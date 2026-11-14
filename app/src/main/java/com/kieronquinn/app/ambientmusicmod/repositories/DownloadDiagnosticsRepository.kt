package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.google.android.as.oss.http.service.AstreaCacheBridge
import com.google.gson.Gson
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.model.shards.SuperpacksManifest
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getPackageInfoCompat
import com.kieronquinn.app.ambientmusicmod.utils.extensions.map
import com.kieronquinn.app.ambientmusicmod.utils.extensions.safeQuery
import com.kieronquinn.app.ambientmusicmod.utils.manifest.ManifestUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

interface DownloadDiagnosticsRepository {

    fun runAllDiagnostics(): Flow<List<DiagnosticCheck>>

    enum class CheckStatus {
        PENDING, RUNNING, PASSED, WARNING, FAILED
    }

    data class DiagnosticCheck(
        val id: String,
        val title: String,
        val status: CheckStatus,
        val detail: String,
        val durationMs: Long = 0L
    )
}

class DownloadDiagnosticsRepositoryImpl(
    private val context: Context,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val ambientServiceRepository: AmbientServiceRepository,
    private val shizukuServiceRepository: ShizukuServiceRepository,
    private val shardsCacheRepository: ShardsCacheRepository,
    private val downloadLogRepository: DownloadLogRepository,
    private val gson: Gson
) : DownloadDiagnosticsRepository {

    companion object {
        private const val TAG = "Diagnostics"
        private const val LEVELDB_AUTHORITY = "com.google.android.as.pam.ambientmusic.leveldbprovider"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun runAllDiagnostics(): Flow<List<DownloadDiagnosticsRepository.DiagnosticCheck>> = flow {
        val checks = mutableListOf(
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "dns_https_github_api",
                "1. DNS & HTTPS: api.github.com",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "dns_https_raw_github",
                "2. DNS & HTTPS: raw.githubusercontent.com",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "dns_https_gcs",
                "3. DNS & HTTPS: storage.googleapis.com",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "manifest_fetch_parse",
                "4. Manifest Fetch & Parse",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "ranged_byte_request",
                "5. Ranged Byte Request (HTTP 206)",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "pam_bind_state",
                "6. PAM & Shizuku Bind State",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            ),
            DownloadDiagnosticsRepository.DiagnosticCheck(
                "superpacks_jobs_cache",
                "7. Superpacks Jobs & Astrea Cache",
                DownloadDiagnosticsRepository.CheckStatus.PENDING,
                "Waiting..."
            )
        )

        suspend fun updateCheck(
            index: Int,
            status: DownloadDiagnosticsRepository.CheckStatus,
            detail: String,
            durationMs: Long = 0L
        ) {
            checks[index] = checks[index].copy(
                status = status,
                detail = detail,
                durationMs = durationMs
            )
            emit(checks.toList())
        }

        emit(checks.toList())
        downloadLogRepository.logInfo(TAG, "=== Starting Download Diagnostics Suite ===")

        // 1. DNS & HTTPS: api.github.com
        updateCheck(0, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Resolving DNS and testing HTTPS...")
        val res1 = testDnsAndHttps(
            host = "api.github.com",
            testUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/AmbientMusicMod/releases"
        )
        updateCheck(0, res1.first, res1.second, res1.third)

        // 2. DNS & HTTPS: raw.githubusercontent.com
        updateCheck(1, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Resolving DNS and testing HTTPS...")
        val res2 = testDnsAndHttps(
            host = "raw.githubusercontent.com",
            testUrl = "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release_v3.json"
        )
        updateCheck(1, res2.first, res2.second, res2.third)

        // 3. DNS & HTTPS: storage.googleapis.com
        val activeManifestRaw = deviceConfigRepository.indexManifest.get()
        val activeManifestUrl = ManifestUrlParser.extractHttpsUrl(activeManifestRaw)
        updateCheck(2, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Resolving DNS and testing HTTPS...")
        val res3 = testDnsAndHttps(
            host = "storage.googleapis.com",
            testUrl = activeManifestUrl
        )
        updateCheck(2, res3.first, res3.second, res3.third)

        // 4. Manifest Fetch & Parse
        updateCheck(3, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Fetching and parsing $activeManifestUrl...")
        val t4Start = SystemClock.elapsedRealtime()
        var parsedManifest: SuperpacksManifest? = null
        try {
            val req = Request.Builder().url(activeManifestUrl).get().build()
            client.newCall(req).execute().use { resp ->
                val dur = SystemClock.elapsedRealtime() - t4Start
                if (!resp.isSuccessful) {
                    val msg = "HTTP ${resp.code} ${resp.message} from $activeManifestUrl"
                    downloadLogRepository.logError(TAG, "Diagnostics #4 failed: $msg")
                    updateCheck(3, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
                } else {
                    val bodyStr = resp.body?.string() ?: ""
                    val manifest = SuperpacksManifest.parse(bodyStr, gson)
                    parsedManifest = manifest
                    val primaryCountry = deviceConfigRepository.getPrimaryLanguage()
                    val extraCountries = deviceConfigRepository.getExtraLanguages()
                    val selectedCodes = (listOf(primaryCountry) + extraCountries).distinct()
                    val selectedPacks = manifest.selectPacksForCountries(selectedCodes)
                    val selectedBytes = selectedPacks.sumOf { it.size }
                    val msg = String.format(
                        Locale.US,
                        "OK (%dms): %d total packs in manifest. Selected countries %s -> %d packs (%.1f MB). Active URL: %s",
                        dur,
                        manifest.packs.size,
                        selectedCodes,
                        selectedPacks.size,
                        selectedBytes / (1024.0 * 1024.0),
                        activeManifestUrl
                    )
                    downloadLogRepository.logInfo(TAG, "Diagnostics #4 passed: $msg")
                    updateCheck(3, DownloadDiagnosticsRepository.CheckStatus.PASSED, msg, dur)
                }
            }
        } catch (e: Exception) {
            val dur = SystemClock.elapsedRealtime() - t4Start
            val msg = "${e.javaClass.simpleName}: ${e.message} (URL: $activeManifestUrl)"
            downloadLogRepository.logError(TAG, "Diagnostics #4 exception", e)
            updateCheck(3, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
        }

        // 5. Ranged Byte Request on one real pack
        updateCheck(4, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Testing Range: bytes=0-1023 on core pack...")
        val t5Start = SystemClock.elapsedRealtime()
        val packToTest = parsedManifest?.packs?.firstOrNull { it.isCorePack() }
            ?: parsedManifest?.packs?.firstOrNull()
        val testPackUrl = packToTest?.primaryDownloadUrl
            ?: "https://storage.googleapis.com/music-iq-db/static/tree_core_shard"
        try {
            val req = Request.Builder()
                .url(testPackUrl)
                .header("Range", "bytes=0-1023")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val dur = SystemClock.elapsedRealtime() - t5Start
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val contentRange = resp.header("Content-Range") ?: "missing"
                val etag = resp.header("ETag") ?: "missing"
                val acceptRanges = resp.header("Accept-Ranges") ?: "missing"
                if (resp.code == 206 && bytes.size == 1024) {
                    val msg = "OK (%dms): HTTP 206 Partial Content, received %d bytes from '%s'. Content-Range=%s, Accept-Ranges=%s, ETag=%s".format(
                        Locale.US, dur, bytes.size, testPackUrl, contentRange, acceptRanges, etag
                    )
                    downloadLogRepository.logInfo(TAG, "Diagnostics #5 passed: $msg")
                    updateCheck(4, DownloadDiagnosticsRepository.CheckStatus.PASSED, msg, dur)
                } else {
                    val msg = "Unexpected response (%dms): HTTP %d %s, received %d bytes (expected 1024). Content-Range=%s, URL=%s".format(
                        Locale.US, dur, resp.code, resp.message, bytes.size, contentRange, testPackUrl
                    )
                    downloadLogRepository.logWarn(TAG, "Diagnostics #5 warning/fail: $msg")
                    val st = if (resp.isSuccessful) DownloadDiagnosticsRepository.CheckStatus.WARNING
                    else DownloadDiagnosticsRepository.CheckStatus.FAILED
                    updateCheck(4, st, msg, dur)
                }
            }
        } catch (e: Exception) {
            val dur = SystemClock.elapsedRealtime() - t5Start
            val msg = "${e.javaClass.simpleName}: ${e.message} (URL: $testPackUrl)"
            downloadLogRepository.logError(TAG, "Diagnostics #5 exception", e)
            updateCheck(4, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
        }

        // 6. PAM & Shizuku Bind State
        updateCheck(5, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Checking PAM package, IRecognitionService, and Shizuku...")
        val t6Start = SystemClock.elapsedRealtime()
        try {
            val pamInfo = try {
                context.packageManager.getPackageInfoCompat(PACKAGE_NAME_PAM)
            } catch (e: Exception) {
                null
            }
            if (pamInfo == null) {
                val dur = SystemClock.elapsedRealtime() - t6Start
                val msg = "Now Playing ($PACKAGE_NAME_PAM) is NOT installed."
                downloadLogRepository.logError(TAG, "Diagnostics #6 failed: $msg")
                updateCheck(5, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
            } else {
                val apiCompatible = ApiRepository.assertCompatibility()
                val pamService = ambientServiceRepository.getService()
                val pamPing = try {
                    pamService?.ping() == true
                } catch (e: Exception) {
                    false
                }
                val shizukuReady = shizukuServiceRepository.assertReady()
                val dur = SystemClock.elapsedRealtime() - t6Start
                val msg = "PAM installed: v${pamInfo.versionName} (${pamInfo.longVersionCode}), " +
                    "API compatible=$apiCompatible, IRecognitionService ping=$pamPing, Shizuku ready=$shizukuReady"
                downloadLogRepository.logInfo(TAG, "Diagnostics #6: $msg")
                val status = if (pamPing && apiCompatible && shizukuReady) {
                    DownloadDiagnosticsRepository.CheckStatus.PASSED
                } else if (pamPing) {
                    DownloadDiagnosticsRepository.CheckStatus.WARNING
                } else {
                    DownloadDiagnosticsRepository.CheckStatus.FAILED
                }
                updateCheck(5, status, msg, dur)
            }
        } catch (e: Exception) {
            val dur = SystemClock.elapsedRealtime() - t6Start
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            downloadLogRepository.logError(TAG, "Diagnostics #6 exception", e)
            updateCheck(5, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
        }

        // 7. Pending Superpacks Jobs & Local Cache State
        updateCheck(6, DownloadDiagnosticsRepository.CheckStatus.RUNNING, "Querying PAM LevelDbProvider & local Astrea cache...")
        val t7Start = SystemClock.elapsedRealtime()
        try {
            val jobsUri = Uri.parse("content://$LEVELDB_AUTHORITY/jobs")
            val dlStateUri = Uri.parse("content://$LEVELDB_AUTHORITY/downloadstate")
            val countUri = Uri.parse("content://$LEVELDB_AUTHORITY/count/leveldb")
            val listUri = Uri.parse("content://$LEVELDB_AUTHORITY/list")

            val jobIds = context.contentResolver.safeQuery(jobsUri, null, null, null, null)?.use { c ->
                c.map { it.getInt(0) }
            } ?: emptyList()

            val pendingSuperpacksDownloads = context.contentResolver.safeQuery(dlStateUri, null, null, null, null)?.use { c ->
                c.map { it.getInt(0) }.firstOrNull()
            } ?: -1

            val levelDbShards = context.contentResolver.safeQuery(listUri, null, null, null, null)?.use { c ->
                c.map { it.getString(0) }
            } ?: emptyList()

            val trackCount = context.contentResolver.safeQuery(countUri, null, null, null, null)?.use { c ->
                c.map { it.getInt(0) }.firstOrNull()
            } ?: 0

            val cacheSummary = shardsCacheRepository.getCacheSummary()
            val lastAstreaTime = AstreaCacheBridge.getLastAstreaRequestTimeMs()
            val lastAstreaDesc = if (lastAstreaTime > 0) {
                "${(System.currentTimeMillis() - lastAstreaTime) / 1000}s ago (${AstreaCacheBridge.getLastAstreaRequestUrl()})"
            } else {
                "none in this session"
            }

            val dur = SystemClock.elapsedRealtime() - t7Start
            val msg = String.format(
                Locale.US,
                "Scheduled JobIds=%s | Pending Superpacks downloads=%d | PAM LevelDB shards=%d (%d tracks) | " +
                    "AMM Cache: %d completed (%.1f MB), %d partial (%.1f MB) | Astrea RPCs=%d (hits=%d, misses=%d, last=%s)",
                jobIds.toString(),
                pendingSuperpacksDownloads,
                levelDbShards.size,
                trackCount,
                cacheSummary.completedFileCount,
                cacheSummary.totalCompletedBytes / (1024.0 * 1024.0),
                cacheSummary.partialFileCount,
                cacheSummary.totalPartialBytes / (1024.0 * 1024.0),
                AstreaCacheBridge.getTotalRequestsHandled(),
                AstreaCacheBridge.getCacheHitsCount(),
                AstreaCacheBridge.getCacheMissesCount(),
                lastAstreaDesc
            )
            downloadLogRepository.logInfo(TAG, "Diagnostics #7: $msg")
            val status = if (pendingSuperpacksDownloads == 0 && levelDbShards.size > 1) {
                DownloadDiagnosticsRepository.CheckStatus.PASSED
            } else {
                DownloadDiagnosticsRepository.CheckStatus.WARNING
            }
            updateCheck(6, status, msg, dur)
        } catch (e: Exception) {
            val dur = SystemClock.elapsedRealtime() - t7Start
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            downloadLogRepository.logError(TAG, "Diagnostics #7 exception", e)
            updateCheck(6, DownloadDiagnosticsRepository.CheckStatus.FAILED, msg, dur)
        }

        downloadLogRepository.logInfo(TAG, "=== Download Diagnostics Suite Finished ===")
    }.flowOn(Dispatchers.IO)

    private fun testDnsAndHttps(
        host: String,
        testUrl: String
    ): Triple<DownloadDiagnosticsRepository.CheckStatus, String, Long> {
        val start = SystemClock.elapsedRealtime()
        return try {
            val addresses = InetAddress.getAllByName(host)
            val ipList = addresses.joinToString(", ") { it.hostAddress ?: "?" }
            val dnsTime = SystemClock.elapsedRealtime() - start

            val req = Request.Builder().url(testUrl).head().build()
            var code: Int
            var message: String
            client.newCall(req).execute().use { resp ->
                code = resp.code
                message = resp.message
            }
            // Some endpoints (e.g., GitHub API) may reject HEAD with 403/405; fallback to ranged GET
            if (code == 405 || code == 403 || code == 404) {
                val getReq = Request.Builder()
                    .url(testUrl)
                    .header("Range", "bytes=0-255")
                    .get()
                    .build()
                client.newCall(getReq).execute().use { resp ->
                    code = resp.code
                    message = resp.message
                }
            }
            val totalTime = SystemClock.elapsedRealtime() - start
            if (code in 200..299 || (host == "api.github.com" && code == 404)) {
                val note = if (host == "api.github.com" && code == 404) {
                    " (HTTP 404: repo/releases not published yet on ${BuildConfig.GITHUB_OWNER}, but DNS+TLS succeeded)"
                } else ""
                val detail = "DNS OK (${dnsTime}ms -> $ipList) | HTTPS HTTP $code $message (${totalTime}ms)$note"
                downloadLogRepository.logInfo(TAG, "Host check [$host] passed: $detail")
                val status = if (code == 404) DownloadDiagnosticsRepository.CheckStatus.WARNING
                else DownloadDiagnosticsRepository.CheckStatus.PASSED
                Triple(status, detail, totalTime)
            } else {
                val detail = "DNS OK ($ipList), but HTTPS returned HTTP $code $message for $testUrl (${totalTime}ms)"
                downloadLogRepository.logWarn(TAG, "Host check [$host] HTTP error: $detail")
                Triple(DownloadDiagnosticsRepository.CheckStatus.FAILED, detail, totalTime)
            }
        } catch (e: Exception) {
            val totalTime = SystemClock.elapsedRealtime() - start
            val detail = "FAILED (${totalTime}ms): ${e.javaClass.simpleName}: ${e.message}"
            downloadLogRepository.logError(TAG, "Host check [$host] failed", e)
            Triple(DownloadDiagnosticsRepository.CheckStatus.FAILED, detail, totalTime)
        }
    }
}
