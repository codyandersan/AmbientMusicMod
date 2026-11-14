package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.google.android.as.oss.http.service.AstreaCacheBridge
import com.google.gson.Gson
import com.kieronquinn.app.ambientmusicmod.model.shards.SuperpacksManifest
import com.kieronquinn.app.ambientmusicmod.service.ShardsDownloadForegroundService
import com.kieronquinn.app.ambientmusicmod.utils.manifest.ManifestUrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException

interface ShardsCacheRepository {

    val downloadState: StateFlow<DatabaseDownloadState>

    fun getCacheRootDir(): File
    fun getCompletedFileForUrl(url: String): File?
    fun getCompletedFileForPack(pack: SuperpacksManifest.Pack): File?
    fun getCachedETagForUrl(url: String, file: File): String
    suspend fun fetchAndParseActiveManifest(): Result<Pair<String, SuperpacksManifest>>
    suspend fun getRequiredPacksForSelectedCountries(): Result<RequiredPacksPlan>
    suspend fun isAllRequiredPacksCached(): Boolean
    suspend fun getCacheSummary(): CacheSummary

    fun startForegroundDownload(force: Boolean = false)
    suspend fun executeResumableDownload(
        force: Boolean = false,
        onNotificationUpdate: (DatabaseDownloadState) -> Unit = {}
    )
    fun cancelActiveDownload()
    suspend fun clearCache(): Boolean

    fun importFromUri(uri: Uri, isTreeUri: Boolean): Flow<ImportExportState>
    fun exportCacheToZip(targetUri: Uri): Flow<ImportExportState>

    data class RequiredPacksPlan(
        val manifestUrl: String,
        val versionCode: Int,
        val selectedCountryCodes: List<String>,
        val manifest: SuperpacksManifest,
        val requiredPacks: List<SuperpacksManifest.Pack>,
        val alreadyCachedPacks: List<SuperpacksManifest.Pack>,
        val missingPacks: List<SuperpacksManifest.Pack>,
        val totalBytes: Long,
        val alreadyCachedBytes: Long
    )

    data class CacheSummary(
        val completedFileCount: Int,
        val partialFileCount: Int,
        val totalCompletedBytes: Long,
        val totalPartialBytes: Long
    )

    sealed class DatabaseDownloadState {
        object Idle : DatabaseDownloadState()
        data class Preparing(val manifestUrl: String) : DatabaseDownloadState()
        data class Downloading(
            val manifestUrl: String,
            val packsDone: Int,
            val packsTotal: Int,
            val bytesDone: Long,
            val bytesTotal: Long,
            val speedBytesPerSec: Long,
            val etaSeconds: Long,
            val currentPackName: String,
            val isStuck: Boolean = false,
            val stuckReason: String? = null
        ) : DatabaseDownloadState()
        data class Completed(
            val manifestUrl: String,
            val packsTotal: Int,
            val bytesTotal: Long,
            val durationMs: Long
        ) : DatabaseDownloadState()
        data class PausedOrBlocked(
            val reason: String,
            val packsDone: Int,
            val packsTotal: Int,
            val bytesDone: Long,
            val bytesTotal: Long
        ) : DatabaseDownloadState()
        data class Failed(
            val errorSummary: String,
            val detailedReason: String,
            val packsDone: Int,
            val packsTotal: Int,
            val bytesDone: Long,
            val bytesTotal: Long
        ) : DatabaseDownloadState()
    }

    sealed class ImportExportState {
        data class Progress(
            val currentFile: String,
            val processedCount: Int,
            val totalCount: Int,
            val message: String
        ) : ImportExportState()
        data class Completed(
            val successCount: Int,
            val totalRequired: Int,
            val bytesProcessed: Long,
            val warnings: List<String>,
            val summaryMessage: String
        ) : ImportExportState()
        data class Failed(
            val errorMessage: String,
            val details: List<String> = emptyList()
        ) : ImportExportState()
    }
}

