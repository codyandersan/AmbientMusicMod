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

private val ACCEPTED_CONTENT_TYPES = setOf(
    "application/vnd.android.package-archive",
    "application/octet-stream",
    "application/zip",
    "binary/octet-stream"
)

fun GitHubRelease.toRelease(title: String, localVersion: String?): Release? {
    val resolvedTag = tag ?: return null
    val resolvedVersionName = versionName?.takeIf { it.isNotBlank() } ?: resolvedTag
    val asset = assets?.firstOrNull { candidate ->
        val isApkName = candidate.fileName?.endsWith(".apk", ignoreCase = true) == true
        val isApkContentType = candidate.contentType?.lowercase() == "application/vnd.android.package-archive"
        val isGenericTypeWithApkExt = isApkName && (candidate.contentType == null || ACCEPTED_CONTENT_TYPES.contains(candidate.contentType.lowercase()))
        isApkContentType || isGenericTypeWithApkExt || isApkName
    } ?: return null
    val downloadUrl = asset.downloadUrl ?: return null
    val fileName = asset.fileName ?: return null
    val gitHubUrl = gitHubUrl ?: "https://github.com"
    val body = body ?: ""
    return Release(title, resolvedTag, resolvedVersionName, localVersion, downloadUrl, fileName, gitHubUrl, body)
}
