package com.kieronquinn.app.ambientmusicmod.ui.screens.setup.installpam

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.components.navigation.SetupNavigation
import com.kieronquinn.app.ambientmusicmod.model.update.toRelease
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository.UpdateState
import com.kieronquinn.app.ambientmusicmod.utils.extensions.broadcastReceiverAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.contentReceiverAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.isAppInstalled
import com.kieronquinn.app.ambientmusicmod.utils.extensions.onPackageChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

abstract class SetupInstallPAMViewModel: ViewModel() {

    abstract val state: StateFlow<State>

    abstract fun restartDownload()
    abstract fun moveToNext()
    abstract fun onInstallClicked()
    abstract fun onLocalApkSelected(uri: Uri)

    sealed class State {
        object Loading: State()
        data class Downloading(val progress: Double): State()
        data class Error(val reason: String): State()
        object DownloadComplete: State()
        object Installed: State()
    }

}

class SetupInstallPAMViewModelImpl(
    private val context: Context,
    private val updatesRepository: UpdatesRepository,
    private val navigation: SetupNavigation
): SetupInstallPAMViewModel() {

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val getUriForFile = { downloadFile: File ->
        FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", downloadFile)
    }

    private val downloadManagerTitle = context.getString(R.string.app_name)
    private val downloadManagerDescription = context.getString(R.string.download_manager_description_initial)

    private val downloadFolder = updatesRepository.getUpdatesFolder(context).apply {
        updatesRepository.clearUpdatesFolder(this)
    }

    private val downloadBus = MutableStateFlow(System.currentTimeMillis())
    private val downloadId = MutableStateFlow<Pair<Long, String>?>(null)
    private val localApkError = MutableStateFlow<String?>(null)
    private val localApkReadyFile = MutableStateFlow<String?>(null)

    private val updateState = downloadBus.mapLatest {
        localApkError.value = null
        updatesRepository.getPAMUpdateState(true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null).onEach {
        val release = when(it){
            is UpdateState.NotInstalled -> it.release.toRelease("", "")
            is UpdateState.UpdateAvailable -> it.release.toRelease("", "")
            else -> null
        }
        if (release != null) {
            queueDownload(release.downloadUrl, release.fileName)
        } else if (it is UpdateState.NotInstalled || it is UpdateState.UpdateAvailable) {
            localApkError.value = "Release found on GitHub (${BuildConfig.GITHUB_OWNER}/NowPlaying), but no .apk asset was attached."
        }
    }.flowOn(Dispatchers.IO)

    private val downloadProgress = combine(
        downloadId,
        context.contentReceiverAsFlow(Uri.parse("content://downloads/my_downloads"))
    ) { id, _ ->
        getDownloadProgress(id?.first ?: return@combine null)
    }.flowOn(Dispatchers.IO)

    private val downloadComplete = downloadId.flatMapLatest {
        flow {
            emit(null)
            if(it == null) return@flow
            context.broadcastReceiverAsFlow(DownloadManager.ACTION_DOWNLOAD_COMPLETE).first()
            emit(getDownloadSuccess(it.first))
        }
    }.flowOn(Dispatchers.IO)

    private val installComplete = context.onPackageChanged(PACKAGE_NAME_PAM, true).map {
        context.packageManager.isAppInstalled(PACKAGE_NAME_PAM)
    }

    override val state = combine(
        updateState,
        downloadProgress,
        downloadComplete,
        installComplete,
        combine(localApkError, localApkReadyFile) { err, ready -> Pair(err, ready) }
    ) { update, progress, complete, installed, localPair ->
        val localErr = localPair.first
        val localReady = localPair.second
        if (installed) return@combine State.Installed
        if (localReady != null) return@combine State.DownloadComplete
        if (localErr != null) return@combine State.Error(localErr)
        if (update == null) return@combine State.Loading
        if (update is UpdateState.UpToDate) return@combine State.Installed
        if (update is UpdateState.FailedToFetchInitial) return@combine State.Error(update.errorMessage)
        if (update is UpdateState.FailedToFetchUpdate) return@combine State.Error(update.errorMessage)
        complete?.let {
            return@combine if(it) State.DownloadComplete else State.Error("DownloadManager failed to download Now Playing APK.")
        }
        progress?.let {
            return@combine State.Downloading(it)
        }
        State.Loading
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State.Loading)

    private fun getDownloadProgress(requestId: Long): Double {
        val query = DownloadManager.Query()
        query.setFilterById(requestId)
        val c: Cursor = downloadManager.query(query)
        var progress = 0.0
        if (c.moveToFirst()) {
            val sizeIndex: Int = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloadedIndex: Int =
                c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val size = c.getInt(sizeIndex)
            val downloaded = c.getInt(downloadedIndex)
            if (size != -1) progress = downloaded * 100.0 / size
        }
        c.close()
        return progress
    }

    private fun getDownloadSuccess(requestId: Long): Boolean {
        var success = false
        val query = DownloadManager.Query().apply {
            setFilterById(requestId)
        }
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (cursor.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                success = true
            }
        }
        cursor.close()
        return success
    }

    override fun restartDownload() {
        viewModelScope.launch {
            localApkError.value = null
            localApkReadyFile.value = null
            downloadBus.emit(System.currentTimeMillis())
        }
    }

    private suspend fun queueDownload(url: String, fileName: String) {
        val downloadFile = File(downloadFolder, fileName)
        val requestId = downloadManager.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(downloadManagerTitle)
            setDescription(downloadManagerDescription)
            setDestinationUri(Uri.fromFile(downloadFile))
        })
        downloadId.emit(Pair(requestId, fileName))
    }

    override fun moveToNext() {
        viewModelScope.launch {
            navigation.navigate(SetupInstallPAMFragmentDirections.actionSetupInstallPAMFragmentToSetupPermissionsFragment())
        }
    }

    override fun onInstallClicked() {
        val fileName = localApkReadyFile.value ?: downloadId.value?.second ?: return
        val file = File(downloadFolder, fileName)
        if(!file.exists()) return
        val uri = getUriForFile(file)
        viewModelScope.launch {
            Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.also {
                navigation.navigate(it)
            }
        }
    }

    override fun onLocalApkSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetName = "NowPlaying-local.apk"
                val destFile = File(downloadFolder, targetName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                } ?: throw IllegalStateException("Cannot read selected APK file")
                if (!destFile.exists() || destFile.length() == 0L) {
                    throw IllegalStateException("Selected APK file is empty")
                }
                localApkReadyFile.value = targetName
                withContext(Dispatchers.Main) {
                    onInstallClicked()
                }
            } catch (e: Exception) {
                localApkError.value = "Failed to load local APK: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadId.value?.let {
            downloadManager.remove(it.first)
        }
        updatesRepository.clearUpdatesFolder(downloadFolder)
    }

}