class ShardsCacheRepositoryImpl(
    private val context: Context,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val jobsRepository: JobsRepository,
    private val shizukuServiceRepository: ShizukuServiceRepository,
    private val downloadLogRepository: DownloadLogRepository,
    private val gson: Gson
) : ShardsCacheRepository, AstreaCacheBridge.Handler {

    companion object {
        private const val TAG = "ShardsCache"
        private const val CACHE_DIR_NAME = "astrea_pack_cache"
        private const val COMPLETED_SUBDIR = "completed"
        private const val PARTIAL_SUBDIR = "partial"
        private const val META_SUBDIR = "meta"

        private const val MAX_PARALLEL_DOWNLOADS = 3
        private const val MAX_RETRIES_PER_PACK = 4
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val STUCK_THRESHOLD_MS = 90_000L // 90 seconds without a single byte
        private const val BUFFER_SIZE = 32 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cacheRoot = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
    private val completedDir = File(cacheRoot, COMPLETED_SUBDIR).apply { mkdirs() }
    private val partialDir = File(cacheRoot, PARTIAL_SUBDIR).apply { mkdirs() }
    private val metaDir = File(cacheRoot, META_SUBDIR).apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _downloadState = MutableStateFlow<ShardsCacheRepository.DatabaseDownloadState>(
        ShardsCacheRepository.DatabaseDownloadState.Idle
    )
    override val downloadState: StateFlow<ShardsCacheRepository.DatabaseDownloadState> =
        _downloadState.asStateFlow()

    private val isDownloading = AtomicBoolean(false)
    private var activeDownloadJob: Job? = null
    private val knownPackSizesByUrl = ConcurrentHashMap<String, Long>()
    private val knownPackNamesByUrl = ConcurrentHashMap<String, String>()

    init {
        AstreaCacheBridge.setHandler(this)
    }

    override fun getCacheRootDir(): File = cacheRoot

    /**
     * Resolves a URL's canonical filename (last path segment of the URL, e.g., `tree_core_shard`
     * or shard name `in_...`). Also checks alias `matcher_tah.leveldb` for core shard.
     */
    fun getCanonicalFileNameForUrl(url: String): String {
        val clean = url.substringBefore('?').trimEnd('/')
        val segment = clean.substringAfterLast('/')
        return segment.ifBlank { "pack_${url.hashCode()}" }
    }

    override fun getCompletedFileForUrl(url: String): File? {
        if (url.endsWith("manifest.json")) {
            // Do not intercept manifest.json from pack cache so Superpacks always gets fresh or direct manifest
            return null
        }
        val fileName = getCanonicalFileNameForUrl(url)
        val primaryCandidate = File(completedDir, fileName)
        val expectedSize = knownPackSizesByUrl[url] ?: readExpectedSizeMeta(fileName)

        if (primaryCandidate.exists() && primaryCandidate.isFile()) {
            if (expectedSize == null || primaryCandidate.length() == expectedSize) {
                if (primaryCandidate.length() > 0) return primaryCandidate
            }
        }
        // Check core shard alias (tree_core_shard <-> matcher_tah.leveldb)
        if (fileName == "tree_core_shard") {
            val alias = File(completedDir, "matcher_tah.leveldb")
            if (alias.exists() && alias.isFile() && (expectedSize == null || alias.length() == expectedSize) && alias.length() > 0) {
                return alias
            }
        }
        return null
    }

    override fun getCompletedFileForPack(pack: SuperpacksManifest.Pack): File? {
        for (candidateName in pack.candidateFileNames()) {
            val f = File(completedDir, candidateName)
            if (f.exists() && f.isFile() && f.length() == pack.size) {
                return f
            }
        }
        return null
    }

    override fun getCachedETagForUrl(url: String, file: File): String {
        val fileName = getCanonicalFileNameForUrl(url)
        val etagFile = File(metaDir, "$fileName.etag")
        if (etagFile.exists()) {
            val stored = etagFile.readText().trim()
            if (stored.isNotEmpty()) return stored
        }
        return "\"${file.length()}-${file.lastModified()}\""
    }

    override fun getCompletedCacheFile(url: String): File? {
        return getCompletedFileForUrl(url)
    }

    override fun getCachedETag(url: String, file: File): String? {
        return getCachedETagForUrl(url, file)
    }

    override fun onCacheMiss(url: String, requestHeaders: MutableMap<String, MutableList<String>>): Boolean {
        // Only intercept Superpacks shard/core downloads from music-iq-db, NOT manifest.json or other URLs
        if (!url.contains("music-iq-db") || url.endsWith("manifest.json")) {
            return false
        }
        downloadLogRepository.logInfo(
            TAG,
            "Astrea cache miss for pack URL: $url (headers=$requestHeaders). Triggering AMM background downloader."
        )
        startForegroundDownload(force = false)
        return true
    }

    override fun onAstreaRequestEvent(
        url: String,
        requestHeaders: MutableMap<String, MutableList<String>>,
        responseCode: Int,
        bytesTransferred: Long,
        durationMs: Long,
        servedFromCache: Boolean,
        note: String?,
        error: Throwable?
    ) {
        val mode = if (servedFromCache) "CACHE_HIT" else "NETWORK"
        val msg = "Astrea [$mode] url=$url code=$responseCode bytes=$bytesTransferred duration=${durationMs}ms note=${note ?: "-"} headers=$requestHeaders"
        if (error != null) {
            downloadLogRepository.logError(TAG, msg, error)
        } else {
            downloadLogRepository.logInfo(TAG, msg)
        }
    }

    override suspend fun fetchAndParseActiveManifest(): Result<Pair<String, SuperpacksManifest>> =
        withContext(Dispatchers.IO) {
            val rawIndexValue = deviceConfigRepository.indexManifest.get()
            val manifestHttpsUrl = ManifestUrlParser.extractHttpsUrl(rawIndexValue)
            if (!manifestHttpsUrl.startsWith("https://")) {
                val err = IllegalArgumentException("Invalid manifest URL in config: $rawIndexValue")
                downloadLogRepository.logError(TAG, "Manifest URL validation failed", err)
                return@withContext Result.failure(err)
            }

            downloadLogRepository.logInfo(TAG, "Fetching manifest from $manifestHttpsUrl")
            try {
                val req = Request.Builder()
                    .url(manifestHttpsUrl)
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = IOException("HTTP ${response.code} ${response.message} fetching manifest $manifestHttpsUrl")
                        downloadLogRepository.logError(TAG, "Manifest fetch failed", err)
                        return@withContext Result.failure(err)
                    }
                    val bodyStr = response.body?.string()
                        ?: return@withContext Result.failure(IOException("Empty manifest body from $manifestHttpsUrl"))
                    val parsed = SuperpacksManifest.parse(bodyStr, gson)
                    // Cache pack sizes in memory and on disk for Astrea lookup verification
                    parsed.packs.forEach { pack ->
                        pack.primaryDownloadUrl?.let { u ->
                            knownPackSizesByUrl[u] = pack.size
                            knownPackNamesByUrl[u] = pack.name
                            val canonicalName = getCanonicalFileNameForUrl(u)
                            writeExpectedSizeMeta(canonicalName, pack.size)
                        }
                    }
                    downloadLogRepository.logInfo(
                        TAG,
                        "Parsed manifest ($manifestHttpsUrl): ${parsed.packs.size} total packs"
                    )
                    Result.success(Pair(manifestHttpsUrl, parsed))
                }
            } catch (e: Exception) {
                downloadLogRepository.logError(TAG, "Exception fetching/parsing manifest $manifestHttpsUrl", e)
                Result.failure(e)
            }
        }

    override suspend fun getRequiredPacksForSelectedCountries(): Result<ShardsCacheRepository.RequiredPacksPlan> =
        withContext(Dispatchers.IO) {
            val manifestResult = fetchAndParseActiveManifest()
            if (manifestResult.isFailure) {
                return@withContext Result.failure(manifestResult.exceptionOrNull()!!)
            }
            val (manifestUrl, manifest) = manifestResult.getOrThrow()
            val rawIndex = deviceConfigRepository.indexManifest.get()
            val versionCode = ManifestUrlParser.parse(rawIndex)?.versionCode ?: 3099

            val primaryCountry = deviceConfigRepository.getPrimaryLanguage()
            val extraCountries = deviceConfigRepository.getExtraLanguages()
            val selectedCodes = (listOf(primaryCountry) + extraCountries)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()

            val requiredPacks = manifest.selectPacksForCountries(selectedCodes)
            val cachedPacks = ArrayList<SuperpacksManifest.Pack>()
            val missingPacks = ArrayList<SuperpacksManifest.Pack>()

            for (pack in requiredPacks) {
                if (getCompletedFileForPack(pack) != null) {
                    cachedPacks.add(pack)
                } else {
                    missingPacks.add(pack)
                }
            }

            val totalBytes = requiredPacks.sumOf { it.size }
            val cachedBytes = cachedPacks.sumOf { it.size }

            Result.success(
                ShardsCacheRepository.RequiredPacksPlan(
                    manifestUrl = manifestUrl,
                    versionCode = versionCode,
                    selectedCountryCodes = selectedCodes,
                    manifest = manifest,
                    requiredPacks = requiredPacks,
                    alreadyCachedPacks = cachedPacks,
                    missingPacks = missingPacks,
                    totalBytes = totalBytes,
                    alreadyCachedBytes = cachedBytes
                )
            )
        }

    override suspend fun isAllRequiredPacksCached(): Boolean {
        val plan = getRequiredPacksForSelectedCountries().getOrNull() ?: return false
        return plan.requiredPacks.isNotEmpty() && plan.missingPacks.isEmpty()
    }

    override suspend fun getCacheSummary(): ShardsCacheRepository.CacheSummary =
        withContext(Dispatchers.IO) {
            val completedFiles = completedDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val partialFiles = partialDir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") } ?: emptyList()
            ShardsCacheRepository.CacheSummary(
                completedFileCount = completedFiles.size,
                partialFileCount = partialFiles.size,
                totalCompletedBytes = completedFiles.sumOf { it.length() },
                totalPartialBytes = partialFiles.sumOf { it.length() }
            )
        }

    override fun startForegroundDownload(force: Boolean) {
        ShardsDownloadForegroundService.start(context, force)
    }

    override fun cancelActiveDownload() {
        activeDownloadJob?.cancel()
        isDownloading.set(false)
        _downloadState.value = ShardsCacheRepository.DatabaseDownloadState.Idle
        downloadLogRepository.logInfo(TAG, "Active download cancelled by user")
    }

    override suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        cancelActiveDownload()
        var ok = true
        completedDir.listFiles()?.forEach { if (!it.delete()) ok = false }
        partialDir.listFiles()?.forEach { if (!it.delete()) ok = false }
        metaDir.listFiles()?.forEach { if (!it.delete()) ok = false }
        _downloadState.value = ShardsCacheRepository.DatabaseDownloadState.Idle
        downloadLogRepository.logInfo(TAG, "Cleared local Astrea shard cache (success=$ok)")
        ok
    }

    override suspend fun executeResumableDownload(
        force: Boolean,
        onNotificationUpdate: (ShardsCacheRepository.DatabaseDownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isDownloading.compareAndSet(false, true)) {
            downloadLogRepository.logInfo(TAG, "Download already in progress; ignoring duplicate start")
            return@withContext
        }

        val startTimeMs = SystemClock.elapsedRealtime()
        try {
            val rawConfigUrl = deviceConfigRepository.indexManifest.get()
            val manifestHttpsUrl = ManifestUrlParser.extractHttpsUrl(rawConfigUrl)
            val prepState = ShardsCacheRepository.DatabaseDownloadState.Preparing(manifestHttpsUrl)
            _downloadState.value = prepState
            onNotificationUpdate(prepState)

            // Check network and charging constraints unless force == true
            if (!force) {
                val blockReason = checkEnvironmentConstraints()
                if (blockReason != null) {
                    downloadLogRepository.logWarn(TAG, "Download blocked by environment constraint: $blockReason")
                    val blockedState = ShardsCacheRepository.DatabaseDownloadState.PausedOrBlocked(
                        reason = blockReason,
                        packsDone = 0,
                        packsTotal = 0,
                        bytesDone = 0L,
                        bytesTotal = 0L
                    )
                    _downloadState.value = blockedState
                    onNotificationUpdate(blockedState)
                    return@withContext
                }
            }

            val planResult = getRequiredPacksForSelectedCountries()
            if (planResult.isFailure) {
                val err = planResult.exceptionOrNull()!!
                val reason = classifyExceptionReason(err)
                val failedState = ShardsCacheRepository.DatabaseDownloadState.Failed(
                    errorSummary = reason,
                    detailedReason = "${err.javaClass.simpleName}: ${err.message}",
                    packsDone = 0,
                    packsTotal = 0,
                    bytesDone = 0L,
                    bytesTotal = 0L
                )
                _downloadState.value = failedState
                onNotificationUpdate(failedState)
                return@withContext
            }

            val plan = planResult.getOrThrow()
            val totalPacks = plan.requiredPacks.size
            val totalBytes = plan.totalBytes

            downloadLogRepository.logInfo(
                TAG,
                "Download plan for countries ${plan.selectedCountryCodes}: " +
                    "${plan.requiredPacks.size} required packs (${formatBytes(totalBytes)}), " +
                    "${plan.alreadyCachedPacks.size} already cached, ${plan.missingPacks.size} to download."
            )

            if (plan.missingPacks.isEmpty()) {
                val doneState = ShardsCacheRepository.DatabaseDownloadState.Completed(
                    manifestUrl = plan.manifestUrl,
                    packsTotal = totalPacks,
                    bytesTotal = totalBytes,
                    durationMs = SystemClock.elapsedRealtime() - startTimeMs
                )
                _downloadState.value = doneState
                onNotificationUpdate(doneState)
                triggerPamSuperpacksSync()
                return@withContext
            }

            val packsDoneCounter = AtomicInteger(plan.alreadyCachedPacks.size)
            val bytesDoneCounter = AtomicLong(plan.alreadyCachedBytes)
            // Include already-downloaded .part bytes for missing packs
            for (pack in plan.missingPacks) {
                val partFile = getPartFileForPack(pack)
                if (partFile.exists() && partFile.length() < pack.size) {
                    bytesDoneCounter.addAndGet(partFile.length())
                }
            }

            val lastByteTimestamp = AtomicLong(SystemClock.elapsedRealtime())
            val currentPackLabel = AtomicStateLabel(plan.missingPacks.first().name)
            val firstFailure = AtomicReferenceCompat<Throwable?>(null)

            val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
            val speedSampleStartBytes = bytesDoneCounter.get()
            val speedSampleStartTime = SystemClock.elapsedRealtime()

            coroutineScope {
                val downloadScope = this
                activeDownloadJob = coroutineContext[Job]

                // Watchdog coroutine for real-time progress + stuck detection
                val progressWatchdog = launch {
                    while (isActive) {
                        val now = SystemClock.elapsedRealtime()
                        val idleMs = now - lastByteTimestamp.get()
                        val elapsedSec = ((now - speedSampleStartTime) / 1000.0).coerceAtLeast(0.5)
                        val downloadedInSession = (bytesDoneCounter.get() - speedSampleStartBytes).coerceAtLeast(0L)
                        val speedBps = (downloadedInSession / elapsedSec).toLong()
                        val remainingBytes = (totalBytes - bytesDoneCounter.get()).coerceAtLeast(0L)
                        val etaSec = if (speedBps > 0) remainingBytes / speedBps else -1L

                        val isStuck = idleMs >= STUCK_THRESHOLD_MS
                        val stuckReason = if (isStuck) {
                            diagnoseStuckReason(idleMs)
                        } else null

                        if (isStuck) {
                            downloadLogRepository.logWarn(
                                TAG,
                                "Download stuck detected: no bytes for ${idleMs / 1000}s ($stuckReason)"
                            )
                        }

                        val state = ShardsCacheRepository.DatabaseDownloadState.Downloading(
                            manifestUrl = plan.manifestUrl,
                            packsDone = packsDoneCounter.get(),
                            packsTotal = totalPacks,
                            bytesDone = bytesDoneCounter.get().coerceAtMost(totalBytes),
                            bytesTotal = totalBytes,
                            speedBytesPerSec = speedBps,
                            etaSeconds = etaSec,
                            currentPackName = currentPackLabel.get(),
                            isStuck = isStuck,
                            stuckReason = stuckReason
                        )
                        _downloadState.value = state
                        onNotificationUpdate(state)
                        delay(500L)
                    }
                }

                val deferreds = plan.missingPacks.map { pack ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (firstFailure.get() != null || !isActive) return@withPermit
                            currentPackLabel.set(pack.name)
                            val result = downloadSinglePackWithResume(
                                pack = pack,
                                onBytesRead = { delta ->
                                    bytesDoneCounter.addAndGet(delta)
                                    lastByteTimestamp.set(SystemClock.elapsedRealtime())
                                }
                            )
                            if (result.isSuccess) {
                                val doneCount = packsDoneCounter.incrementAndGet()
                                downloadLogRepository.logInfo(
                                    TAG,
                                    "Pack completed ($doneCount/$totalPacks): ${pack.name} (${pack.size} bytes)"
                                )
                            } else {
                                val ex = result.exceptionOrNull() ?: IOException("Unknown download failure")
                                firstFailure.compareAndSet(null, ex)
                                downloadLogRepository.logError(
                                    TAG,
                                    "Pack failed permanently after retries: ${pack.name}",
                                    ex
                                )
                            }
                        }
                    }
                }

                deferreds.awaitAll()
                progressWatchdog.cancel()
            }

            val failure = firstFailure.get()
            if (failure != null) {
                val summary = classifyExceptionReason(failure)
                val failedState = ShardsCacheRepository.DatabaseDownloadState.Failed(
                    errorSummary = summary,
                    detailedReason = "${failure.javaClass.simpleName}: ${failure.message}",
                    packsDone = packsDoneCounter.get(),
                    packsTotal = totalPacks,
                    bytesDone = bytesDoneCounter.get(),
                    bytesTotal = totalBytes
                )
                _downloadState.value = failedState
                onNotificationUpdate(failedState)
                return@withContext
            }

            val durationMs = SystemClock.elapsedRealtime() - startTimeMs
            val completedState = ShardsCacheRepository.DatabaseDownloadState.Completed(
                manifestUrl = plan.manifestUrl,
                packsTotal = totalPacks,
                bytesTotal = totalBytes,
                durationMs = durationMs
            )
            _downloadState.value = completedState
            onNotificationUpdate(completedState)
            downloadLogRepository.logInfo(
                TAG,
                "All $totalPacks packs (${formatBytes(totalBytes)}) downloaded & verified in ${durationMs / 1000}s! Expediting PAM Superpacks job."
            )
            triggerPamSuperpacksSync()
        } catch (e: CancellationException) {
            downloadLogRepository.logInfo(TAG, "Download job cancelled")
            throw e
        } catch (e: Exception) {
            downloadLogRepository.logError(TAG, "Unexpected exception in executeResumableDownload", e)
            val failedState = ShardsCacheRepository.DatabaseDownloadState.Failed(
                errorSummary = classifyExceptionReason(e),
                detailedReason = "${e.javaClass.simpleName}: ${e.message}",
                packsDone = 0,
                packsTotal = 0,
                bytesDone = 0L,
                bytesTotal = 0L
            )
            _downloadState.value = failedState
            onNotificationUpdate(failedState)
        } finally {
            isDownloading.set(false)
        }
    }

    /**
     * Downloads a single pack using `.part` file, HTTP `Range` + `If-Range`/`ETag`,
     * exponential backoff retries, exact byte-size verification, and atomic rename on completion.
     */
    suspend fun downloadSinglePackWithResume(
        pack: SuperpacksManifest.Pack,
        onBytesRead: (Long) -> Unit
    ): Result<File> {
        val url = pack.primaryDownloadUrl
            ?: return Result.failure(IllegalArgumentException("Pack ${pack.name} has no download URL"))
        val canonicalFileName = getCanonicalFileNameForUrl(url)
        val finalFile = File(completedDir, canonicalFileName)
        val partFile = File(partialDir, "$canonicalFileName.part")
        val etagMetaFile = File(metaDir, "$canonicalFileName.etag")
        writeExpectedSizeMeta(canonicalFileName, pack.size)

        // Already complete?
        if (finalFile.exists() && finalFile.length() == pack.size) {
            return Result.success(finalFile)
        }

        var attempt = 0
        var lastException: Exception? = null

        while (attempt < MAX_RETRIES_PER_PACK) {
            attempt++
            try {
                if (partFile.exists() && partFile.length() > pack.size) {
                    downloadLogRepository.logWarn(
                        TAG,
                        "Partial file ${partFile.name} (${partFile.length()}) > expected (${pack.size}); resetting."
                    )
                    partFile.delete()
                }
                if (partFile.exists() && partFile.length() == pack.size) {
                    if (atomicPromotePartFile(partFile, finalFile, pack)) {
                        return Result.success(finalFile)
                    }
                }

                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = Request.Builder().url(url)
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                    if (etagMetaFile.exists()) {
                        val savedEtag = etagMetaFile.readText().trim()
                        if (savedEtag.isNotEmpty()) {
                            reqBuilder.header("If-Range", savedEtag)
                        }
                    }
                }

                downloadLogRepository.logInfo(
                    TAG,
                    "Downloading pack '${pack.name}' (attempt $attempt/$MAX_RETRIES_PER_PACK, resumeOffset=$existingBytes/${pack.size}) from $url"
                )

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 416) {
                        // Range not satisfiable - reset .part file and retry
                        val resetOffset = partFile.length()
                        partFile.delete()
                        if (resetOffset > 0) onBytesRead(-resetOffset)
                        throw IOException("HTTP 416 Range Not Satisfiable for ${pack.name}; reset partial file")
                    }
                    if (code != 200 && code != 206) {
                        throw IOException("HTTP $code ${response.message} for ${pack.name}")
                    }

                    response.header("ETag")?.let { etag ->
                        if (etag.isNotBlank()) {
                            etagMetaFile.writeText(etag.trim())
                        }
                    }

                    val appendMode = (code == 206 && existingBytes > 0L)
                    if (!appendMode && existingBytes > 0L) {
                        // Server ignored Range and sent 200 OK full content; rewind progress counter
                        onBytesRead(-existingBytes)
                        partFile.delete()
                    }

                    val body = response.body
                        ?: throw IOException("Empty response body for ${pack.name}")

                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (appendMode) {
                            raf.seek(existingBytes)
                        } else {
                            raf.setLength(0L)
                            raf.seek(0L)
                        }
                        val stream = body.byteStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            if (read > 0) {
                                raf.write(buffer, 0, read)
                                onBytesRead(read.toLong())
                            }
                        }
                        raf.fd.sync()
                    }
                }

                // Exact-size verification
                val actualLength = partFile.length()
                if (actualLength != pack.size) {
                    throw IOException(
                        "Exact-size verification failed for ${pack.name}: expected ${pack.size} bytes, got $actualLength bytes"
                    )
                }

                if (!atomicPromotePartFile(partFile, finalFile, pack)) {
                    throw IOException("Failed to atomically rename ${partFile.name} to ${finalFile.name}")
                }

                return Result.success(finalFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                downloadLogRepository.logWarn(
                    TAG,
                    "Attempt $attempt/$MAX_RETRIES_PER_PACK failed for ${pack.name}: ${e.javaClass.simpleName}: ${e.message}"
                )
                if (attempt < MAX_RETRIES_PER_PACK) {
                    val backoff = INITIAL_BACKOFF_MS * (1L shl (attempt - 1))
                    delay(backoff)
                }
            }
        }

        return Result.failure(
            lastException ?: IOException("Failed to download ${pack.name} after $MAX_RETRIES_PER_PACK attempts")
        )
    }

    private fun atomicPromotePartFile(
        partFile: File,
        finalFile: File,
        pack: SuperpacksManifest.Pack
    ): Boolean {
        if (partFile.length() != pack.size) {
            partFile.delete()
            return false
        }
        if (finalFile.exists()) {
            finalFile.delete()
        }
        val renamed = partFile.renameTo(finalFile)
        if (!renamed) {
            try {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            } catch (e: Exception) {
                downloadLogRepository.logError(TAG, "Copy fallback failed for ${finalFile.name}", e)
                return false
            }
        }
        return finalFile.exists() && finalFile.length() == pack.size
    }

    private fun getPartFileForPack(pack: SuperpacksManifest.Pack): File {
        val url = pack.primaryDownloadUrl ?: pack.name
        val canonicalFileName = getCanonicalFileNameForUrl(url)
        return File(partialDir, "$canonicalFileName.part")
    }

    private fun writeExpectedSizeMeta(canonicalName: String, size: Long) {
        try {
            File(metaDir, "$canonicalName.size").writeText(size.toString())
        } catch (_: Exception) {
        }
    }

    private fun readExpectedSizeMeta(canonicalName: String): Long? {
        return try {
            val f = File(metaDir, "$canonicalName.size")
            if (f.exists()) f.readText().trim().toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun triggerPamSuperpacksSync() {
        try {
            deviceConfigRepository.sendValues()
            delay(300L)
            jobsRepository.forceExpediteJobs()
        } catch (e: Exception) {
            downloadLogRepository.logWarn(TAG, "Failed to expedite PAM jobs after cache completion", e)
        }
    }

    /**
     * Phase 3: Import database packs from a folder (DocumentTree) or ZIP file (OpenDocument) via SAF.
     * Matches files by name (pack.name or last URL path segment) against the active manifest,
     * verifies exact byte size, and copies into the Astrea cache.
     */
    override fun importFromUri(
        uri: Uri,
        isTreeUri: Boolean
    ): Flow<ShardsCacheRepository.ImportExportState> = flow {
        emit(
            ShardsCacheRepository.ImportExportState.Progress(
                currentFile = "manifest.json",
                processedCount = 0,
                totalCount = 1,
                message = "Fetching active manifest to verify pack names & exact byte sizes..."
            )
        )

        val planResult = getRequiredPacksForSelectedCountries()
        if (planResult.isFailure) {
            val err = planResult.exceptionOrNull()!!
            emit(
                ShardsCacheRepository.ImportExportState.Failed(
                    errorMessage = "Could not load manifest to validate imported files: ${err.message}",
                    details = listOf("${err.javaClass.simpleName}: ${err.message}")
                )
            )
            return@flow
        }

        val plan = planResult.getOrThrow()
        val allPacksByCandidateName = HashMap<String, SuperpacksManifest.Pack>()
        plan.manifest.packs.forEach { pack ->
            pack.candidateFileNames().forEach { candidate ->
                allPacksByCandidateName[candidate] = pack
            }
        }

        val warnings = ArrayList<String>()
        var importedCount = 0
        var importedBytes = 0L

        if (isTreeUri) {
            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree == null || !tree.exists()) {
                emit(
                    ShardsCacheRepository.ImportExportState.Failed(
                        errorMessage = "Could not open selected folder via Storage Access Framework."
                    )
                )
                return@flow
            }
            val files = tree.listFiles().filter { it.isFile }
            if (files.isEmpty()) {
                emit(
                    ShardsCacheRepository.ImportExportState.Failed(
                        errorMessage = "Selected folder contains no files."
                    )
                )
                return@flow
            }

            files.forEachIndexed { index, docFile ->
                val rawName = docFile.name ?: return@forEachIndexed
                emit(
                    ShardsCacheRepository.ImportExportState.Progress(
                        currentFile = rawName,
                        processedCount = index + 1,
                        totalCount = files.size,
                        message = "Inspecting $rawName (${index + 1}/${files.size})..."
                    )
                )

                val matchedPack = allPacksByCandidateName[rawName]
                if (matchedPack == null) {
                    warnings.add("Unrecognised file skipped: $rawName (not in manifest)")
                    return@forEachIndexed
                }

                val actualSize = docFile.length()
                if (actualSize != matchedPack.size) {
                    warnings.add(
                        "Wrong size for $rawName: expected ${matchedPack.size} bytes, got $actualSize bytes"
                    )
                    return@forEachIndexed
                }

                val canonicalName = getCanonicalFileNameForUrl(matchedPack.primaryDownloadUrl ?: matchedPack.name)
                val tempImport = File(partialDir, "$canonicalName.import.part")
                val destFile = File(completedDir, canonicalName)

                val copiedOk = try {
                    context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                        FileOutputStream(tempImport).use { output ->
                            input.copyTo(output, BUFFER_SIZE)
                            output.flush()
                        }
                    } != null
                } catch (e: Exception) {
                    warnings.add("Error reading $rawName: ${e.message}")
                    false
                }

                if (copiedOk && tempImport.length() == matchedPack.size) {
                    if (atomicPromotePartFile(tempImport, destFile, matchedPack)) {
                        writeExpectedSizeMeta(canonicalName, matchedPack.size)
                        importedCount++
                        importedBytes += matchedPack.size
                    } else {
                        warnings.add("Failed to move $rawName into cache")
                    }
                } else {
                    tempImport.delete()
                    warnings.add("Size mismatch after copying $rawName")
                }
            }
        } else {
            // Single file URI (either a .zip archive or a single pack file)
            val singleDoc = DocumentFile.fromSingleUri(context, uri)
            val docName = singleDoc?.name ?: uri.lastPathSegment ?: "selected_file"

            if (docName.endsWith(".zip", ignoreCase = true)) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open input stream for ZIP $docName")
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        var idx = 0
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                idx++
                                val entryFileName = entry.name.substringAfterLast('/')
                                emit(
                                    ShardsCacheRepository.ImportExportState.Progress(
                                        currentFile = entryFileName,
                                        processedCount = idx,
                                        totalCount = plan.requiredPacks.size,
                                        message = "Extracting $entryFileName from ZIP..."
                                    )
                                )
                                val matchedPack = allPacksByCandidateName[entryFileName]
                                if (matchedPack == null) {
                                    warnings.add("Unrecognised ZIP entry skipped: $entryFileName")
                                } else {
                                    val canonicalName = getCanonicalFileNameForUrl(
                                        matchedPack.primaryDownloadUrl ?: matchedPack.name
                                    )
                                    val tempImport = File(partialDir, "$canonicalName.import.part")
                                    val destFile = File(completedDir, canonicalName)
                                    FileOutputStream(tempImport).use { out ->
                                        val buf = ByteArray(BUFFER_SIZE)
                                        while (true) {
                                            val r = zis.read(buf)
                                            if (r == -1) break
                                            if (r > 0) out.write(buf, 0, r)
                                        }
                                        out.flush()
                                    }
                                    if (tempImport.length() == matchedPack.size) {
                                        if (atomicPromotePartFile(tempImport, destFile, matchedPack)) {
                                            writeExpectedSizeMeta(canonicalName, matchedPack.size)
                                            importedCount++
                                            importedBytes += matchedPack.size
                                        } else {
                                            warnings.add("Failed to store $entryFileName in cache")
                                        }
                                    } else {
                                        val badLen = tempImport.length()
                                        tempImport.delete()
                                        warnings.add(
                                            "Wrong size for ZIP entry $entryFileName: expected ${matchedPack.size} bytes, got $badLen bytes"
                                        )
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    downloadLogRepository.logError(TAG, "ZIP import failed", e)
                    emit(
                        ShardsCacheRepository.ImportExportState.Failed(
                            errorMessage = "Failed to import ZIP archive: ${e.message}",
                            details = warnings
                        )
                    )
                    return@flow
                }
            } else {
                // Single pack file
                val matchedPack = allPacksByCandidateName[docName]
                if (matchedPack == null) {
                    emit(
                        ShardsCacheRepository.ImportExportState.Failed(
                            errorMessage = "Unrecognised file '$docName'. File name must match the pack name or last URL segment in the manifest."
                        )
                    )
                    return@flow
                }
                val canonicalName = getCanonicalFileNameForUrl(matchedPack.primaryDownloadUrl ?: matchedPack.name)
                val tempImport = File(partialDir, "$canonicalName.import.part")
                val destFile = File(completedDir, canonicalName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempImport).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.flush()
                    }
                }
                if (tempImport.length() != matchedPack.size) {
                    val actual = tempImport.length()
                    tempImport.delete()
                    emit(
                        ShardsCacheRepository.ImportExportState.Failed(
                            errorMessage = "Wrong size for '$docName': expected ${matchedPack.size} bytes, got $actual bytes."
                        )
                    )
                    return@flow
                }
                if (atomicPromotePartFile(tempImport, destFile, matchedPack)) {
                    writeExpectedSizeMeta(canonicalName, matchedPack.size)
                    importedCount++
                    importedBytes += matchedPack.size
                }
            }
        }

        // Check how many required packs are still missing after import
        val updatedPlan = getRequiredPacksForSelectedCountries().getOrNull()
        val missingNames = updatedPlan?.missingPacks?.map { it.name } ?: emptyList()
        if (missingNames.isNotEmpty()) {
            warnings.add(
                "Still missing ${missingNames.size}/${plan.requiredPacks.size} required packs for selected countries (${plan.selectedCountryCodes.joinToString()}): ${missingNames.take(5).joinToString()}" +
                    if (missingNames.size > 5) "..." else ""
            )
        }

        if (importedCount == 0) {
            emit(
                ShardsCacheRepository.ImportExportState.Failed(
                    errorMessage = "No valid packs were imported.",
                    details = warnings
                )
            )
        } else {
            val summary = "Imported $importedCount pack(s) (${formatBytes(importedBytes)}). " +
                if (missingNames.isEmpty()) "All required packs for ${plan.selectedCountryCodes.joinToString()} are now cached!"
                else "${missingNames.size} required pack(s) still missing."
            downloadLogRepository.logInfo(TAG, summary)
            triggerPamSuperpacksSync()
            emit(
                ShardsCacheRepository.ImportExportState.Completed(
                    successCount = importedCount,
                    totalRequired = plan.requiredPacks.size,
                    bytesProcessed = importedBytes,
                    warnings = warnings,
                    summaryMessage = summary
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Phase 3: Export all verified cached packs as a ZIP archive for reuse on another device or network.
     */
    override fun exportCacheToZip(
        targetUri: Uri
    ): Flow<ShardsCacheRepository.ImportExportState> = flow {
        val files = completedDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        if (files.isEmpty()) {
            emit(
                ShardsCacheRepository.ImportExportState.Failed(
                    errorMessage = "Astrea cache is empty. Download or import packs before exporting."
                )
            )
            return@flow
        }

        try {
            val outStream = context.contentResolver.openOutputStream(targetUri)
                ?: throw IOException("Could not open output stream for target ZIP URI")
            var totalExportedBytes = 0L

            ZipOutputStream(BufferedOutputStream(outStream)).use { zos ->
                files.forEachIndexed { idx, file ->
                    emit(
                        ShardsCacheRepository.ImportExportState.Progress(
                            currentFile = file.name,
                            processedCount = idx + 1,
                            totalCount = files.size,
                            message = "Exporting ${file.name} (${idx + 1}/${files.size})..."
                        )
                    )
                    val entry = ZipEntry(file.name)
                    entry.size = file.length()
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos, BUFFER_SIZE)
                    }
                    zos.closeEntry()
                    totalExportedBytes += file.length()
                }
                zos.flush()
            }

            val msg = "Exported ${files.size} cached pack(s) (${formatBytes(totalExportedBytes)}) to ZIP."
            downloadLogRepository.logInfo(TAG, msg)
            emit(
                ShardsCacheRepository.ImportExportState.Completed(
                    successCount = files.size,
                    totalRequired = files.size,
                    bytesProcessed = totalExportedBytes,
                    warnings = emptyList(),
                    summaryMessage = msg
                )
            )
        } catch (e: Exception) {
            downloadLogRepository.logError(TAG, "Cache ZIP export failed", e)
            emit(
                ShardsCacheRepository.ImportExportState.Failed(
                    errorMessage = "Export failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun checkEnvironmentConstraints(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork
        val caps = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isUnmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

        if (!hasInternet) {
            return "No active internet connection"
        }
        if (deviceConfigRepository.superpacksRequireWiFi.get() && !isUnmetered) {
            return "Metered network (Wi-Fi Only is enabled in settings)"
        }
        if (deviceConfigRepository.superpacksRequireCharging.get() && !isDeviceCharging()) {
            return "Device not charging (Require Charging is enabled in settings)"
        }
        return null
    }

    private fun isDeviceCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun diagnoseStuckReason(idleMs: Long): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isUnmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryRestricted = !pm.isIgnoringBatteryOptimizations(context.packageName)

        return when {
            !hasInternet -> "No bytes for ${idleMs / 1000}s: Network lost"
            !isUnmetered && deviceConfigRepository.superpacksRequireWiFi.getSync() ->
                "No bytes for ${idleMs / 1000}s: Metered network restriction"
            batteryRestricted ->
                "No bytes for ${idleMs / 1000}s: Battery optimisation is active for AMM"
            else ->
                "No bytes for ${idleMs / 1000}s: Connection stalled or server timeout"
        }
    }

    private fun classifyExceptionReason(t: Throwable): String {
        return when (t) {
            is UnknownHostException -> "DNS failure (${t.message})"
            is SSLException -> "TLS/HTTPS error (${t.message})"
            is java.net.SocketTimeoutException -> "Connection/read timeout (${t.message})"
            is IOException -> "HTTP/Network error: ${t.message}"
            else -> "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private class AtomicStateLabel(initial: String) {
        @Volatile
        private var value: String = initial
        fun get(): String = value
        fun set(v: String) {
            value = v
        }
    }

    private class AtomicReferenceCompat<T>(initial: T) {
        @Volatile
        private var value: T = initial
        fun get(): T = value
        @Synchronized
        fun compareAndSet(expect: T, update: T): Boolean {
            if (value == expect) {
                value = update
                return true
            }
            return false
        }
    }
}
