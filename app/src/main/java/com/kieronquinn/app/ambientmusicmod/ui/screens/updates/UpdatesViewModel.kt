package com.kieronquinn.app.ambientmusicmod.ui.screens.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.ambientmusicmod.components.navigation.ContainerNavigation
import com.kieronquinn.app.ambientmusicmod.components.navigation.RootNavigation
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItem
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItemType
import com.kieronquinn.app.ambientmusicmod.model.update.Release
import com.kieronquinn.app.ambientmusicmod.model.update.toRelease
import com.kieronquinn.app.ambientmusicmod.repositories.BatteryOptimisationRepository
import com.kieronquinn.app.ambientmusicmod.repositories.DeviceConfigRepository
import com.kieronquinn.app.ambientmusicmod.repositories.JobsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.SettingsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsCacheRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsCacheRepository.ImportExportState
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.ShardDownloadState
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsRepository.ShardsState
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository.UpdateState
import com.kieronquinn.app.ambientmusicmod.ui.screens.container.ContainerFragmentDirections
import com.kieronquinn.app.ambientmusicmod.utils.extensions.firstNotNull
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getNetworkCapabilities
import com.kieronquinn.app.ambientmusicmod.utils.extensions.isCharging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

abstract class UpdatesViewModel: ViewModel() {

    abstract fun reload(clearCache: Boolean = false)
    abstract val state: StateFlow<State>
    abstract val toastBus: SharedFlow<String>

    abstract fun onShardsUpdateClicked(shardsState: ShardsState)
    abstract fun onShardsViewTracksClicked()
    abstract fun onCountryClicked()
    abstract fun onAdditionalCountries()
    abstract fun onAMMUpdateClicked(label: String, updateState: UpdateState)
    abstract fun onPAMUpdateClicked(label: String, updateState: UpdateState)

    abstract fun onForceRunDownloadClicked()
    abstract fun onRetryDownloadNowClicked()
    abstract fun onClearShardsCacheClicked()
    abstract fun onDownloadDiagnosticsClicked()
    abstract fun onDownloadLogsClicked()
    abstract fun onImportFromUri(uri: Uri, isTreeUri: Boolean)
    abstract fun onExportCacheToZip(uri: Uri)

    abstract fun onContributorsClicked()
    abstract fun onDonateClicked()
    abstract fun onGitHubClicked()
    abstract fun onLibrariesClicked()
    abstract fun onTwitterClicked()
    abstract fun onXdaClicked()

    abstract fun onAutomaticDatabaseUpdatesChanged(enabled: Boolean)

    sealed class State {
        object Loading: State()
        data class Loaded(
            val shardsState: ShardsState,
            val ammState: UpdateState,
            val pamState: UpdateState,
            val automaticDatabaseUpdates: Boolean,
            val supportsMultipleCountries: Boolean,
            val cacheSummary: ShardsCacheRepository.CacheSummary,
            val importExportState: ImportExportState? = null
        ): State() {
            override fun equals(other: Any?): Boolean {
                return false
            }
        }
    }

    sealed class UpdatesSettingsItem(val type: ItemType): BaseSettingsItem(type) {

        data class Shards(
            val shardsState: ShardsState,
            val multipleCountriesSupported: Boolean,
            val onUpdateClicked: (ShardsState) -> Unit,
            val onViewTracksClicked: () -> Unit,
            val onCountryClicked: () -> Unit,
            val onAdditionalCountriesClicked: () -> Unit,
        ): UpdatesSettingsItem(ItemType.SHARDS)

        data class AMM(
            val updateState: UpdateState,
            val onUpdateClicked: (UpdateState) -> Unit
        ): UpdatesSettingsItem(ItemType.AMM)

        data class PAM(
            val updateState: UpdateState,
            val onUpdateClicked: (UpdateState) -> Unit
        ): UpdatesSettingsItem(ItemType.PAM)

        data class About(
            val onContributorsClicked: () -> Unit,
            val onDonateClicked: () -> Unit,
            val onGitHubClicked: () -> Unit,
            val onTwitterClicked: () -> Unit,
            val onXdaClicked: () -> Unit,
            val onLibrariesClicked: () -> Unit
        ): UpdatesSettingsItem(ItemType.ABOUT)

        enum class ItemType: BaseSettingsItemType {
            SHARDS, AMM, PAM, ABOUT
        }
    }

}

