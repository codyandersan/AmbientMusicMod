package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.gson.Gson
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.model.shards.CachedShardManifest
import com.kieronquinn.app.ambientmusicmod.model.shards.ShardManifest
import com.kieronquinn.app.ambientmusicmod.providers.ShardsProvider
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.LocalShardsState
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.ManifestSource
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.RemoteShardsState
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.ShardCountry
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.ShardsState
import com.kieronquinn.app.ambientmusicmod.utils.extensions.contentReceiverAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.contentResolverAsTFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.isArmv7
import com.kieronquinn.app.ambientmusicmod.utils.extensions.map
import com.kieronquinn.app.ambientmusicmod.utils.extensions.safeQuery
import com.kieronquinn.app.ambientmusicmod.utils.manifest.ManifestUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import retrofit2.Call
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

interface ShardsRepository {

    suspend fun isUpdateAvailable(clearCache: Boolean = false): Boolean
    fun getCurrentDownloads(): Flow<Int>
    fun getShardsState(clearCache: Boolean = false): Flow<ShardsState>
    suspend fun setManualManifestOverride(urlOrEmpty: String): Result<String>

    enum class ShardDownloadState {
        DOWNLOADING, WAITING_FOR_NETWORK, WAITING_FOR_CHARGING
    }

    enum class ManifestSource(val label: String) {
        MANUAL_OVERRIDE("Manual Override"),
        REMOTE_POINTER("Remote Pointer (GitHub)"),
        NEWEST_KNOWN_WORKING("Newest Known Working Fallback"),
        PINNED_DEFAULT("Pinned Default (v3099)")
    }

    data class ShardsState(
        val local: LocalShardsState,
        val remote: RemoteShardsState?,
        val updateAvailable: Boolean,
        var downloadState: ShardDownloadState? = null,
        val activeManifestUrl: String = "",
        val manifestSource: ManifestSource = ManifestSource.PINNED_DEFAULT,
        val remoteFetchError: String? = null,
        var cacheDownloadState: ShardsCacheRepository.DatabaseDownloadState =
            ShardsCacheRepository.DatabaseDownloadState.Idle
    )

    data class LocalShardsState(
        val versionCode: Int,
        val date: LocalDateTime,
        val selectedCountries: List<ShardCountry>,
        val trackCount: Int,
        val personalisedTrackCount: Int,
        val activeManifestHttpsUrl: String = ""
    )

    data class RemoteShardsState(
        val versionCode: Int,
        val date: LocalDateTime,
        val url: String,
        val source: ManifestSource = ManifestSource.REMOTE_POINTER,
        val errorMessage: String? = null
    )

    /**
     *  List of countries in the Shards manifest. New countries must be added manually.
     */
    enum class ShardCountry(
        val code: String,
        @StringRes val countryName: Int,
        @DrawableRes val icon: Int
    ) {
        AR("ar", R.string.shard_country_argentina, R.drawable.ic_flag_ar),
        AU("au", R.string.shard_country_australia, R.drawable.ic_flag_au),
        BR("br", R.string.shard_country_brazil, R.drawable.ic_flag_br),
        CA("ca", R.string.shard_country_canada, R.drawable.ic_flag_ca),
        CH("ch", R.string.shard_country_switzerland, R.drawable.ic_flag_ch),
        DE("de", R.string.shard_country_germany, R.drawable.ic_flag_de),
        ES("es", R.string.shard_country_spain, R.drawable.ic_flag_es),
        FR("fr", R.string.shard_country_france, R.drawable.ic_flag_fr),
        GB("gb", R.string.shard_country_united_kingdom, R.drawable.ic_flag_gb),
        IE("ie", R.string.shard_country_ireland, R.drawable.ic_flag_ie),
        IN("in", R.string.shard_country_india, R.drawable.ic_flag_in),
        IT("it", R.string.shard_country_italy, R.drawable.ic_flag_it),
        JP("jp", R.string.shard_country_japan, R.drawable.ic_flag_jp),
        MX("mx", R.string.shard_country_mexico, R.drawable.ic_flag_mx),
        NL("nl", R.string.shard_country_netherlands, R.drawable.ic_flag_nl),
        RU("ru", R.string.shard_country_russia, R.drawable.ic_flag_ru),
        US("us,xa", R.string.shard_country_united_states, R.drawable.ic_flag_us);

        companion object {
            const val CORE_SHARED_FILENAME = "matcher_tah.leveldb"

            fun takeIfCountry(code: String): String? {
                return if(values().any { it.codes().contains(code.lowercase()) }) code else null
            }

            fun forCode(code: String): ShardCountry {
                return values().firstOrNull { it.codes().contains(code.lowercase()) } ?: US
            }
        }

        fun codes(): List<String> {
            return if(code.contains(",")){
                listOf(code) + code.split(",")
            }else listOf(code)
        }
    }

}

