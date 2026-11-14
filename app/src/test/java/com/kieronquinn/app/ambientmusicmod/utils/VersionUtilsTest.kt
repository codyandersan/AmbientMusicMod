package com.kieronquinn.app.ambientmusicmod.utils

import com.kieronquinn.app.ambientmusicmod.model.github.GitHubRelease
import com.kieronquinn.app.ambientmusicmod.model.update.isApk
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VersionUtilsTest {
    @Test fun tagWithVPrefixMatches() {
        assertTrue(VersionUtils.isSameVersion("v1.3.5", "1.3.5"))
        assertTrue(VersionUtils.isSameVersion("1.3.5", "1.3.5"))
        assertFalse(VersionUtils.isSameVersion("1.3.6", "1.3.5"))
    }

    @Test fun apkAssetDetection() {
        fun asset(name: String, type: String) = Gson().fromJson(
            """{"name":"$name","content_type":"$type","browser_download_url":"https://x/$name"}""",
            GitHubRelease.Asset::class.java
        )
        assertTrue(asset("a.apk", "application/octet-stream").isApk())
        assertTrue(asset("a", "application/vnd.android.package-archive").isApk())
        assertFalse(asset("a.zip", "application/zip").isApk())
    }
}
