package com.kieronquinn.app.ambientmusicmod.utils

object VersionUtils {

    /** "v1.3.5" and "1.3.5" are the same version; tags are often prefixed with "v". */
    fun normalise(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V")
    }

    fun isSameVersion(remoteTag: String, localVersionName: String): Boolean {
        return normalise(remoteTag) == normalise(localVersionName)
    }

}
