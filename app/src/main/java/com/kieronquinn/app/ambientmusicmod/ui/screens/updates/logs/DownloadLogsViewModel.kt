package com.kieronquinn.app.ambientmusicmod.ui.screens.updates.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.ambientmusicmod.repositories.DownloadLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class DownloadLogsViewModel : ViewModel() {

    abstract val state: StateFlow<State>
    abstract fun refreshLogs()
    abstract fun clearLogs()

    sealed class State {
        object Loading : State()
        data class Loaded(
            val logLines: List<String>,
            val fullText: String
        ) : State() {
            override fun equals(other: Any?): Boolean = false
        }
    }
}

class DownloadLogsViewModelImpl(
    private val downloadLogRepository: DownloadLogRepository
) : DownloadLogsViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    override val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshLogs()
    }

    override fun refreshLogs() {
        viewModelScope.launch {
            downloadLogRepository.refreshFromBridge()
            val full = downloadLogRepository.getFullLogText()
            val lines = full.lines().filter { it.isNotBlank() }
            _state.value = State.Loaded(lines, full)
        }
    }

    override fun clearLogs() {
        viewModelScope.launch {
            downloadLogRepository.clearLogs()
            refreshLogs()
        }
    }
}