class ShardsRepositoryImpl(
    private val gson: Gson,
    private val context: Context,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadLogRepository: DownloadLogRepository
): ShardsRepository {

    companion object {
        private const val TAG = "ShardsRepo"
        private const val AUTHORITY = "com.google.android.as.pam.ambientmusic.leveldbprovider"
        private const val SCHEME = "content"
        private const val METHOD_COUNT = "count"
        private const val METHOD_COUNT_LEVELDB = "count/leveldb"
        private const val METHOD_COUNT_LINEAR = "linear"
        private const val METHOD_DOWNLOAD_STATE = "downloadstate"
        private const val TYPE_LINEAR_NORMAL = "linear.db"
        private const val TYPE_LINEAR_V3 = "linear_v3.db"

        private val CACHE_TIMEOUT = Duration.ofHours(12).toMillis()
        private const val CACHE_FILENAME = "shards_manifest_v3"

        private val URI_DOWNLOAD_STATE = Uri.Builder().apply {
            scheme(SCHEME)
            authority(AUTHORITY)
            path(METHOD_DOWNLOAD_STATE)
        }.build()

        val URI_LINEAR = Uri.Builder().apply {
            scheme(SCHEME)
            authority(AUTHORITY)
            path(METHOD_COUNT_LINEAR)
        }.build()
    }

    private val contentResolver = context.contentResolver
    private val shardsProvider = ShardsProvider.getShardsProvider()
    private val scope = MainScope()

    private val updatesCacheDir = File(context.cacheDir, "updates").apply {
        mkdirs()
    }

    private val linearChangeBus = context.contentReceiverAsFlow(URI_LINEAR)
        .map { System.currentTimeMillis() }
        .stateIn(scope, SharingStarted.Eagerly, System.currentTimeMillis())

    override fun getCurrentDownloads() = context.contentResolverAsTFlow(URI_DOWNLOAD_STATE) {
        val cursor = contentResolver.safeQuery(
            URI_DOWNLOAD_STATE, null, null, null, null
        ) ?: return@contentResolverAsTFlow 0
        val count = cursor.map {
            it.getInt(0)
        }.firstOrNull() ?: 0
        count.also {
            cursor.close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun isUpdateAvailable(clearCache: Boolean): Boolean = withContext(Dispatchers.IO) {
        val localShard = deviceConfigRepository.indexManifest.get().getUrlData()
        val remoteShard = getRemoteShards(clearCache).first().first
        remoteShard != null && remoteShard.versionCode != localShard.first
    }

    override suspend fun setManualManifestOverride(urlOrEmpty: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = urlOrEmpty.trim()
        if (trimmed.isEmpty()) {
            settingsRepository.manualManifestUrl.set("")
            val fallback = settingsRepository.newestKnownManifestUrl.get().takeIf { it.isNotBlank() }
                ?: if (isArmv7) BuildConfig.DEFAULT_MANIFEST else BuildConfig.DEFAULT_MANIFEST_V3
            deviceConfigRepository.indexManifest.set(fallback)
            downloadLogRepository.logInfo(TAG, "Cleared manual manifest override; reverted to $fallback")
            return@withContext Result.success(fallback)
        }
        val parsed = ManifestUrlParser.parse(trimmed)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Invalid manifest URL. Must be https://.../manifest.json or <version>:https://.../manifest.json")
            )
        settingsRepository.manualManifestUrl.set(parsed.deviceConfigValue)
        deviceConfigRepository.indexManifest.set(parsed.deviceConfigValue)
        downloadLogRepository.logInfo(
            TAG,
            "Applied manual manifest URL override: ${parsed.deviceConfigValue} (HTTPS: ${parsed.httpsUrl})"
        )
        Result.success(parsed.deviceConfigValue)
    }

    private suspend fun getDatabaseTracksCount(): Int = withContext(Dispatchers.IO) {
        val uri = Uri.Builder().apply {
            scheme(SCHEME)
            authority(AUTHORITY)
            path(METHOD_COUNT)
            appendPath("leveldb")
        }.build()
        val cursor = contentResolver.safeQuery(
            uri, null, null, null, null
        ) ?: return@withContext 0
        val count = cursor.map {
            it.getInt(0)
        }.firstOrNull() ?: 0
        count.also {
            cursor.close()
        }
    }

    private fun getLinearTracksCount() = linearChangeBus.mapLatest {
        val type = if (isArmv7) {
            TYPE_LINEAR_NORMAL
        } else {
            TYPE_LINEAR_V3
        }
        val uri = Uri.Builder().apply {
            scheme(SCHEME)
            authority(AUTHORITY)
            path(METHOD_COUNT)
            appendPath(METHOD_COUNT_LINEAR)
            appendPath(type)
        }.build()
        val cursor = contentResolver.safeQuery(
            uri, null, null, null, null
        ) ?: return@mapLatest 0
        val count = cursor.map {
            it.getInt(0)
        }.firstOrNull() ?: 0
        count.also {
            cursor.close()
        }
    }.flowOn(Dispatchers.IO)

    private val localShards = combine(
        deviceConfigRepository.deviceCountry.asFlow(),
        deviceConfigRepository.extraLanguages.asFlow(),
        deviceConfigRepository.indexManifest.asFlow(),
        getLinearTracksCount()
    ) { _, _, index, personalisedTrackCount ->
        val primaryCountry = getPrimaryCountry()
        val extraCountries = getExtraCountries()
            .filterNot { it == primaryCountry }
            .distinct()
        val trackCount = getDatabaseTracksCount()
        val urlData = index.getUrlData()
        val httpsUrl = ManifestUrlParser.extractHttpsUrl(index)
        LocalShardsState(
            urlData.first,
            urlData.second,
            listOf(primaryCountry) + extraCountries,
            trackCount,
            personalisedTrackCount,
            httpsUrl
        )
    }.flowOn(Dispatchers.IO)

    private suspend fun getPrimaryCountry(): ShardCountry {
        return ShardCountry.forCode(deviceConfigRepository.getPrimaryLanguage())
    }

    private suspend fun getExtraCountries(): List<ShardCountry> {
        return deviceConfigRepository.getExtraLanguages().map {
            ShardCountry.forCode(it)
        }
    }

    /**
     * Phase 0.3 Manifest Fallback Chain:
     * 1. If user configured a Manual Manifest URL in settings, use it and report MANUAL_OVERRIDE.
     * 2. Otherwise fetch remote pointer (raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release_v3.json).
     *    When remote succeeds, persist its URL to `newestKnownManifestUrl` so offline/future runs have it.
     * 3. If remote pointer fails, log the real exception and fall back to `newestKnownManifestUrl` -> `DEFAULT_MANIFEST_V3`.
     */
    private fun getRemoteShards(ignoreCache: Boolean) = flow<Pair<RemoteShardsState?, String?>> {
        val manualOverride = settingsRepository.manualManifestUrl.get().trim()
        if (manualOverride.isNotEmpty()) {
            val parsed = ManifestUrlParser.parse(manualOverride)
            if (parsed != null) {
                downloadLogRepository.logInfo(
                    TAG,
                    "Using manual manifest override: ${parsed.httpsUrl} (version ${parsed.versionCode})"
                )
                emit(
                    Pair(
                        RemoteShardsState(
                            versionCode = parsed.versionCode,
                            date = parsed.date,
                            url = parsed.deviceConfigValue,
                            source = ManifestSource.MANUAL_OVERRIDE
                        ),
                        null
                    )
                )
                return@flow
            }
        }

        var remoteError: String? = null
        val remoteManifest: ShardManifest? = try {
            val cached = if (ignoreCache) null else getShardsCache()
            if (cached != null) {
                cached
            } else {
                val response = shardsProvider.getCompatibleShardsManifest().execute()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code()} ${response.message()} fetching release_v3.json")
                }
                response.body()?.also {
                    it.cacheManifest()
                    settingsRepository.newestKnownManifestUrl.set(it.url)
                    downloadLogRepository.logInfo(
                        TAG,
                        "Fetched remote manifest pointer: version=${it.versionCode}, url=${it.url}"
                    )
                } ?: throw IOException("Empty response body from release_v3.json")
            }
        } catch (e: Exception) {
            remoteError = "${e.javaClass.simpleName}: ${e.message}"
            downloadLogRepository.logWarn(
                TAG,
                "Remote manifest pointer failed ($remoteError); falling back to newest known working manifest.",
                e
            )
            null
        }

        if (remoteManifest != null) {
            val urlData = remoteManifest.url.getUrlData()
            emit(
                Pair(
                    RemoteShardsState(
                        versionCode = urlData.first,
                        date = urlData.second,
                        url = remoteManifest.url,
                        source = ManifestSource.REMOTE_POINTER,
                        errorMessage = null
                    ),
                    null
                )
            )
        } else {
            // Fallback to newest known working -> pinned default
            val fallbackConfigValue = settingsRepository.newestKnownManifestUrl.get().takeIf { it.isNotBlank() }
                ?: if (isArmv7) BuildConfig.DEFAULT_MANIFEST else BuildConfig.DEFAULT_MANIFEST_V3
            val urlData = fallbackConfigValue.getUrlData()
            val source = if (fallbackConfigValue != BuildConfig.DEFAULT_MANIFEST_V3 && fallbackConfigValue != BuildConfig.DEFAULT_MANIFEST) {
                ManifestSource.NEWEST_KNOWN_WORKING
            } else {
                ManifestSource.PINNED_DEFAULT
            }
            emit(
                Pair(
                    RemoteShardsState(
                        versionCode = urlData.first,
                        date = urlData.second,
                        url = fallbackConfigValue,
                        source = source,
                        errorMessage = remoteError
                    ),
                    remoteError
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun ShardsProvider.getCompatibleShardsManifest(): Call<ShardManifest> {
        return if (isArmv7) {
            getShardsManifest()
        } else {
            getShardsManifestV3()
        }
    }

    override fun getShardsState(clearCache: Boolean): Flow<ShardsState> = flow {
        emit(combine(
            localShards,
            getRemoteShards(clearCache),
            settingsRepository.manualManifestUrl.asFlow()
        ) { local, remotePair, manualUrl ->
            val remote = remotePair.first
            val fetchError = remotePair.second
            val updateAvailable = remote != null && local.versionCode != remote.versionCode
            val resolvedSource = when {
                manualUrl.isNotBlank() -> ManifestSource.MANUAL_OVERRIDE
                remote != null -> remote.source
                else -> ManifestSource.PINNED_DEFAULT
            }
            ShardsState(
                local = local,
                remote = remote,
                updateAvailable = updateAvailable,
                activeManifestUrl = local.activeManifestHttpsUrl,
                manifestSource = resolvedSource,
                remoteFetchError = fetchError
            )
        }.first())
    }

    private fun String.getUrlData(): Pair<Int, LocalDateTime> {
        val parsed = ManifestUrlParser.parse(this)
        if (parsed != null) {
            return Pair(parsed.versionCode, parsed.date)
        }
        val fallback = ManifestUrlParser.parse(BuildConfig.DEFAULT_MANIFEST_V3)!!
        return Pair(fallback.versionCode, fallback.date)
    }

    private fun getShardsCache(): ShardManifest? {
        val file = File(updatesCacheDir, CACHE_FILENAME)
        if(!file.exists()) return null
        val cachedShardManifest = try {
            gson.fromJson(file.readText(), CachedShardManifest::class.java)
        }catch (e: Exception){
            null
        } ?: return null
        val cacheAge = Duration.between(
            Instant.ofEpochMilli(cachedShardManifest.timestamp), Instant.now()
        ).toMillis()
        if(cacheAge > CACHE_TIMEOUT) return null
        return cachedShardManifest.shardManifest
    }

    private fun ShardManifest.cacheManifest() {
        val file = File(updatesCacheDir, CACHE_FILENAME)
        val cachedRelease = gson.toJson(CachedShardManifest(System.currentTimeMillis(), this))
        file.writeText(cachedRelease)
    }

}
