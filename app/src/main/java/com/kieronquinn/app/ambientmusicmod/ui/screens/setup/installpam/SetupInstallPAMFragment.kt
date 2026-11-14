package com.kieronquinn.app.ambientmusicmod.ui.screens.setup.installpam

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.databinding.FragmentSetupInstallPamBinding
import com.kieronquinn.app.ambientmusicmod.ui.base.BackAvailable
import com.kieronquinn.app.ambientmusicmod.ui.base.BoundFragment
import com.kieronquinn.app.ambientmusicmod.ui.screens.setup.installpam.SetupInstallPAMViewModel.State
import com.kieronquinn.app.ambientmusicmod.utils.extensions.onClicked
import com.kieronquinn.app.ambientmusicmod.utils.extensions.whenResumed
import com.kieronquinn.monetcompat.extensions.views.applyMonet
import com.kieronquinn.monetcompat.extensions.views.overrideRippleColor
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.math.roundToInt

class SetupInstallPAMFragment: BoundFragment<FragmentSetupInstallPamBinding>(FragmentSetupInstallPamBinding::inflate), BackAvailable {

    private val viewModel by viewModel<SetupInstallPAMViewModel>()

    private val localApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onLocalApkSelected(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupState()
        setupMonet()
        setupRetry()
        setupInstall()
        setupLocalApkFallback()
    }

    private fun setupMonet() = with(binding) {
        setupInstallPamLoadingProgress.applyMonet()
        val accent = monet.getAccentColor(requireContext())
        setupInstallPamRetry.run {
            setTextColor(accent)
            iconTint = ColorStateList.valueOf(accent)
            overrideRippleColor(accent)
        }
        setupInstallPamLocalApk.run {
            setTextColor(accent)
            iconTint = ColorStateList.valueOf(accent)
            overrideRippleColor(accent)
        }
        setupInstallPamInstallStart.run {
            setTextColor(accent)
            iconTint = ColorStateList.valueOf(accent)
            overrideRippleColor(accent)
        }
    }

    private fun setupRetry() = whenResumed {
        binding.setupInstallPamRetry.onClicked().collect {
            viewModel.restartDownload()
        }
    }

    private fun setupInstall() = whenResumed {
        binding.setupInstallPamInstallStart.onClicked().collect {
            viewModel.onInstallClicked()
        }
    }

    private fun setupLocalApkFallback() = whenResumed {
        binding.setupInstallPamLocalApk.onClicked().collect {
            localApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
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
            is State.Loading -> showLoading()
            is State.Downloading -> showDownloading(state)
            is State.Error -> showError(state.reason)
            is State.DownloadComplete -> showDownloadComplete()
            is State.Installed -> {
                showLoading()
                viewModel.moveToNext()
            }
        }
    }

    private fun showLoading() = with(binding) {
        setupInstallPamLoading.isVisible = true
        setupInstallPamError.isVisible = false
        setupInstallPamInstall.isVisible = false
        setupInstallPamLocalApk.isVisible = true
        setupInstallPamLoadingProgress.isIndeterminate = true
        setupInstallPamLoadingLabel.setText(R.string.setup_install_pam_loading)
        setupInstallPamLoadingSubtitle.text = ""
    }

    private fun showDownloading(downloading: State.Downloading) = with(binding) {
        setupInstallPamLoading.isVisible = true
        setupInstallPamError.isVisible = false
        setupInstallPamInstall.isVisible = false
        setupInstallPamLocalApk.isVisible = true
        setupInstallPamLoadingProgress.isIndeterminate = downloading.progress <= 1.0
        setupInstallPamLoadingProgress.progress = downloading.progress.roundToInt()
        setupInstallPamLoadingLabel.setText(R.string.setup_install_pam_downloading)
        setupInstallPamLoadingSubtitle.setText(R.string.setup_install_pam_downloading_subtitle)
    }

    private fun showError(reason: String) = with(binding) {
        setupInstallPamLoading.isVisible = false
        setupInstallPamError.isVisible = true
        setupInstallPamInstall.isVisible = false
        setupInstallPamLocalApk.isVisible = true
        setupInstallPamErrorLabelSub.text = getString(R.string.setup_install_pam_error_subtitle_detailed, reason)
    }

    private fun showDownloadComplete() = with(binding) {
        setupInstallPamLoading.isVisible = false
        setupInstallPamError.isVisible = false
        setupInstallPamInstall.isVisible = true
        setupInstallPamLocalApk.isVisible = true
    }

}
