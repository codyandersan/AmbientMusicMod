package com.kieronquinn.app.ambientmusicmod.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.components.notifications.NotificationChannel
import com.kieronquinn.app.ambientmusicmod.components.notifications.NotificationId
import com.kieronquinn.app.ambientmusicmod.components.notifications.createNotification
import com.kieronquinn.app.ambientmusicmod.repositories.DownloadLogRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsCacheRepository
import com.kieronquinn.app.ambientmusicmod.repositories.ShardsCacheRepository.DatabaseDownloadState
import com.kieronquinn.app.ambientmusicmod.ui.activities.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phase 1 Foreground Service for downloading and caching Superpacks shards in AMM.
 *
 * Uses `specialUse` foreground service type (with `FOREGROUND_SERVICE_SPECIAL_USE` and
 * `FOREGROUND_SERVICE_DATA_SYNC` declared in AndroidManifest.xml) so it is not killed by
 * Android 14/15's 6-hour `dataSync` timeout limit, and also overrides `onTimeout(startId, fgsType)`
 * for API 34/35 to gracefully checkpoint `.part` files and reschedule/stop cleanly if the OS
 * ever signals a timeout.
 */
class ShardsDownloadForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "ShardsDownloadFGS"
        const val ACTION_START_DOWNLOAD = "com.kieronquinn.app.ambientmusicmod.action.START_DB_DOWNLOAD"
        const val ACTION_FORCE_DOWNLOAD = "com.kieronquinn.app.ambientmusicmod.action.FORCE_DB_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.kieronquinn.app.ambientmusicmod.action.CANCEL_DB_DOWNLOAD"

        fun start(context: Context, force: Boolean = false) {
            val intent = Intent(context, ShardsDownloadForegroundService::class.java).apply {
                action = if (force) ACTION_FORCE_DOWNLOAD else ACTION_START_DOWNLOAD
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ShardsDownloadForegroundService", e)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ShardsDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val shardsCacheRepository by inject<ShardsCacheRepository>()
    private val downloadLogRepository by inject<DownloadLogRepository>()

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var downloadJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                shardsCacheRepository.cancelActiveDownload()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FORCE_DOWNLOAD -> {
                startForegroundWithNotification(
                    buildProgressNotification("Preparing forced music database download...", 0, 100, true)
                )
                runDownload(force = true)
            }
            else -> {
                startForegroundWithNotification(
                    buildProgressNotification("Preparing music database download...", 0, 100, true)
                )
                runDownload(force = false)
            }
        }
        return START_REDELIVER_INTENT
    }

    /**
     * Handles Android 14 (API 34) / Android 15 (API 35) foreground service timeout callback
     * (e.g., if `dataSync` time limit is reached). All progress is already flushed to `.part`
     * files, so we cancel cleanly and stop the service before the OS throws an ANR/RemoteServiceException.
     */
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleServiceTimeout("onTimeout(startId=$startId)")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleServiceTimeout("onTimeout(startId=$startId, fgsType=$fgsType)")
    }

    private fun handleServiceTimeout(source: String) {
        downloadLogRepository.logWarn(
            TAG,
            "Foreground service time limit reached ($source); checkpointing .part files and stopping gracefully."
        )
        shardsCacheRepository.cancelActiveDownload()
        stopForegroundCompat()
        stopSelf()
    }

    private fun runDownload(force: Boolean) {
        if (downloadJob?.isActive == true && !force) return
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            shardsCacheRepository.executeResumableDownload(force = force) { state ->
                updateNotificationForState(state)
            }
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun updateNotificationForState(state: DatabaseDownloadState) {
        when (state) {
            is DatabaseDownloadState.Preparing -> {
                val n = buildProgressNotification("Fetching manifest...", 0, 100, true)
                notificationManager.notify(NotificationId.SHARDS_DOWNLOAD.ordinal, n)
            }
            is DatabaseDownloadState.Downloading -> {
                val percent = if (state.bytesTotal > 0) {
                    ((state.bytesDone * 100.0) / state.bytesTotal).roundToInt().coerceIn(0, 100)
                } else 0
                val mbDone = state.bytesDone / (1024.0 * 1024.0)
                val mbTotal = state.bytesTotal / (1024.0 * 1024.0)
                val speedKbps = state.speedBytesPerSec / 1024.0
                val etaText = if (state.etaSeconds >= 0) "${state.etaSeconds}s" else "--"
                val subtitle = if (state.isStuck) {
                    "Stuck: ${state.stuckReason ?: "Waiting for network"}"
                } else {
                    String.format(
                        Locale.US,
                        "Packs %d/%d • %.1f/%.1f MB (%.0f KB/s, ETA %s)",
                        state.packsDone,
                        state.packsTotal,
                        mbDone,
                        mbTotal,
                        speedKbps,
                        etaText
                    )
                }
                val n = buildProgressNotification(subtitle, percent, 100, false)
                notificationManager.notify(NotificationId.SHARDS_DOWNLOAD.ordinal, n)
            }
            is DatabaseDownloadState.Completed -> {
                val mbTotal = state.bytesTotal / (1024.0 * 1024.0)
                val n = buildOutcomeNotification(
                    title = getString(R.string.notification_shards_download_complete_title),
                    subtitle = String.format(
                        Locale.US,
                        "Cached %d packs (%.1f MB) ready for Now Playing",
                        state.packsTotal,
                        mbTotal
                    )
                )
                notificationManager.notify(NotificationId.SHARDS_DOWNLOAD.ordinal, n)
            }
            is DatabaseDownloadState.Failed -> {
                val n = buildOutcomeNotification(
                    title = getString(R.string.notification_shards_download_failed_title),
                    subtitle = state.errorSummary
                )
                notificationManager.notify(NotificationId.SHARDS_DOWNLOAD.ordinal, n)
            }
            is DatabaseDownloadState.PausedOrBlocked -> {
                val n = buildOutcomeNotification(
                    title = getString(R.string.notification_shards_download_paused_title),
                    subtitle = state.reason
                )
                notificationManager.notify(NotificationId.SHARDS_DOWNLOAD.ordinal, n)
            }
            is DatabaseDownloadState.Idle -> {}
        }
    }

    private fun buildProgressNotification(
        subtitle: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val cancelIntent = PendingIntent.getService(
            this,
            2001,
            Intent(this, ShardsDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return createNotification(NotificationChannel.UPDATES) {
            it.setContentTitle(getString(R.string.notification_shards_download_title))
            it.setContentText(subtitle)
            it.setSmallIcon(R.drawable.ic_download)
            it.setOngoing(true)
            it.setOnlyAlertOnce(true)
            it.setProgress(max, progress, indeterminate)
            it.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NotificationId.SHARDS_DOWNLOAD.ordinal,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            it.addAction(
                R.drawable.ic_search_clear,
                getString(android.R.string.cancel),
                cancelIntent
            )
        }
    }

    private fun buildOutcomeNotification(title: String, subtitle: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        return createNotification(NotificationChannel.UPDATES) {
            it.setContentTitle(title)
            it.setContentText(subtitle)
            it.setSmallIcon(R.drawable.ic_music_database)
            it.setOngoing(false)
            it.setAutoCancel(true)
            it.setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            it.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NotificationId.SHARDS_DOWNLOAD.ordinal,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationId.SHARDS_DOWNLOAD.ordinal,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationId.SHARDS_DOWNLOAD.ordinal, notification)
            }
        } catch (e: Exception) {
            downloadLogRepository.logError(TAG, "Failed to enter foreground state", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {
        }
    }
}
