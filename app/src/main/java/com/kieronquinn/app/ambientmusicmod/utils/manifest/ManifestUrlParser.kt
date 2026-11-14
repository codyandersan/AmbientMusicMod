package com.kieronquinn.app.ambientmusicmod.utils.manifest

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parses and normalizes index manifest strings used in DeviceConfig (`<version>:<https_url>`)
 * as well as manual raw HTTPS manifest URLs entered by the user.
 */
object ManifestUrlParser {

    private val STANDARD_REGEXES = listOf(
        "^(\\d+):(https://storage\\.googleapis\\.com/music-iq-db/updatable_db_v3/(\\d{8})-(\\d{6})/manifest\\.json)$".toRegex(),
        "^(\\d+):(https://storage\\.googleapis\\.com/music-iq-db/updatable_ytm_db/(\\d{8})-(\\d{6})/manifest\\.json)$".toRegex()
    )

    private val RAW_URL_WITH_DATE_REGEX =
        "^https://storage\\.googleapis\\.com/music-iq-db/[^/]+/(\\d{8})-(\\d{6})/manifest\\.json$".toRegex()

    private val GENERIC_PREFIXED_URL_REGEX =
        "^(\\d+):(https://.+)$".toRegex()

    data class ParsedManifestInfo(
        val versionCode: Int,
        val date: LocalDateTime,
        val httpsUrl: String,
        val deviceConfigValue: String
    )

    fun parse(input: String, defaultVersionCode: Int = 3099): ParsedManifestInfo? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        for (regex in STANDARD_REGEXES) {
            val match = regex.find(trimmed)
            if (match != null) {
                val version = match.groupValues[1].toIntOrNull() ?: defaultVersionCode
                val url = match.groupValues[2]
                val datePart = match.groupValues[3]
                val timePart = match.groupValues[4]
                val dateTime = parseDateTime(datePart, timePart)
                return ParsedManifestInfo(version, dateTime, url, "$version:$url")
            }
        }

        val genericMatch = GENERIC_PREFIXED_URL_REGEX.find(trimmed)
        if (genericMatch != null) {
            val version = genericMatch.groupValues[1].toIntOrNull() ?: defaultVersionCode
            val url = genericMatch.groupValues[2].trim()
            if (!url.startsWith("https://")) return null
            val dateMatch = RAW_URL_WITH_DATE_REGEX.find(url)
            val dateTime = if (dateMatch != null) {
                parseDateTime(dateMatch.groupValues[1], dateMatch.groupValues[2])
            } else {
                LocalDateTime.of(2024, 9, 1, 3, 0, 29)
            }
            return ParsedManifestInfo(version, dateTime, url, "$version:$url")
        }

        if (trimmed.startsWith("https://")) {
            val dateMatch = RAW_URL_WITH_DATE_REGEX.find(trimmed)
            val dateTime = if (dateMatch != null) {
                parseDateTime(dateMatch.groupValues[1], dateMatch.groupValues[2])
            } else {
                LocalDateTime.of(2024, 9, 1, 3, 0, 29)
            }
            return ParsedManifestInfo(defaultVersionCode, dateTime, trimmed, "$defaultVersionCode:$trimmed")
        }

        return null
    }

    fun extractHttpsUrl(input: String): String {
        val trimmed = input.trim()
        val colonIdx = trimmed.indexOf(":https://")
        return if (colonIdx >= 0) {
            trimmed.substring(colonIdx + 1)
        } else {
            trimmed
        }
    }

    private fun parseDateTime(date: String, time: String): LocalDateTime {
        return try {
            val parsed = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneId.systemDefault())
                .parse(date + time)
            LocalDateTime.ofInstant(Instant.from(parsed), ZoneId.systemDefault())
        } catch (e: Exception) {
            LocalDateTime.of(2024, 9, 1, 3, 0, 29)
        }
    }
}
