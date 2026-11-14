package com.google.android.as.oss.http.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bridge between the Astrea HTTP gRPC service and Ambient Music Mod's local resumable
 * cache, diagnostics, and download logger.
 */
public final class AstreaCacheBridge {

  public interface Handler {
    /**
     * Checks whether the requested URL is present and complete in the local AMM cache.
     * Returns the complete verified File if available, or null on a cache miss.
     */
    @Nullable
    File getCompletedCacheFile(@NonNull String url);

    /**
     * Returns the ETag recorded for a cached URL if known, or a deterministic ETag based on size.
     */
    @Nullable
    String getCachedETag(@NonNull String url, @NonNull File file);

    /**
     * Called when PAM requests a pack URL that is not yet in the completed cache.
     * Should enqueue/start the background resumable download in AMM and return true if handled
     * (so Astrea immediately returns UNAVAILABLE to Superpacks instead of doing a slow unresumable
     * direct download).
     */
    boolean onCacheMiss(@NonNull String url, @NonNull Map<String, List<String>> requestHeaders);

    /**
     * Records a structured request/response event from Astrea into AMM's download log & diagnostics.
     */
    void onAstreaRequestEvent(
        @NonNull String url,
        @NonNull Map<String, List<String>> requestHeaders,
        int responseCode,
        long bytesTransferred,
        long durationMs,
        boolean servedFromCache,
        @Nullable String note,
        @Nullable Throwable error);
  }

  private static final int MAX_MEMORY_LOGS = 500;
  private static final List<String> memoryLogs =
      Collections.synchronizedList(new ArrayList<>());

  @Nullable private static volatile Handler handler;
  @Nullable private static volatile File logFile;
  private static volatile long lastAstreaRequestTimeMs = 0L;
  @Nullable private static volatile String lastAstreaRequestUrl = null;
  private static volatile int totalRequestsHandled = 0;
  private static volatile int cacheHitsCount = 0;
  private static volatile int cacheMissesCount = 0;

  private AstreaCacheBridge() {}

  public static void setHandler(@Nullable Handler newHandler) {
    handler = newHandler;
  }

  @Nullable
  public static Handler getHandler() {
    return handler;
  }

  public static void setLogFile(@Nullable File file) {
    logFile = file;
  }

  public static long getLastAstreaRequestTimeMs() {
    return lastAstreaRequestTimeMs;
  }

  @Nullable
  public static String getLastAstreaRequestUrl() {
    return lastAstreaRequestUrl;
  }

  public static int getTotalRequestsHandled() {
    return totalRequestsHandled;
  }

  public static int getCacheHitsCount() {
    return cacheHitsCount;
  }

  public static int getCacheMissesCount() {
    return cacheMissesCount;
  }

  public static void recordMetrics(String url, boolean cacheHit, boolean cacheMiss) {
    lastAstreaRequestTimeMs = System.currentTimeMillis();
    lastAstreaRequestUrl = url;
    totalRequestsHandled++;
    if (cacheHit) {
      cacheHitsCount++;
    }
    if (cacheMiss) {
      cacheMissesCount++;
    }
  }

  public static synchronized void appendLog(@NonNull String tag, @NonNull String message) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    String line = String.format(Locale.US, "%s [%s] %s", sdf.format(new Date()), tag, message);
    synchronized (memoryLogs) {
      if (memoryLogs.size() >= MAX_MEMORY_LOGS) {
        memoryLogs.remove(0);
      }
      memoryLogs.add(line);
    }
    File target = logFile;
    if (target != null) {
      try {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
          parent.mkdirs();
        }
        // Rotate if > 2MB
        if (target.exists() && target.length() > 2 * 1024 * 1024) {
          File backup = new File(target.getParentFile(), target.getName() + ".old");
          if (backup.exists()) {
            backup.delete();
          }
          target.renameTo(backup);
        }
        try (PrintWriter out = new PrintWriter(new FileWriter(target, true))) {
          out.println(line);
        }
      } catch (IOException ignored) {
      }
    }
  }

  @NonNull
  public static List<String> getMemoryLogs() {
    synchronized (memoryLogs) {
      return new ArrayList<>(memoryLogs);
    }
  }

  public static synchronized void clearLogs() {
    synchronized (memoryLogs) {
      memoryLogs.clear();
    }
    File target = logFile;
    if (target != null && target.exists()) {
      target.delete();
    }
  }
}
