package com.kieronquinn.app.ambientmusicmod.ui.screens.updates.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItem
import com.kieronquinn.app.ambientmusicmod.model.settings.BaseSettingsItemType
import com.kieronquinn.app.ambientmusicmod.repositories.DownloadDiagnosticsRepository
import com.kieronquinn.app.ambientmusicmod.repositories.DownloadDiagnosticsRepository.DiagnosticCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class DownloadDiagnosticsViewModel : ViewModel() {

    abstract val state: StateFlow<State>
    abstract fun runDiagnostics()

    sealed class State {
        object Loading : State()
        data class Loaded(
            val isRunning: Boolean,
            val checks: List<DiagnosticCheck>
        ) : State() {
            override fun equals(other: Any?): Boolean = false
        }
    }

    sealed class DiagnosticsSettingsItem(val type: ItemType) : BaseSettingsItem(type) {
        enum class ItemType : BaseSettingsItemType {
            DUMMY
        }
    }
}

class DownloadDiagnosticsViewModelImpl(
    private val diagnosticsRepository: DownloadDiagnosticsRepository
) : DownloadDiagnosticsViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    override val state: StateFlow<State> = _state.asStateFlow()

    private var runJob: Job? = null

    init {
        runDiagnostics()
    }

    override fun runDiagnostics() {
        runJob?.cancel()
        runJob = viewModelScope.launch {
            diagnosticsRepository.runAllDiagnostics().collect { checks ->
                val anyRunning = checks.any {
                    it.status == DownloadDiagnosticsRepository.CheckStatus.RUNNING ||
                        it.status == DownloadDiagnosticsRepository.CheckStatus.PENDING
                }
                _state.value = State.Loaded(anyRunning, checks)
            }
        }
    }
}
