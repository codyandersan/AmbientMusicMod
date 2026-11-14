package com.kieronquinn.app.ambientmusicmod.model.update

import android.os.Parcelable
import com.kieronquinn.app.ambientmusicmod.model.github.GitHubRelease
import kotlinx.parcelize.Parcelize

@Parcelize
data class Release(
    val title: String,
    val tag: String,
    val versionName: String,
    val installedVersion: String?,
    val downloadUrl: String,
    val fileName: String,
    val gitHubUrl: String,
    val body: String
) : Parcelable

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"

/**
 *  GitHub derives an asset's content_type from the upload request, so an APK can legitimately
 *  come back as octet-stream/zip. Accept the APK type, or any asset named *.apk.
 */
fun GitHubRelease.Asset.isApk(): Boolean {
    return contentType == CONTENT_TYPE_APK || fileName?.endsWith(".apk", ignoreCase = true) == true
}

fun GitHubRelease.toRelease(title: String, localVersion: String?): Release? {
    val tag = tag ?: return null
    //GitHub omits release name/body when empty, so fall back instead of rejecting the release
    val versionName = versionName?.takeIf { it.isNotBlank() } ?: tag
    val asset = assets?.firstOrNull { it.isApk() } ?: return null
    val downloadUrl = asset.downloadUrl ?: return null
    val fileName = asset.fileName ?: return null
    val gitHubUrl = gitHubUrl ?: return null
    val body = body ?: ""
    return Release(title, tag, versionName, localVersion, downloadUrl, fileName, gitHubUrl, body)
}
