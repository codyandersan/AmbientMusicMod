package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import com.google.gson.Gson
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.model.github.GitHubRelease
import com.kieronquinn.app.ambientmusicmod.model.update.CachedGitHubRelease
import com.kieronquinn.app.ambientmusicmod.providers.GitHubProvider
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository.UpdateState
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getPackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

interface UpdatesRepository {

    val containerCheckUpdatesBus: Flow<Long>

    fun getUpdatesFolder(context: Context): File
    fun clearUpdatesFolder(folder: File)
    suspend fun containerCheckForUpdates()
    suspend fun isAnyUpdateAvailable(): Boolean
    fun doesPAMSupportSummaryAndEditing(): Boolean

    suspend fun getPAMUpdateState(
        ignoreLocal: Boolean = false,
        ignoreCache: Boolean = false
    ): UpdateState

    suspend fun getAMMUpdateState(
        ignoreCache: Boolean = false
    ): UpdateState

    sealed class UpdateState {
        data class NotInstalled(
            val remoteVersion: String,
            val release: GitHubRelease
        ): UpdateState()
        data class FailedToFetchUpdate(
            val errorMessage: String = "Failed to connect to GitHub releases"
        ): UpdateState()
        data class FailedToFetchInitial(
            val errorMessage: String = "Failed to fetch release from GitHub"
        ): UpdateState()
        data class UpToDate(
            val localVersion: String,
            val localVersionCode: Long
        ): UpdateState()
        data class UpdateAvailable(
            val localVersion: String,
            val localVersionCode: Long,
            val remoteVersion: String,
            val release: GitHubRelease
        ): UpdateState()
    }

}

