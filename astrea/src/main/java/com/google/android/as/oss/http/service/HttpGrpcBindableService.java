/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.as.oss.http.service;

import static com.google.android.as.oss.grpc.ContextKeys.WRITEABLE_FILE_CONTEXT_KEY;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.as.oss.common.ExecutorAnnotations.IoExecutorQualifier;
import com.google.android.as.oss.common.config.ConfigReader;
import com.google.android.as.oss.grpc.GrpcStatusProto;
import com.google.android.as.oss.http.api.proto.HttpDownloadRequest;
import com.google.android.as.oss.http.api.proto.HttpDownloadResponse;
import com.google.android.as.oss.http.api.proto.HttpProperty;
import com.google.android.as.oss.http.api.proto.HttpServiceGrpc;
import com.google.android.as.oss.http.api.proto.ResponseBodyChunk;
import com.google.android.as.oss.http.api.proto.ResponseHeaders;
import com.google.android.as.oss.http.api.proto.UnrecognizedUrlException;
import com.google.android.as.oss.http.config.PcsHttpConfig;
import com.google.android.as.oss.networkusage.db.ConnectionDetails;
import com.google.android.as.oss.networkusage.db.ConnectionDetails.ConnectionType;
import com.google.android.as.oss.networkusage.db.NetworkUsageEntity;
import com.google.android.as.oss.networkusage.db.NetworkUsageLogRepository;
import com.google.android.as.oss.networkusage.db.NetworkUsageLogUtils;
import com.google.android.as.oss.networkusage.db.Status;
import com.google.android.as.oss.networkusage.ui.content.UnrecognizedNetworkRequestException;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bindable Service that handles HTTP requests to Private Compute Services. */
public class HttpGrpcBindableService extends HttpServiceGrpc.HttpServiceImplBase {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final String LOG_TAG = "AstreaHttp";

  @VisibleForTesting static final int BUFFER_LENGTH = 8_192;

  private static final Set<String> ALLOWED_HOSTS =
      ImmutableSet.of(
          "storage.googleapis.com",
          "dl.google.com",
          "www.gstatic.com",
          "raw.githubusercontent.com",
          "api.github.com",
          "github.com",
          "objects.githubusercontent.com");

  private static final Pattern RANGE_HEADER_PATTERN =
      Pattern.compile("bytes=(\\d+)-(\\d*)");

  private final OkHttpClient client;
  private final Executor executor;
  private final NetworkUsageLogRepository networkUsageLogRepository;
  private final ConfigReader<PcsHttpConfig> configReader;

  @Inject
  HttpGrpcBindableService(
      OkHttpClient client,
      @IoExecutorQualifier Executor ioExecutor,
      NetworkUsageLogRepository networkUsageLogRepository,
      ConfigReader<PcsHttpConfig> httpConfigReader) {
    this.client = client;
    this.executor = ioExecutor;
    this.networkUsageLogRepository = networkUsageLogRepository;
    this.configReader = httpConfigReader;
  }

