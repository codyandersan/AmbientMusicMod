package com.kieronquinn.app.ambientmusicmod.ui.screens.updates.diagnostics

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
import com.kieronquinn.app.ambientmusicmod.repositories.DownloadDiagnosticsRepository.CheckStatus
import com.kieronquinn.app.ambientmusicmod.ui.base.BackAvailable
import com.kieronquinn.app.ambientmusicmod.ui.base.settings.BaseSettingsFragment
import com.kieronquinn.app.ambientmusicmod.ui.screens.updates.diagnostics.DownloadDiagnosticsViewModel.State
import com.kieronquinn.app.ambientmusicmod.utils.extensions.whenResumed
import org.koin.androidx.viewmodel.ext.android.viewModel

class DownloadDiagnosticsFragment : BaseSettingsFragment(), BackAvailable {

    override val addAdditionalPadding = true

    private val viewModel by viewModel<DownloadDiagnosticsViewModel>()

    override val adapter by lazy {
        DownloadDiagnosticsAdapter(binding.settingsBaseRecyclerView, emptyList())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupState()
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
                title = if (state.isRunning) getString(R.string.diagnostics_running_title)
                else getString(R.string.diagnostics_rerun_title),
                subtitle = getString(R.string.diagnostics_rerun_subtitle),
                icon = R.drawable.ic_recognition_retry,
                enabled = !state.isRunning,
                onClick = viewModel::runDiagnostics
            )
        )
        items.add(
            GenericSettingsItem.Setting(
                title = getString(R.string.diagnostics_copy_report_title),
                subtitle = getString(R.string.diagnostics_copy_report_subtitle),
                icon = R.drawable.ic_save,
                onClick = { copyAndShareReport(state) }
            )
        )
        items.add(GenericSettingsItem.Header(getString(R.string.diagnostics_results_header)))

        state.checks.forEach { check ->
            val statusPrefix = when (check.status) {
                CheckStatus.PENDING -> "[PENDING]"
                CheckStatus.RUNNING -> "[RUNNING...]"
                CheckStatus.PASSED -> "[PASS]"
                CheckStatus.WARNING -> "[WARN]"
                CheckStatus.FAILED -> "[FAIL]"
            }
            val iconRes = when (check.status) {
                CheckStatus.PASSED -> R.drawable.ic_check_circle
                CheckStatus.FAILED -> R.drawable.ic_error_circle
                CheckStatus.WARNING -> R.drawable.ic_warning
                else -> R.drawable.ic_info
            }
            items.add(
                GenericSettingsItem.Setting(
                    title = "$statusPrefix ${check.title}",
                    subtitle = check.detail,
                    icon = iconRes,
                    onClick = {
                        copyTextToClipboard(check.title, "${check.title}\n${check.detail}")
                    }
                )
            )
        }
        return items
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), R.string.diagnostics_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun copyAndShareReport(state: State.Loaded) {
        val report = buildString {
            appendLine("=== Ambient Music Mod Download Diagnostics ===")
            state.checks.forEach { c ->
                appendLine("[${c.status}] ${c.title} (${c.durationMs}ms)")
                appendLine("  ${c.detail}")
            }
        }
        copyTextToClipboard("AMM Diagnostics", report)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AMM Download Diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.diagnostics_copy_report_title)))
    }
}