class UpdatesViewModelImpl(
    private val shardsRepository: ShardsRepository,
    private val shardsCacheRepository: ShardsCacheRepository,
    private val updatesRepository: UpdatesRepository,
    private val batteryOptimisationRepository: BatteryOptimisationRepository,
    private val jobsRepository: JobsRepository,
    private val rootNavigation: RootNavigation,
    private val navigation: ContainerNavigation,
    private val deviceConfigRepository: DeviceConfigRepository,
    settingsRepository: SettingsRepository,
    context: Context
): UpdatesViewModel() {

    companion object {
        private const val LINK_TWITTER = "https://kieronquinn.co.uk/redirect/AmbientMusicMod/twitter"
        private const val LINK_GITHUB = "https://kieronquinn.co.uk/redirect/AmbientMusicMod/github"
        private const val LINK_XDA = "https://kieronquinn.co.uk/redirect/AmbientMusicMod/xda"
        private const val LINK_DONATE = "https://kieronquinn.co.uk/redirect/AmbientMusicMod/donate"
        private const val MIN_MULTIPLE_COUNTRIES_VERSION_CODE = 120L
    }

    private val reloadBus = MutableStateFlow(Pair(System.currentTimeMillis(), false))
    private val importExportStateFlow = MutableStateFlow<ImportExportState?>(null)
    private val _toastBus = MutableSharedFlow<String>()
    override val toastBus = _toastBus.asSharedFlow()

    private val automaticDatabaseUpdates = settingsRepository.automaticMusicDatabaseUpdates

    private fun getAmmState(clearCache: Boolean) = flow {
        emit(updatesRepository.getAMMUpdateState(clearCache))
    }.flowOn(Dispatchers.IO)

    private fun getPamState(clearCache: Boolean) = flow {
        emit(updatesRepository.getPAMUpdateState(ignoreCache = clearCache))
    }.flowOn(Dispatchers.IO)

    private val downloadState = combine(
        shardsRepository.getCurrentDownloads(),
        deviceConfigRepository.superpacksRequireWiFi.asFlow(),
        deviceConfigRepository.superpacksRequireCharging.asFlow(),
        context.getNetworkCapabilities(),
        context.isCharging()
    ) { downloadCount, requireWifi, requireCharging, capabilities, charging ->
        if(downloadCount == 0) return@combine null
        when {
            !capabilities.unmetered && capabilities.hasInternet && requireWifi -> {
                ShardDownloadState.WAITING_FOR_NETWORK
            }
            !capabilities.hasInternet -> {
                ShardDownloadState.WAITING_FOR_NETWORK
            }
            !charging && requireCharging -> {
                ShardDownloadState.WAITING_FOR_CHARGING
            }
            else -> ShardDownloadState.DOWNLOADING
        }
    }

    override val state = reloadBus.flatMapLatest { reloadArgs ->
        combine(
            getAmmState(reloadArgs.second),
            getPamState(reloadArgs.second),
            downloadState,
            automaticDatabaseUpdates.asFlow(),
            shardsCacheRepository.downloadState,
            importExportStateFlow
        ) { args ->
            val amm = args[0] as UpdateState
            val pam = args[1] as UpdateState
            val download = args[2] as ShardDownloadState?
            val automaticUpdates = args[3] as Boolean
            val cacheDlState = args[4] as ShardsCacheRepository.DatabaseDownloadState
            val ieState = args[5] as ImportExportState?

            val shards = shardsRepository.getShardsState(reloadArgs.second).firstNotNull().apply {
                this.downloadState = download
                this.cacheDownloadState = cacheDlState
            }
            val supportsMultipleCountries = when(pam) {
                is UpdateState.UpToDate -> {
                    pam.localVersionCode >= MIN_MULTIPLE_COUNTRIES_VERSION_CODE
                }
                is UpdateState.UpdateAvailable -> {
                    pam.localVersionCode >= MIN_MULTIPLE_COUNTRIES_VERSION_CODE
                }
                else -> true
            }
            if(automaticUpdates && shards.updateAvailable && shards.remote != null){
                deviceConfigRepository.indexManifest.set(shards.remote.url)
                shardsCacheRepository.startForegroundDownload(force = false)
            }
            val cacheSummary = shardsCacheRepository.getCacheSummary()
            State.Loaded(
                shardsState = shards,
                ammState = amm,
                pamState = pam,
                automaticDatabaseUpdates = automaticUpdates,
                supportsMultipleCountries = supportsMultipleCountries,
                cacheSummary = cacheSummary,
                importExportState = ieState
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State.Loading)

    override fun reload(clearCache: Boolean) {
        viewModelScope.launch {
            reloadBus.emit(Pair(System.currentTimeMillis(), clearCache))
        }
    }

    private suspend fun promptBatteryExemptionIfNeeded() {
        batteryOptimisationRepository.getDisableBatteryOptimisationsIntent()?.let { intent ->
            navigation.navigate(intent)
        }
    }

    override fun onShardsUpdateClicked(shardsState: ShardsState) {
        viewModelScope.launch {
            promptBatteryExemptionIfNeeded()
            val remoteUrl = shardsState.remote?.url
            if (remoteUrl != null && shardsState.updateAvailable) {
                deviceConfigRepository.indexManifest.set(remoteUrl)
            }
            shardsCacheRepository.startForegroundDownload(force = true)
            jobsRepository.forceExpediteJobs()
        }
    }

    override fun onForceRunDownloadClicked() {
        viewModelScope.launch {
            promptBatteryExemptionIfNeeded()
            shardsCacheRepository.startForegroundDownload(force = true)
            jobsRepository.forceExpediteJobs()
            _toastBus.emit("Started resumable database download & expedited Now Playing jobs")
        }
    }

    override fun onRetryDownloadNowClicked() {
        viewModelScope.launch {
            promptBatteryExemptionIfNeeded()
            shardsCacheRepository.startForegroundDownload(force = false)
            jobsRepository.forceExpediteJobs()
            _toastBus.emit("Retrying database download...")
        }
    }

    override fun onClearShardsCacheClicked() {
        viewModelScope.launch {
            val ok = shardsCacheRepository.clearCache()
            reload(false)
            _toastBus.emit(if (ok) "Cleared local database cache" else "Cache cleared with warnings")
        }
    }

    override fun onDownloadDiagnosticsClicked() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToDownloadDiagnosticsFragment())
        }
    }

    override fun onDownloadLogsClicked() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToDownloadLogsFragment())
        }
    }

    override fun onImportFromUri(uri: Uri, isTreeUri: Boolean) {
        viewModelScope.launch {
            shardsCacheRepository.importFromUri(uri, isTreeUri).collect { st ->
                importExportStateFlow.value = st
                if (st is ImportExportState.Completed) {
                    _toastBus.emit(st.summaryMessage)
                    reload(false)
                } else if (st is ImportExportState.Failed) {
                    _toastBus.emit(st.errorMessage)
                }
            }
        }
    }

    override fun onExportCacheToZip(uri: Uri) {
        viewModelScope.launch {
            shardsCacheRepository.exportCacheToZip(uri).collect { st ->
                importExportStateFlow.value = st
                if (st is ImportExportState.Completed) {
                    _toastBus.emit(st.summaryMessage)
                } else if (st is ImportExportState.Failed) {
                    _toastBus.emit(st.errorMessage)
                }
            }
        }
    }

    override fun onCountryClicked() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToCountryPickerFragment())
        }
    }

    override fun onAdditionalCountries() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToSettingsExtraCountryPickerFragment())
        }
    }

    override fun onAMMUpdateClicked(label: String, updateState: UpdateState) {
        val release = updateState.toRelease(label) ?: return
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToUpdatesDownloadFragment(release))
        }
    }

    override fun onPAMUpdateClicked(label: String, updateState: UpdateState) {
        val release = updateState.toRelease(label) ?: return
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToUpdatesDownloadFragment(release))
        }
    }

    private fun UpdateState.toRelease(label: String): Release? {
        return when(this){
            is UpdateState.UpdateAvailable -> Pair(localVersion, release)
            is UpdateState.NotInstalled -> Pair("", release)
            else -> null
        }?.let {
            it.second.toRelease(label, it.first)
        }
    }

    override fun onShardsViewTracksClicked() {
        viewModelScope.launch {
            rootNavigation.navigate(ContainerFragmentDirections.actionContainerFragmentToTracklistFragment())
        }
    }

    override fun onAutomaticDatabaseUpdatesChanged(enabled: Boolean) {
        viewModelScope.launch {
            automaticDatabaseUpdates.set(enabled)
        }
    }

    override fun onContributorsClicked() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToContributorsFragment())
        }
    }

    override fun onDonateClicked() {
        viewModelScope.launch {
            navigation.navigate(LINK_DONATE.toIntent())
        }
    }

    override fun onGitHubClicked() {
        viewModelScope.launch {
            navigation.navigate(LINK_GITHUB.toIntent())
        }
    }

    override fun onLibrariesClicked() {
        viewModelScope.launch {
            navigation.navigate(UpdatesFragmentDirections.actionUpdatesFragmentToOssLicensesMenuActivity())
        }
    }

    override fun onTwitterClicked() {
        viewModelScope.launch {
            navigation.navigate(LINK_TWITTER.toIntent())
        }
    }

    override fun onXdaClicked() {
        viewModelScope.launch {
            navigation.navigate(LINK_XDA.toIntent())
        }
    }

    private fun String.toIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(this@toIntent)
        }
    }

}
