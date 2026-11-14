package com.kieronquinn.app.ambientmusicmod.ui.screens.updates

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.databinding.FragmentUpdatesBinding
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItem
import com.kieronquinn.app.ambientmusicmod.model.settings.GenericSettingsItem
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsCacheRepository.ImportExportState
import com.kieronquinn.app.ambientmusicmod.ui.base.BoundFragment
import com.kieronquinn.app.ambientmusicmod.ui.base.Root
import com.kieronquinn.app.ambientmusicmod.ui.screens.updates.UpdatesViewModel.State
import com.kieronquinn.app.ambientmusicmod.ui.screens.updates.UpdatesViewModel.UpdatesSettingsItem
import com.kieronquinn.app.ambientmusicmod.utils.extensions.applyBottomNavigationInset
import com.kieronquinn.app.ambientmusicmod.utils.extensions.whenResumed
import com.kieronquinn.monetcompat.extensions.views.applyMonet
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

class UpdatesFragment: BoundFragment<FragmentUpdatesBinding>(FragmentUpdatesBinding::inflate), Root {

    private val viewModel by viewModel<UpdatesViewModel>()

    private val adapter by lazy {
        UpdatesAdapter(binding.updatesRecyclerView, emptyList())
    }

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onImportFromUri(uri, isTreeUri = true)
        }
    }

    private val importZipOrFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onImportFromUri(uri, isTreeUri = false)
        }
    }

    private val exportZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.onExportCacheToZip(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupState()
        setupToasts()
        setupRecyclerView()
        setupLoading()
        setupSwipeRefresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    private fun setupSwipeRefresh() = with(binding.updatesSwipeRefresh) {
        setColorSchemeColors(monet.getAccentColor(context))
        setOnRefreshListener {
            viewModel.reload(true)
        }
    }

    private fun setupRecyclerView() = with(binding.updatesRecyclerView) {
        layoutManager = LinearLayoutManager(context)
        adapter = this@UpdatesFragment.adapter
        applyBottomNavigationInset(resources.getDimension(R.dimen.margin_16))
    }

    private fun setupLoading() = with(binding.updatesLoadingProgress) {
        applyMonet()
    }

    private fun setupToasts() = whenResumed {
        viewModel.toastBus.collect { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupState() {
        handleState(viewModel.state.value)
        whenResumed {
            viewModel.state.collect {
                handleState(it)
            }
        }
    }

    private fun handleState(state: State) {
        when(state){
            is State.Loading -> {
                binding.updatesLoading.isVisible = true
                binding.updatesRecyclerView.isVisible = false
            }
            is State.Loaded -> {
                binding.updatesSwipeRefresh.isRefreshing = false
                binding.updatesLoading.isVisible = false
                binding.updatesRecyclerView.isVisible = true
                adapter.update(loadItems(state), binding.updatesRecyclerView)
            }
        }
    }

    private fun showImportSourceDialog() {
        val options = arrayOf(
            getString(R.string.updates_import_option_folder),
            getString(R.string.updates_import_option_zip),
            getString(R.string.updates_import_option_where_to_get)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.updates_import_database_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFolderLauncher.launch(null)
                    1 -> importZipOrFileLauncher.launch(arrayOf("*/*"))
                    2 -> showWhereToGetFilesDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWhereToGetFilesDialog() {
        val state = viewModel.state.value as? State.Loaded
        val manifestUrl = state?.shardsState?.activeManifestUrl ?: ""
        val message = getString(R.string.updates_where_to_get_files_explanation, manifestUrl)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.updates_where_to_get_files_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadItems(state: State.Loaded): List<BaseSettingsItem> {
        val cacheMb = state.cacheSummary.totalCompletedBytes / (1024.0 * 1024.0)
        val partialMb = state.cacheSummary.totalPartialBytes / (1024.0 * 1024.0)
        val cacheSubtitle = String.format(
            Locale.US,
            "%d completed pack(s) (%.1f MB), %d partial .part file(s) (%.1f MB). Tap to clear.",
            state.cacheSummary.completedFileCount,
            cacheMb,
            state.cacheSummary.partialFileCount,
            partialMb
        )

        val ieBannerSubtitle = when (val ie = state.importExportState) {
            is ImportExportState.Progress -> ie.message
            is ImportExportState.Completed -> {
                if (ie.warnings.isEmpty()) ie.summaryMessage
                else "${ie.summaryMessage}\nWarnings:\n• ${ie.warnings.take(5).joinToString("\n• ")}"
            }
            is ImportExportState.Failed -> {
                if (ie.details.isEmpty()) "Error: ${ie.errorMessage}"
                else "Error: ${ie.errorMessage}\n• ${ie.details.take(5).joinToString("\n• ")}"
            }
            null -> getString(R.string.updates_import_database_subtitle)
        }

        return listOf(
            UpdatesSettingsItem.Shards(
                state.shardsState,
                state.supportsMultipleCountries,
                viewModel::onShardsUpdateClicked,
                viewModel::onShardsViewTracksClicked,
                viewModel::onCountryClicked,
                viewModel::onAdditionalCountries
            ),
            GenericSettingsItem.Header(getString(R.string.updates_database_actions_header)),
            GenericSettingsItem.Setting(
                getString(R.string.updates_force_run_download_title),
                getString(R.string.updates_force_run_download_subtitle),
                R.drawable.ic_download,
                onClick = viewModel::onForceRunDownloadClicked
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_retry_download_now_title),
                getString(R.string.updates_retry_download_now_subtitle),
                R.drawable.ic_recognition_retry,
                onClick = viewModel::onRetryDownloadNowClicked
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_import_database_title),
                ieBannerSubtitle,
                R.drawable.ic_backup_restore_restore,
                onClick = ::showImportSourceDialog
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_export_cache_title),
                getString(R.string.updates_export_cache_subtitle),
                R.drawable.ic_backup_restore_backup,
                onClick = { exportZipLauncher.launch("amm_music_database_cache.zip") }
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_where_to_get_files_title),
                getString(R.string.updates_where_to_get_files_subtitle),
                R.drawable.ic_info,
                onClick = ::showWhereToGetFilesDialog
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_clear_cache_title),
                cacheSubtitle,
                R.drawable.ic_delete,
                onClick = viewModel::onClearShardsCacheClicked
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_download_diagnostics_title),
                getString(R.string.updates_download_diagnostics_subtitle),
                R.drawable.ic_test,
                onClick = viewModel::onDownloadDiagnosticsClicked
            ),
            GenericSettingsItem.Setting(
                getString(R.string.updates_download_logs_title),
                getString(R.string.updates_download_logs_subtitle),
                R.drawable.ic_settings_advanced_logging,
                onClick = viewModel::onDownloadLogsClicked
            ),
            GenericSettingsItem.Header(getString(R.string.updates_app_updates_header)),
            UpdatesSettingsItem.AMM(state.ammState){
                viewModel.onAMMUpdateClicked(getString(R.string.updates_amm_title), it)
            },
            UpdatesSettingsItem.PAM(state.pamState) {
                viewModel.onPAMUpdateClicked(getString(R.string.updates_pam_title), it)
            },
            GenericSettingsItem.SwitchSetting(
                state.automaticDatabaseUpdates,
                getString(R.string.updates_automatic_database_downloads),
                getString(R.string.updates_automatic_database_downloads_content),
                R.drawable.ic_updates_automatic_database_updates,
                onChanged = viewModel::onAutomaticDatabaseUpdatesChanged
            ),
            UpdatesSettingsItem.About(
                viewModel::onContributorsClicked,
                viewModel::onDonateClicked,
                viewModel::onGitHubClicked,
                viewModel::onTwitterClicked,
                viewModel::onXdaClicked,
                viewModel::onLibrariesClicked
            )
        )
    }

}