  @Override
  public void download(
      HttpDownloadRequest request, StreamObserver<HttpDownloadResponse> responseObserver) {
    final long startTimeMs = SystemClock.elapsedRealtime();
    final String url = request.getUrl() != null ? request.getUrl() : "";
    final Map<String, List<String>> reqHeadersMap = extractRequestHeaders(request);
    final String headersSummary = formatHeadersForLog(reqHeadersMap);

    final ParcelFileDescriptor pfdSnapshot = WRITEABLE_FILE_CONTEXT_KEY.get();
    String initMsg =
        String.format(
            Locale.US,
            "REQUEST url=%s pfd=%s headers=%s",
            url,
            (pfdSnapshot != null ? "present" : "null"),
            headersSummary);
    Log.i(LOG_TAG, initMsg);
    AstreaCacheBridge.appendLog(LOG_TAG, initMsg);
    logger.atInfo().log("Downloading requested for URL '%s' (headers: %s)", url, headersSummary);

    // Snapshot config so we use consistent values for a single request.
    PcsHttpConfig config = configReader.getConfig();

    // To suppress an exception in onNext in case the observer was cancelled.
    if (responseObserver instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver<HttpDownloadResponse>) responseObserver)
          .setOnCancelHandler(() -> {});
    }

    // 1. Validate HTTPS-only and allowed hosts
    if (!isValidHttpsUrl(url)) {
      String errMsg = String.format(Locale.US, "Rejecting non-HTTPS URL: '%s'", url);
      Log.w(LOG_TAG, errMsg);
      AstreaCacheBridge.appendLog(LOG_TAG, errMsg);
      IllegalArgumentException ex = new IllegalArgumentException(errMsg);
      notifyBridgeEvent(url, reqHeadersMap, -1, 0L, startTimeMs, false, "non_https_rejected", ex);
      closePfdQuietly(pfdSnapshot);
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT.withDescription(errMsg).asRuntimeException());
      return;
    }

    if (!isAllowedHost(url)) {
      String errMsg = String.format(Locale.US, "Rejecting disallowed host for URL: '%s'", url);
      Log.w(LOG_TAG, errMsg);
      AstreaCacheBridge.appendLog(LOG_TAG, errMsg);
      IllegalArgumentException ex = new IllegalArgumentException(errMsg);
      notifyBridgeEvent(url, reqHeadersMap, -1, 0L, startTimeMs, false, "host_rejected", ex);
      closePfdQuietly(pfdSnapshot);
      responseObserver.onError(
          io.grpc.Status.PERMISSION_DENIED.withDescription(errMsg).asRuntimeException());
      return;
    }

    // 2. Check AMM local cache first (Phase 2)
    AstreaCacheBridge.Handler bridgeHandler = AstreaCacheBridge.getHandler();
    if (bridgeHandler != null) {
      File cachedFile = bridgeHandler.getCompletedCacheFile(url);
      if (cachedFile != null && cachedFile.exists() && cachedFile.isFile() && cachedFile.length() > 0) {
        AstreaCacheBridge.recordMetrics(url, true, false);
        serveFromLocalCache(
            request,
            url,
            reqHeadersMap,
            cachedFile,
            bridgeHandler.getCachedETag(url, cachedFile),
            config,
            pfdSnapshot,
            responseObserver,
            startTimeMs);
        return;
      }

      // Check if this is a pack download that AMM should download in the background
      if (bridgeHandler.onCacheMiss(url, reqHeadersMap)) {
        AstreaCacheBridge.recordMetrics(url, false, true);
        closePfdQuietly(pfdSnapshot);
        long elapsed = SystemClock.elapsedRealtime() - startTimeMs;
        String missMsg =
            String.format(
                Locale.US,
                "CACHE MISS url=%s -> queued in AMM background downloader, returning UNAVAILABLE (%dms)",
                url,
                elapsed);
        Log.i(LOG_TAG, missMsg);
        AstreaCacheBridge.appendLog(LOG_TAG, missMsg);
        notifyBridgeEvent(
            url, reqHeadersMap, 503, 0L, startTimeMs, false, "cache_miss_queued", null);
        responseObserver.onError(
            io.grpc.Status.UNAVAILABLE
                .withDescription("Pack queued in AMM local cache downloader; retry shortly")
                .asRuntimeException());
        return;
      }
    }

    AstreaCacheBridge.recordMetrics(url, false, false);

    // Log Unrecognized requests
    if (!networkUsageLogRepository.isKnownConnection(ConnectionType.HTTP, url)) {
      logger.atInfo().log("Network usage log unrecognised HTTPS request for %s", url);
    }

    if (networkUsageLogRepository.shouldRejectRequest(ConnectionType.HTTP, url)) {
      UnrecognizedNetworkRequestException exception =
          UnrecognizedNetworkRequestException.forUrl(url);
      logger.atWarning().withCause(exception).log("Rejected unknown HTTPS request to PCS");
      notifyBridgeEvent(
          url, reqHeadersMap, 400, 0L, startTimeMs, false, "network_usage_rejected", exception);
      closePfdQuietly(pfdSnapshot);

      com.google.rpc.Status statusProto =
          com.google.rpc.Status.newBuilder()
              .setCode(Code.INVALID_ARGUMENT.value())
              .setMessage(exception.getMessage())
              .addDetails(
                  GrpcStatusProto.packIntoAny(
                      UnrecognizedUrlException.newBuilder().setUrl(url).build()))
              .build();
      responseObserver.onError(GrpcStatusProto.toStatusRuntimeException(statusProto));
      return;
    }

    Request.Builder okRequest = new Request.Builder().url(url);

    for (HttpProperty property : request.getRequestPropertyList()) {
      for (String value : property.getValueList()) {
        okRequest.addHeader(property.getKey(), value);
      }
    }

    Response response;
    try {
      response = client.newCall(okRequest.build()).execute();
    } catch (IOException e) {
      long duration = SystemClock.elapsedRealtime() - startTimeMs;
      String errLog =
          String.format(
              Locale.US,
              "HTTP ERROR url=%s duration=%dms error=%s: %s",
              url,
              duration,
              e.getClass().getSimpleName(),
              e.getMessage());
      Log.e(LOG_TAG, errLog, e);
      AstreaCacheBridge.appendLog(LOG_TAG, errLog);
      notifyBridgeEvent(url, reqHeadersMap, -1, 0L, startTimeMs, false, "network_io_error", e);
      closePfdQuietly(pfdSnapshot);
      responseObserver.onError(
          io.grpc.Status.UNAVAILABLE
              .withDescription(e.getClass().getSimpleName() + ": " + e.getMessage())
              .withCause(e)
              .asRuntimeException());
      insertNetworkUsageLogRow(networkUsageLogRepository, request, Status.FAILED, 0L);
      return;
    }

    final int responseCode = response.code();
    ResponseHeaders.Builder responseHeaders =
        ResponseHeaders.newBuilder().setResponseCode(responseCode);
    for (String name : response.headers().names()) {
      responseHeaders.addHeader(
          HttpProperty.newBuilder()
              .setKey(name)
              .addAllValue(response.headers().values(name))
              .build());
    }

    String hdrLog =
        String.format(
            Locale.US,
            "RESPONSE HEADERS url=%s code=%d contentLength=%s contentType=%s",
            url,
            responseCode,
            response.header("Content-Length", "unknown"),
            response.header("Content-Type", "unknown"));
    Log.i(LOG_TAG, hdrLog);
    AstreaCacheBridge.appendLog(LOG_TAG, hdrLog);
    logger.atInfo().log("Responding with header information for URL '%s' (code %d)", url, responseCode);
    responseObserver.onNext(
        HttpDownloadResponse.newBuilder().setResponseHeaders(responseHeaders).build());

    ResponseBody body = response.body();

    if (body == null) {
      closePfdQuietly(pfdSnapshot);
      long duration = SystemClock.elapsedRealtime() - startTimeMs;
      String emptyLog =
          String.format(
              Locale.US,
              "EMPTY BODY COMPLETE url=%s code=%d duration=%dms",
              url,
              responseCode,
              duration);
      Log.i(LOG_TAG, emptyLog);
      AstreaCacheBridge.appendLog(LOG_TAG, emptyLog);
      notifyBridgeEvent(url, reqHeadersMap, responseCode, 0L, startTimeMs, false, "empty_body", null);
      responseObserver.onCompleted();
      insertNetworkUsageLogRow(networkUsageLogRepository, request, Status.SUCCEEDED, 0L);
      return;
    }

    final ParcelFileDescriptor pfd = config.writeToPfd() ? pfdSnapshot : null;
    if (config.onReadyHandlerEnabled() && pfd == null && responseObserver instanceof ServerCallStreamObserver) {
      // Only use onReadyHandler if it is enabled by flag AND we have not received a direct pfd.
      ServerCallStreamObserver<HttpDownloadResponse> serverStreamObserver =
          (ServerCallStreamObserver<HttpDownloadResponse>) responseObserver;

      Runnable onReadyHandler =
          new HttpGrpcStreamHandler(
              request,
              reqHeadersMap,
              responseCode,
              startTimeMs,
              serverStreamObserver,
              body.byteStream(),
              config.ipcStreamingThrottleMs(),
              executor,
              networkUsageLogRepository);
      serverStreamObserver.setOnReadyHandler(onReadyHandler);
      // First call is required to be manual as per GRPC docs.
      onReadyHandler.run();
    } else {
      executor.execute(
          new Runnable() {
            private long totalBytesRead = 0;

            @Override
            public void run() {
              try (InputStream is = body.byteStream();
                  OutputStream os =
                      (pfd == null) ? null : new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
                byte[] buffer = new byte[BUFFER_LENGTH];

                while (true) {
                  if (responseObserver instanceof ServerCallStreamObserver
                      && ((ServerCallStreamObserver<HttpDownloadResponse>) responseObserver)
                          .isCancelled()) {
                    logCallCancelledByClient(
                        null,
                        networkUsageLogRepository,
                        request,
                        reqHeadersMap,
                        responseCode,
                        startTimeMs,
                        totalBytesRead);
                    return;
                  }
                  int bytesRead = is.read(buffer);
                  if (bytesRead == -1) {
                    break;
                  }

                  if (bytesRead > 0) {
                    totalBytesRead += bytesRead;
                    if (os != null) {
                      os.write(buffer, 0, bytesRead);
                    } else {
                      responseObserver.onNext(
                          HttpDownloadResponse.newBuilder()
                              .setResponseBodyChunk(
                                  ResponseBodyChunk.newBuilder()
                                      .setResponseBytes(ByteString.copyFrom(buffer, 0, bytesRead))
                                      .build())
                              .build());
                    }
                  }
                }

                if (os != null) {
                  os.flush();
                }

                long duration = SystemClock.elapsedRealtime() - startTimeMs;
                String doneLog =
                    String.format(
                        Locale.US,
                        "[pfd-write] DOWNLOAD COMPLETE url=%s code=%d bytes=%d duration=%dms",
                        url,
                        responseCode,
                        totalBytesRead,
                        duration);
                Log.i(LOG_TAG, doneLog);
                AstreaCacheBridge.appendLog(LOG_TAG, doneLog);
                logger.atInfo().log(
                    "[pfd-write] DOWNLOAD COMPLETE: Downloaded %d bytes from URL [%s] in %d ms.",
                    totalBytesRead, url, duration);
                notifyBridgeEvent(
                    url,
                    reqHeadersMap,
                    responseCode,
                    totalBytesRead,
                    startTimeMs,
                    false,
                    "network_stream_complete",
                    null);
                responseObserver.onCompleted();
                insertNetworkUsageLogRow(
                    networkUsageLogRepository, request, Status.SUCCEEDED, totalBytesRead);
              } catch (IOException e) {
                long duration = SystemClock.elapsedRealtime() - startTimeMs;
                String errLog =
                    String.format(
                        Locale.US,
                        "[pfd-write] IO ERROR url=%s code=%d bytesSoFar=%d duration=%dms error=%s: %s",
                        url,
                        responseCode,
                        totalBytesRead,
                        duration,
                        e.getClass().getSimpleName(),
                        e.getMessage());
                Log.w(LOG_TAG, errLog, e);
                AstreaCacheBridge.appendLog(LOG_TAG, errLog);
                logger.atWarning().withCause(e).log(
                    "Failed performing IO operation while handling URL '%s'", url);
                notifyBridgeEvent(
                    url,
                    reqHeadersMap,
                    responseCode,
                    totalBytesRead,
                    startTimeMs,
                    false,
                    "stream_io_error",
                    e);
                responseObserver.onError(
                    io.grpc.Status.UNAVAILABLE
                        .withDescription(e.getClass().getSimpleName() + ": " + e.getMessage())
                        .withCause(e)
                        .asRuntimeException());
                insertNetworkUsageLogRow(
                    networkUsageLogRepository, request, Status.FAILED, totalBytesRead);
              } catch (StatusRuntimeException e) {
                if (responseObserver instanceof ServerCallStreamObserver
                    && ((ServerCallStreamObserver<?>) responseObserver).isCancelled()) {
                  logCallCancelledByClient(
                      e,
                      networkUsageLogRepository,
                      request,
                      reqHeadersMap,
                      responseCode,
                      startTimeMs,
                      totalBytesRead);
                } else {
                  notifyBridgeEvent(
                      url,
                      reqHeadersMap,
                      responseCode,
                      totalBytesRead,
                      startTimeMs,
                      false,
                      "grpc_status_exception",
                      e);
                  responseObserver.onError(e);
                  insertNetworkUsageLogRow(
                      networkUsageLogRepository, request, Status.FAILED, totalBytesRead);
                }
              }
            }
          });
    }
  }

  /**
   * Streams a verified file from AMM's local cache into PAM's ParcelFileDescriptor (or gRPC chunks),
   * honouring HTTP Range requests (returning 206 Partial Content + Content-Range) or 200 OK.
   */
  @VisibleForTesting
  void serveFromLocalCache(
      HttpDownloadRequest request,
      String url,
      Map<String, List<String>> reqHeadersMap,
      File cachedFile,
      @Nullable String cachedETag,
      PcsHttpConfig config,
      @Nullable ParcelFileDescriptor pfdSnapshot,
      StreamObserver<HttpDownloadResponse> responseObserver,
      long startTimeMs) {
    final long fileLength = cachedFile.length();
    final String etag =
        (cachedETag != null && !cachedETag.isEmpty())
            ? cachedETag
            : ("\"" + fileLength + "-" + cachedFile.lastModified() + "\"");
    final String contentType =
        url.endsWith(".json") ? "application/json" : "application/octet-stream";

    // Check Range header (case-insensitive)
    String rangeHeader = getFirstHeaderIgnoreCase(reqHeadersMap, "Range");
    String ifRangeHeader = getFirstHeaderIgnoreCase(reqHeadersMap, "If-Range");
    long startOffset = 0L;
    long endOffset = fileLength - 1L;
    boolean isPartial = false;

    if (rangeHeader != null && !rangeHeader.isEmpty()) {
      boolean ifRangeMatches =
          (ifRangeHeader == null || ifRangeHeader.isEmpty() || ifRangeHeader.equals(etag));
      if (ifRangeMatches) {
        long[] parsedRange = parseRangeHeader(rangeHeader, fileLength);
        if (parsedRange != null) {
          startOffset = parsedRange[0];
          endOffset = parsedRange[1];
          isPartial = true;
        }
      }
    }

    final long contentLength = (fileLength == 0) ? 0 : (endOffset - startOffset + 1L);
    final int statusCode = isPartial ? 206 : 200;

    ResponseHeaders.Builder headersBuilder =
        ResponseHeaders.newBuilder()
            .setResponseCode(statusCode)
            .addHeader(
                HttpProperty.newBuilder()
                    .setKey("Content-Length")
                    .addValue(String.valueOf(contentLength))
                    .build())
            .addHeader(
                HttpProperty.newBuilder()
                    .setKey("Content-Type")
                    .addValue(contentType)
                    .build())
            .addHeader(
                HttpProperty.newBuilder()
                    .setKey("Accept-Ranges")
                    .addValue("bytes")
                    .build())
            .addHeader(
                HttpProperty.newBuilder()
                    .setKey("ETag")
                    .addValue(etag)
                    .build());

    if (isPartial) {
      headersBuilder.addHeader(
          HttpProperty.newBuilder()
              .setKey("Content-Range")
              .addValue(
                  String.format(
                      Locale.US, "bytes %d-%d/%d", startOffset, endOffset, fileLength))
              .build());
    }

    responseObserver.onNext(
        HttpDownloadResponse.newBuilder().setResponseHeaders(headersBuilder.build()).build());

    final ParcelFileDescriptor pfd = config.writeToPfd() ? pfdSnapshot : null;
    final long finalStartOffset = startOffset;
    final long finalContentLength = contentLength;

    executor.execute(
        () -> {
          long bytesWritten = 0L;
          try (RandomAccessFile raf = new RandomAccessFile(cachedFile, "r");
              OutputStream os =
                  (pfd == null) ? null : new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
            if (finalStartOffset > 0) {
              raf.seek(finalStartOffset);
            }
            byte[] buffer = new byte[BUFFER_LENGTH];
            long remaining = finalContentLength;
            while (remaining > 0) {
              if (responseObserver instanceof ServerCallStreamObserver
                  && ((ServerCallStreamObserver<?>) responseObserver).isCancelled()) {
                logCallCancelledByClient(
                    null,
                    networkUsageLogRepository,
                    request,
                    reqHeadersMap,
                    statusCode,
                    startTimeMs,
                    bytesWritten);
                return;
              }
              int toRead = (int) Math.min(buffer.length, remaining);
              int read = raf.read(buffer, 0, toRead);
              if (read == -1) {
                break;
              }
              if (read > 0) {
                if (os != null) {
                  os.write(buffer, 0, read);
                } else {
                  responseObserver.onNext(
                      HttpDownloadResponse.newBuilder()
                          .setResponseBodyChunk(
                              ResponseBodyChunk.newBuilder()
                                  .setResponseBytes(ByteString.copyFrom(buffer, 0, read))
                                  .build())
                          .build());
                }
                bytesWritten += read;
                remaining -= read;
              }
            }
            if (os != null) {
              os.flush();
            }
            long duration = SystemClock.elapsedRealtime() - startTimeMs;
            String hitMsg =
                String.format(
                    Locale.US,
                    "CACHE HIT COMPLETE url=%s status=%d bytes=%d/%d (offset=%d) duration=%dms",
                    url,
                    statusCode,
                    bytesWritten,
                    fileLength,
                    finalStartOffset,
                    duration);
            Log.i(LOG_TAG, hitMsg);
            AstreaCacheBridge.appendLog(LOG_TAG, hitMsg);
            notifyBridgeEvent(
                url,
                reqHeadersMap,
                statusCode,
                bytesWritten,
                startTimeMs,
                true,
                "served_from_local_cache",
                null);
            responseObserver.onCompleted();
            insertNetworkUsageLogRow(
                networkUsageLogRepository, request, Status.SUCCEEDED, bytesWritten);
          } catch (Exception e) {
            long duration = SystemClock.elapsedRealtime() - startTimeMs;
            String errMsg =
                String.format(
                    Locale.US,
                    "CACHE HIT STREAM ERROR url=%s bytes=%d duration=%dms error=%s: %s",
                    url,
                    bytesWritten,
                    duration,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            Log.e(LOG_TAG, errMsg, e);
            AstreaCacheBridge.appendLog(LOG_TAG, errMsg);
            notifyBridgeEvent(
                url,
                reqHeadersMap,
                statusCode,
                bytesWritten,
                startTimeMs,
                true,
                "cache_stream_error",
                e);
            responseObserver.onError(
                io.grpc.Status.INTERNAL
                    .withDescription("Cache stream failed: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
          }
        });
  }

  /**
   * Parses an HTTP Range header of the form "bytes=start-end" or "bytes=start-".
   * Returns a 2-element array [start, end] inclusive, or null if invalid/unsatisfiable.
   */
  @VisibleForTesting
  @Nullable
  public static long[] parseRangeHeader(@Nullable String rangeHeader, long fileLength) {
    if (rangeHeader == null || fileLength <= 0) {
      return null;
    }
    Matcher matcher = RANGE_HEADER_PATTERN.matcher(rangeHeader.trim());
    if (!matcher.matches()) {
      return null;
    }
    try {
      String startStr = matcher.group(1);
      String endStr = matcher.group(2);
      long start = Long.parseLong(startStr);
      long end =
          (endStr == null || endStr.isEmpty()) ? (fileLength - 1L) : Long.parseLong(endStr);
      if (start < 0 || start >= fileLength) {
        return null;
      }
      if (end >= fileLength) {
        end = fileLength - 1L;
      }
      if (end < start) {
        return null;
      }
      return new long[] {start, end};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @VisibleForTesting
  public static boolean isValidHttpsUrl(@Nullable String url) {
    return url != null && url.startsWith("https://");
  }

  @VisibleForTesting
  public static boolean isAllowedHost(@Nullable String url) {
    if (!isValidHttpsUrl(url)) {
      return false;
    }
    try {
      Uri uri = Uri.parse(url);
      String host = uri.getHost();
      if (host == null) {
        // Fallback manual host parse for JVM unit tests where android.net.Uri might be stubbed
        String withoutScheme = url.substring("https://".length());
        int slashIdx = withoutScheme.indexOf('/');
        host = (slashIdx >= 0) ? withoutScheme.substring(0, slashIdx) : withoutScheme;
      }
      return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.US));
    } catch (Exception e) {
      String withoutScheme = url.substring("https://".length());
      int slashIdx = withoutScheme.indexOf('/');
      String host = (slashIdx >= 0) ? withoutScheme.substring(0, slashIdx) : withoutScheme;
      return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.US));
    }
  }

  private static void closePfdQuietly(@Nullable ParcelFileDescriptor pfd) {
    if (pfd != null) {
      try {
        pfd.close();
      } catch (IOException ignored) {
      }
    }
  }

  @NonNull
  private static Map<String, List<String>> extractRequestHeaders(HttpDownloadRequest request) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (HttpProperty prop : request.getRequestPropertyList()) {
      String key = prop.getKey();
      List<String> values = new ArrayList<>(prop.getValueList());
      map.put(key, values);
    }
    return map;
  }

  @Nullable
  private static String getFirstHeaderIgnoreCase(
      Map<String, List<String>> headers, String targetKey) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() != null
          && entry.getKey().equalsIgnoreCase(targetKey)
          && entry.getValue() != null
          && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }

  @NonNull
  private static String formatHeadersForLog(Map<String, List<String>> headers) {
    if (headers.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(entry.getKey()).append("=").append(entry.getValue());
    }
    sb.append("}");
    return sb.toString();
  }

  private static void notifyBridgeEvent(
      String url,
      Map<String, List<String>> reqHeaders,
      int responseCode,
      long bytes,
      long startTimeMs,
      boolean servedFromCache,
      @Nullable String note,
      @Nullable Throwable error) {
    AstreaCacheBridge.Handler handler = AstreaCacheBridge.getHandler();
    if (handler != null) {
      long duration = Math.max(0L, SystemClock.elapsedRealtime() - startTimeMs);
      try {
        handler.onAstreaRequestEvent(
            url, reqHeaders, responseCode, bytes, duration, servedFromCache, note, error);
      } catch (Throwable ignored) {
      }
    }
  }

  private static void logCallCancelledByClient(
      @Nullable Throwable error,
      NetworkUsageLogRepository networkUsageLogRepository,
      HttpDownloadRequest request,
      Map<String, List<String>> reqHeadersMap,
      int responseCode,
      long startTimeMs,
      long size) {
    long duration = SystemClock.elapsedRealtime() - startTimeMs;
    String cancelMsg =
        String.format(
            Locale.US,
            "CANCELLED BY CLIENT url=%s code=%d bytesSoFar=%d duration=%dms",
            request.getUrl(),
            responseCode,
            size,
            duration);
    Log.w(LOG_TAG, cancelMsg, error);
    AstreaCacheBridge.appendLog(LOG_TAG, cancelMsg);
    logger.atWarning().withCause(error).log(
        "Failed to fetch response body for URL '%s'. Call cancelled by client.", request.getUrl());
    notifyBridgeEvent(
        request.getUrl(),
        reqHeadersMap,
        responseCode,
        size,
        startTimeMs,
        false,
        "cancelled_by_client",
        error);
    insertNetworkUsageLogRow(networkUsageLogRepository, request, Status.FAILED, size);
  }

  private static void insertNetworkUsageLogRow(
      NetworkUsageLogRepository networkUsageLogRepository,
      HttpDownloadRequest request,
      Status status,
      long size) {
    if (!networkUsageLogRepository.shouldLogNetworkUsage(ConnectionType.HTTP, request.getUrl())
        || !networkUsageLogRepository.getContentMap().isPresent()) {
      return;
    }

    ConnectionDetails connectionDetails =
        networkUsageLogRepository
            .getContentMap()
            .get()
            .getHttpConnectionDetails(request.getUrl())
            .get();
    NetworkUsageEntity entity =
        NetworkUsageLogUtils.createHttpNetworkUsageEntity(
            connectionDetails, status, size, request.getUrl());

    networkUsageLogRepository.insertNetworkUsageEntity(entity);
  }

  private static class HttpGrpcStreamHandler implements Runnable {
    private final AtomicLong totalBytesRead = new AtomicLong(0);

    @GuardedBy("this")
    private final byte[] buffer = new byte[BUFFER_LENGTH];

    @GuardedBy("this")
    private int bytesPendingToBeSent = 0;

    private final HttpDownloadRequest request;
    private final Map<String, List<String>> reqHeadersMap;
    private final int responseCode;
    private final long startTimeMs;
    private final ServerCallStreamObserver<HttpDownloadResponse> responseObserver;
    private final InputStream bodyStream;
    private final int ipcStreamingThrottleMs;
    private final Executor backgroundExecutor;
    private final NetworkUsageLogRepository networkUsageLogRepository;

    public HttpGrpcStreamHandler(
        HttpDownloadRequest request,
        Map<String, List<String>> reqHeadersMap,
        int responseCode,
        long startTimeMs,
        ServerCallStreamObserver<HttpDownloadResponse> serverStreamObserver,
        InputStream is,
        int ipcStreamingThrottleMs,
        Executor executor,
        NetworkUsageLogRepository networkUsageLogRepository) {
      this.request = request;
      this.reqHeadersMap = reqHeadersMap;
      this.responseCode = responseCode;
      this.startTimeMs = startTimeMs;
      this.responseObserver = serverStreamObserver;
      this.bodyStream = is;
      this.ipcStreamingThrottleMs = ipcStreamingThrottleMs;
      this.backgroundExecutor = executor;
      this.networkUsageLogRepository = networkUsageLogRepository;
    }

    @Override
    public synchronized void run() {
      logger.atFine().log(
          "onReadyHandler called for URL [%s]. Bytes sent so far: [%d].",
          request.getUrl(), totalBytesRead.get());
      boolean saveStreamToResumeWhenClientIsReadyAgain = false;
      try {
        while (bytesPendingToBeSent >= 0) {
          if (bytesPendingToBeSent == 0) {
            if (responseObserver.isCancelled()) {
              logCallCancelledByClient(
                  null,
                  networkUsageLogRepository,
                  request,
                  reqHeadersMap,
                  responseCode,
                  startTimeMs,
                  totalBytesRead.get());
              return;
            }
            bytesPendingToBeSent = bodyStream.read(buffer);
          }

          if (bytesPendingToBeSent == -1) {
            break;
          }

          if (bytesPendingToBeSent > 0) {
            if (!responseObserver.isReady()) {
              saveStreamToResumeWhenClientIsReadyAgain = true;
              return;
            }
            responseObserver.onNext(
                HttpDownloadResponse.newBuilder()
                    .setResponseBodyChunk(
                        ResponseBodyChunk.newBuilder()
                            .setResponseBytes(ByteString.copyFrom(buffer, 0, bytesPendingToBeSent))
                            .build())
                    .build());
            totalBytesRead.addAndGet(bytesPendingToBeSent);
            bytesPendingToBeSent = 0;

            if (ipcStreamingThrottleMs > 0 && totalBytesRead.get() % 16_000_000 < BUFFER_LENGTH) {
              SystemClock.sleep(ipcStreamingThrottleMs);
            }
          }
        }

        long duration = SystemClock.elapsedRealtime() - startTimeMs;
        String doneMsg =
            String.format(
                Locale.US,
                "[onReadyHandler] DOWNLOAD COMPLETE url=%s code=%d bytes=%d duration=%dms",
                request.getUrl(),
                responseCode,
                totalBytesRead.get(),
                duration);
        Log.i(LOG_TAG, doneMsg);
        AstreaCacheBridge.appendLog(LOG_TAG, doneMsg);
        notifyBridgeEvent(
            request.getUrl(),
            reqHeadersMap,
            responseCode,
            totalBytesRead.get(),
            startTimeMs,
            false,
            "on_ready_complete",
            null);
        try {
          responseObserver.onCompleted();
        } catch (IllegalStateException ignored) {
        }
        backgroundExecutor.execute(
            () ->
                insertNetworkUsageLogRow(
                    networkUsageLogRepository, request, Status.SUCCEEDED, totalBytesRead.get()));
      } catch (IOException e) {
        long duration = SystemClock.elapsedRealtime() - startTimeMs;
        String errMsg =
            String.format(
                Locale.US,
                "[onReadyHandler] IO ERROR url=%s code=%d bytes=%d duration=%dms error=%s",
                request.getUrl(),
                responseCode,
                totalBytesRead.get(),
                duration,
                e.getMessage());
        Log.w(LOG_TAG, errMsg, e);
        AstreaCacheBridge.appendLog(LOG_TAG, errMsg);
        notifyBridgeEvent(
            request.getUrl(),
            reqHeadersMap,
            responseCode,
            totalBytesRead.get(),
            startTimeMs,
            false,
            "on_ready_io_error",
            e);
        responseObserver.onError(e);
        backgroundExecutor.execute(
            () ->
                insertNetworkUsageLogRow(
                    networkUsageLogRepository, request, Status.FAILED, totalBytesRead.get()));
      } catch (StatusRuntimeException e) {
        if (responseObserver.isCancelled()) {
          logCallCancelledByClient(
              e,
              networkUsageLogRepository,
              request,
              reqHeadersMap,
              responseCode,
              startTimeMs,
              totalBytesRead.get());
        } else {
          notifyBridgeEvent(
              request.getUrl(),
              reqHeadersMap,
              responseCode,
              totalBytesRead.get(),
              startTimeMs,
              false,
              "on_ready_status_error",
              e);
          responseObserver.onError(e);
          backgroundExecutor.execute(
              () ->
                  insertNetworkUsageLogRow(
                      networkUsageLogRepository, request, Status.FAILED, totalBytesRead.get()));
        }
      } finally {
        if (!saveStreamToResumeWhenClientIsReadyAgain) {
          try {
            bodyStream.close();
          } catch (IOException e) {
            logger.atWarning().withCause(e).log(
                "Encountered an error while closing the download stream");
          }
        }
      }
    }
  }
}
