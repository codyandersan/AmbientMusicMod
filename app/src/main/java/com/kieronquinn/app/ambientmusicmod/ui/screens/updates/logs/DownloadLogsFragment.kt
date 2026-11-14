package com.kieronquinn.app.ambientmusicmod.ui.screens.updates.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItem
import com.kieronquinn.app.ambientmusicmod.model.settings.GenericSettingsItem
import com.kieronquinn.app.ambientmusicmod.ui.base.BackAvailable
import com.kieronquinn.app.ambientmusicmod.ui.base.settings.BaseSettingsFragment
import com.kieronquinn.app.ambientmusicmod.ui.screens.updates.logs.DownloadLogsViewModel.State
import com.kieronquinn.app.ambientmusicmod.utils.extensions.whenResumed
import org.koin.androidx.viewmodel.ext.android.viewModel

class DownloadLogsFragment : BaseSettingsFragment(), BackAvailable {

    override val addAdditionalPadding = true

    private val viewModel by viewModel<DownloadLogsViewModel>()

    override val adapter by lazy {
        DownloadLogsAdapter(binding.settingsBaseRecyclerView, emptyList())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLogs()
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
        when (state) {
            is State.Loading -> {
                binding.settingsBaseLoading.isVisible = true
                binding.settingsBaseRecyclerView.isVisible = false
            }
            is State.Loaded -> {
                binding.settingsBaseLoading.isVisible = false
                binding.settingsBaseRecyclerView.isVisible = true
                adapter.update(loadItems(state), binding.settingsBaseRecyclerView)
            }
        }
    }

    private fun loadItems(state: State.Loaded): List<BaseSettingsItem> {
        val items = ArrayList<BaseSettingsItem>()
        items.add(
            GenericSettingsItem.Setting(
                title = getString(R.string.download_logs_refresh_title),
                subtitle = getString(R.string.download_logs_refresh_subtitle, state.logLines.size),
                icon = R.drawable.ic_recognition_retry,
                onClick = viewModel::refreshLogs
            )
        )
        items.add(
            GenericSettingsItem.Setting(
                title = getString(R.string.download_logs_copy_share_title),
                subtitle = getString(R.string.download_logs_copy_share_subtitle),
                icon = R.drawable.ic_save,
                onClick = { copyAndShareLogs(state.fullText) }
            )
        )
        items.add(
            GenericSettingsItem.Setting(
                title = getString(R.string.download_logs_clear_title),
                subtitle = getString(R.string.download_logs_clear_subtitle),
                icon = R.drawable.ic_delete,
                onClick = {
                    viewModel.clearLogs()
                    Toast.makeText(requireContext(), R.string.download_logs_cleared_toast, Toast.LENGTH_SHORT).show()
                }
            )
        )
        items.add(GenericSettingsItem.Header(getString(R.string.download_logs_entries_header)))

        if (state.logLines.isEmpty()) {
            items.add(
                GenericSettingsItem.Setting(
                    title = getString(R.string.download_logs_empty_title),
                    subtitle = getString(R.string.download_logs_empty_subtitle),
                    icon = R.drawable.ic_info,
                    onClick = {}
                )
            )
        } else {
            // Show newest entries first so the user immediately sees the latest requests/errors
            state.logLines.asReversed().take(250).forEach { line ->
                val iconRes = when {
                    line.contains("ERROR") || line.contains("FAIL") -> R.drawable.ic_error_circle
                    line.contains("WARN") || line.contains("MISS") -> R.drawable.ic_warning
                    line.contains("HIT") || line.contains("COMPLETE") -> R.drawable.ic_check_circle
                    else -> R.drawable.ic_settings_advanced_logging
                }
                val timestampPart = line.take(23)
                val bodyPart = line.drop(24).ifBlank { line }
                items.add(
                    GenericSettingsItem.Setting(
                        title = timestampPart,
                        subtitle = bodyPart,
                        icon = iconRes,
                        onClick = {
                            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Log Entry", line))
                            Toast.makeText(requireContext(), R.string.diagnostics_copied_toast, Toast.LENGTH_SHORT).show()
                        }
                    )
                )
            }
        }
        return items
    }

    private fun copyAndShareLogs(fullText: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AMM Download Logs", fullText))
        Toast.makeText(requireContext(), R.string.diagnostics_copied_toast, Toast.LENGTH_SHORT).show()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AMM Download & Astrea Logs")
            putExtra(Intent.EXTRA_TEXT, fullText)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.download_logs_copy_share_title)))
    }
}