class UpdatesRepositoryImpl(
    private val gson: Gson,
    context: Context,
    private val downloadLogRepository: DownloadLogRepository? = null
): UpdatesRepository {

    companion object {
        private const val TAG = "UpdatesRepo"
        private val CACHE_TIMEOUT = Duration.ofHours(12).toMillis()
        private const val MIN_SUMMARY_AND_EDIT_CODE = 120L
    }

    private val packageManager = context.packageManager
    private val updatesCacheDir = File(context.cacheDir, "updates")
    private val pamProvider = GitHubProvider.getGitHubProvider("NowPlaying")
    private val ammProvider = GitHubProvider.getGitHubProvider("AmbientMusicMod")

    override val containerCheckUpdatesBus = MutableStateFlow(System.currentTimeMillis())

    override fun getUpdatesFolder(context: Context): File {
        return File(context.externalCacheDir, "updates").apply {
            mkdirs()
        }
    }

    override fun clearUpdatesFolder(folder: File) {
        if(!folder.exists()) return //Already deleted
        folder.listFiles()?.forEach { it.delete() }
    }

    override suspend fun containerCheckForUpdates() {
        containerCheckUpdatesBus.emit(System.currentTimeMillis())
    }

    override suspend fun getPAMUpdateState(
        ignoreLocal: Boolean,
        ignoreCache: Boolean
    ): UpdateState = withContext(
        Dispatchers.IO
    ) {
        val localAsiInfo = try {
            packageManager.getPackageInfoCompat(PACKAGE_NAME_PAM)
        }catch (e: Exception){
            null
        }
        getRelease(
            localAsiInfo?.versionName,
            localAsiInfo?.longVersionCode,
            pamProvider,
            "pam",
            "NowPlaying",
            ignoreCache
        )
    }

    override suspend fun getAMMUpdateState(ignoreCache: Boolean): UpdateState = withContext(
        Dispatchers.IO
    ) {
        getRelease(
            BuildConfig.TAG_NAME,
            BuildConfig.VERSION_CODE.toLong(),
            ammProvider,
            "amm",
            "AmbientMusicMod",
            ignoreCache
        )
    }

    override suspend fun isAnyUpdateAvailable(): Boolean {
        val pam = getPAMUpdateState()
        val amm = getAMMUpdateState()
        return when {
            pam is UpdateState.UpdateAvailable -> true
            amm is UpdateState.UpdateAvailable -> true
            pam is UpdateState.NotInstalled -> true
            amm is UpdateState.NotInstalled -> true
            else -> false
        }
    }

    override fun doesPAMSupportSummaryAndEditing(): Boolean {
        return try {
            packageManager.getPackageInfoCompat(PACKAGE_NAME_PAM)
                .longVersionCode >= MIN_SUMMARY_AND_EDIT_CODE
        }catch (e: NameNotFoundException){
            false
        }
    }

    private fun getRelease(
        localVersion: String?,
        localVersionCode: Long?,
        provider: GitHubProvider,
        repositoryKey: String,
        repoName: String,
        ignoreCache: Boolean = false
    ): UpdateState {
        val cachedRelease = if(ignoreCache) null else getUpdateCache(repositoryKey)
        var errorReason: String? = null
        val remoteRelease = cachedRelease ?: run {
            val res = provider.fetchCurrentReleaseResult(repoName)
            res.getOrNull()?.also {
                it.cacheRelease(repositoryKey)
            } ?: run {
                errorReason = res.exceptionOrNull()?.message ?: "No releases found in ${BuildConfig.GITHUB_OWNER}/$repoName"
                null
            }
        }
        val remoteVersion = remoteRelease?.tag
        return when {
            localVersion != null && localVersionCode != null && remoteRelease != null &&
                    remoteVersion != null && !versionsMatch(localVersion, remoteVersion) -> {
                UpdateState.UpdateAvailable(
                    localVersion,
                    localVersionCode,
                    remoteVersion,
                    remoteRelease
                )
            }
            localVersion == null && remoteRelease != null && remoteVersion != null -> {
                UpdateState.NotInstalled(remoteVersion, remoteRelease)
            }
            localVersion != null && localVersionCode != null && remoteVersion != null -> {
                UpdateState.UpToDate(localVersion, localVersionCode)
            }
            remoteVersion == null && localVersion != null -> {
                UpdateState.FailedToFetchUpdate(
                    errorReason ?: "Installed v$localVersion (no remote release found on ${BuildConfig.GITHUB_OWNER}/$repoName)"
                )
            }
            else -> UpdateState.FailedToFetchInitial(
                errorReason ?: "Could not fetch ${BuildConfig.GITHUB_OWNER}/$repoName releases"
            )
        }
    }

    /**
     * Normalizes tags so both "1.3.5" and "v1.3.5" compare equal to versionName "1.3.5".
     */
    private fun versionsMatch(localVersion: String, remoteTag: String): Boolean {
        val cleanLocal = localVersion.trim().removePrefix("v").removePrefix("V")
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
        return cleanLocal == cleanRemote
    }

    private fun GitHubProvider.fetchCurrentReleaseResult(repoName: String): Result<GitHubRelease> {
        return try {
            val response = getReleases().execute()
            if (!response.isSuccessful) {
                val msg = "HTTP ${response.code()} ${response.message()} from ${BuildConfig.GITHUB_OWNER}/$repoName/releases"
                downloadLogRepository?.logWarn(TAG, msg)
                return Result.failure(IOException(msg))
            }
            val releases = response.body()
            val first = releases?.firstOrNull()
            if (first == null) {
                val msg = "No releases published in ${BuildConfig.GITHUB_OWNER}/$repoName"
                downloadLogRepository?.logWarn(TAG, msg)
                Result.failure(IOException(msg))
            } else {
                Result.success(first)
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            downloadLogRepository?.logError(TAG, "Failed fetching releases for $repoName: $msg", e)
            Result.failure(IOException(msg, e))
        }
    }

    private fun getUpdateCache(repository: String): GitHubRelease? {
        updatesCacheDir.mkdirs()
        val file = File(updatesCacheDir, "${repository}_${BuildConfig.VERSION_CODE}")
        if(!file.exists()) return null
        val cachedRelease = try {
            gson.fromJson(file.readText(), CachedGitHubRelease::class.java)
        }catch (e: Exception){
            null
        } ?: return null
        val cacheAge = Duration.between(
            Instant.ofEpochMilli(cachedRelease.timestamp), Instant.now()
        ).toMillis()
        if(cacheAge > CACHE_TIMEOUT) return null
        return cachedRelease.release
    }

    private fun GitHubRelease.cacheRelease(repository: String) {
        updatesCacheDir.mkdirs()
        val file = File(updatesCacheDir, "${repository}_${BuildConfig.VERSION_CODE}")
        val cachedRelease = gson.toJson(CachedGitHubRelease(System.currentTimeMillis(), this))
        file.writeText(cachedRelease)
    }

}
