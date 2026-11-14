package com.kieronquinn.app.ambientmusicmod.model.shards

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Represents the Superpacks manifest JSON format used by Now Playing's ambientmusic-index-17_09_02:
 * {
 *   "packs": [
 *     {
 *       "name": "matcher_tah.leveldb" or "in_...",
 *       "size": 56789012,
 *       "compressed_size": 56789012,
 *       "download_urls": ["https://storage.googleapis.com/music-iq-db/..."],
 *       "extra_type": "core" | "shard",
 *       "extra_country_codes": "in" | "us,xa" | ... (absent on core)
 *     }
 *   ]
 * }
 */
data class SuperpacksManifest(
    @SerializedName("packs")
    val packs: List<Pack> = emptyList()
) {
    data class Pack(
        @SerializedName("name")
        val name: String,
        @SerializedName("size")
        val size: Long,
        @SerializedName("compressed_size")
        val compressedSize: Long = size,
        @SerializedName("download_urls")
        val downloadUrls: List<String> = emptyList(),
        @SerializedName("extra_type")
        val extraType: String? = null,
        @SerializedName("extra_country_codes")
        val extraCountryCodes: String? = null
    ) {
        val primaryDownloadUrl: String?
            get() = downloadUrls.firstOrNull { it.startsWith("https://") } ?: downloadUrls.firstOrNull()

        val lastUrlPathSegment: String?
            get() = primaryDownloadUrl?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

        fun isCorePack(): Boolean {
            return extraType.equals("core", ignoreCase = true) ||
                extraCountryCodes.isNullOrBlank() ||
                name == "matcher_tah.leveldb" ||
                primaryDownloadUrl?.endsWith("/tree_core_shard") == true
        }

        /**
         * Matches a pack against the set of selected country codes (e.g. ["in", "us,xa", "us"]).
         * Handles "us,xa" as a single value as well as individual comma-separated tokens.
         */
        fun matchesCountryCodes(selectedCodes: Set<String>): Boolean {
            if (isCorePack()) return true
            val rawCodes = extraCountryCodes?.trim()?.lowercase() ?: return false
            val normalizedSelected = selectedCodes.flatMap { code ->
                val lower = code.trim().lowercase()
                if (lower.contains(",")) {
                    listOf(lower) + lower.split(",").map { it.trim() }
                } else {
                    listOf(lower)
                }
            }.filter { it.isNotEmpty() }.toSet()

            if (normalizedSelected.contains(rawCodes)) return true
            val packTokens = rawCodes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return packTokens.any { normalizedSelected.contains(it) }
        }

        /**
         * Returns all candidate cache/import filenames that identify this pack:
         * both the pack's `name` and the last path segment of its download URL
         * (e.g., `tree_core_shard` and `matcher_tah.leveldb` for core, or shard name).
         */
        fun candidateFileNames(): Set<String> {
            val names = LinkedHashSet<String>()
            if (name.isNotBlank()) names.add(name)
            lastUrlPathSegment?.let { names.add(it) }
            return names
        }
    }

    /**
     * Filters the manifest to ONLY the core pack plus shards for the selected primary
     * and extra countries. Never returns all 460 packs unless all countries were somehow passed,
     * and enforces a hard guard against empty country selection by requiring at least 1 country.
     */
    fun selectPacksForCountries(selectedCountryCodes: Collection<String>): List<Pack> {
        val codeSet = selectedCountryCodes.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return packs.filter { pack ->
            if (pack.isCorePack()) {
                true
            } else {
                codeSet.isNotEmpty() && pack.matchesCountryCodes(codeSet)
            }
        }
    }

    companion object {
        fun parse(json: String, gson: Gson = Gson()): SuperpacksManifest {
            val parsed = gson.fromJson(json, SuperpacksManifest::class.java)
                ?: throw IllegalArgumentException("Manifest JSON parsed to null")
            if (parsed.packs.isEmpty()) {
                throw IllegalArgumentException("Manifest contains 0 packs")
            }
            parsed.packs.forEachIndexed { index, pack ->
                if (pack.name.isBlank()) {
                    throw IllegalArgumentException("Pack at index $index has blank name")
                }
                if (pack.size <= 0L) {
                    throw IllegalArgumentException("Pack '${pack.name}' has invalid size ${pack.size}")
                }
                if (pack.primaryDownloadUrl.isNullOrBlank()) {
                    throw IllegalArgumentException("Pack '${pack.name}' has no download_urls")
                }
            }
            return parsed
        }
    }
}
