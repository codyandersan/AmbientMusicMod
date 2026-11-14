package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.util.Log
import com.google.android.as.oss.http.service.AstreaCacheBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface DownloadLogRepository {
    val logs: StateFlow<List<String>>
    fun logInfo(tag: String, message: String)
    fun logWarn(tag: String, message: String, throwable: Throwable? = null)
    fun logError(tag: String, message: String, throwable: Throwable? = null)
    suspend fun getFullLogText(): String
    suspend fun clearLogs()
    fun refreshFromBridge()
}

class DownloadLogRepositoryImpl(
    context: Context
) : DownloadLogRepository {

    private val logFile = File(context.filesDir, "logs/amm_download_diagnostics.log").apply {
        parentFile?.mkdirs()
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        AstreaCacheBridge.setLogFile(logFile)
        loadInitialLogs()
    }

    private fun loadInitialLogs() {
        try {
            if (logFile.exists()) {
                val tail = logFile.readLines().takeLast(500)
                _logs.value = tail
            }
        } catch (e: Exception) {
            Log.w("DownloadLogRepo", "Failed to read initial log file", e)
        }
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
        AstreaCacheBridge.appendLog(tag, "INFO: $message")
        refreshFromBridge()
    }

    override fun logWarn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
        val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        AstreaCacheBridge.appendLog(tag, "WARN: $message$suffix")
        refreshFromBridge()
    }

    override fun logError(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        AstreaCacheBridge.appendLog(tag, "ERROR: $message$suffix")
        refreshFromBridge()
    }

    override fun refreshFromBridge() {
        val bridgeLogs = AstreaCacheBridge.getMemoryLogs()
        if (bridgeLogs.isNotEmpty()) {
            _logs.value = bridgeLogs
        } else if (logFile.exists()) {
            try {
                _logs.value = logFile.readLines().takeLast(500)
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun getFullLogText(): String = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                val lines = logFile.readLines()
                if (lines.isNotEmpty()) {
                    return@withContext lines.takeLast(1500).joinToString("\n")
                }
            }
            val mem = AstreaCacheBridge.getMemoryLogs()
            if (mem.isNotEmpty()) {
                mem.joinToString("\n")
            } else {
                "No download log entries recorded yet."
            }
        } catch (e: Exception) {
            "Failed to read download logs: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override suspend fun clearLogs() = withContext(Dispatchers.IO) {
        AstreaCacheBridge.clearLogs()
        _logs.value = emptyList()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        logInfo("DownloadLog", "Logs cleared at ${sdf.format(Date())}")
    }
}
