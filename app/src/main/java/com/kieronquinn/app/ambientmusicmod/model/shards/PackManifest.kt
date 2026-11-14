package com.kieronquinn.app.ambientmusicmod.model.shards

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 *  Format of the Superpacks manifest.json, verified against the real file:
 *  {"packs":[{name,size,compressed_size,download_urls:[one https url],
 *  extra_type:"core"|"shard", extra_country_codes:"in"|"us,xa" (absent on core)}]}
 *  There are no checksums, only sizes.
 */
data class PackManifest(
    @SerializedName("packs") val packs: List<Pack>?
) {
    data class Pack(
        @SerializedName("name") val name: String?,
        @SerializedName("size") val size: Long?,
        @SerializedName("compressed_size") val compressedSize: Long?,
        @SerializedName("download_urls") val downloadUrls: List<String>?,
        @SerializedName("extra_type") val extraType: String?,
        @SerializedName("extra_country_codes") val extraCountryCodes: String?
    )
}

/** A validated pack. [fileName] is the last URL path segment, which is how files are matched. */
data class ValidPack(
    val name: String,
    val url: String,
    val fileName: String,
    val size: Long,
    val compressedSize: Long?,
    val isCore: Boolean,
    val countryCodes: String?
)

sealed class ManifestParseResult {
    data class Success(val packs: List<ValidPack>, val warnings: List<String>): ManifestParseResult()
    data class Failure(val reason: String): ManifestParseResult()
}

object PackManifestParser {

    private val gson = Gson()

    fun parse(json: String): ManifestParseResult {
        val manifest = try {
            gson.fromJson(json, PackManifest::class.java)
        } catch (e: JsonSyntaxException) {
            return ManifestParseResult.Failure("Not valid JSON: ${e.message}")
        } catch (e: Exception) {
            return ManifestParseResult.Failure("Parse error: ${e.javaClass.simpleName}: ${e.message}")
        } ?: return ManifestParseResult.Failure("Empty manifest")
        val packs = manifest.packs
            ?: return ManifestParseResult.Failure("Manifest has no \"packs\" array")
        if (packs.isEmpty()) return ManifestParseResult.Failure("Manifest \"packs\" array is empty")
        val warnings = ArrayList<String>()
        val valid = ArrayList<ValidPack>()
        packs.forEachIndexed { index, pack ->
            val name = pack.name
            if (name.isNullOrBlank()) {
                warnings += "Pack #$index skipped: no name"; return@forEachIndexed
            }
            val urls = pack.downloadUrls.orEmpty()
            if (urls.size != 1) {
                warnings += "$name skipped: expected exactly 1 download url, found ${urls.size}"
                return@forEachIndexed
            }
            val url = urls[0]
            if (!url.startsWith("https://")) {
                warnings += "$name skipped: url is not https ($url)"; return@forEachIndexed
            }
            val fileName = url.substringBefore('?').substringAfterLast('/')
            if (fileName.isBlank()) {
                warnings += "$name skipped: cannot derive file name from $url"
                return@forEachIndexed
            }
            val size = pack.size
            if (size == null || size <= 0) {
                warnings += "$name skipped: missing or invalid size"; return@forEachIndexed
            }
            valid += ValidPack(
                name, url, fileName, size, pack.compressedSize,
                pack.extraType == "core", pack.extraCountryCodes
            )
        }
        if (valid.isEmpty()) {
            return ManifestParseResult.Failure(
                "No usable packs. ${warnings.take(3).joinToString("; ")}"
            )
        }
        return ManifestParseResult.Success(valid, warnings)
    }

    /**
     *  Core pack plus shards whose extra_country_codes equals one of the selected country codes
     *  (ShardCountry.code, eg "gb" or "us,xa"). INFERRED: the real selection is made inside the
     *  closed-source Android System Intelligence code, which is not in the repository.
     */
    fun select(packs: List<ValidPack>, countryCodes: Collection<String>): List<ValidPack> {
        val wanted = countryCodes.map { it.lowercase() }.toSet()
        return packs.filter {
            it.isCore || (it.countryCodes?.lowercase() in wanted)
        }
    }

}

/** Parsing for the "VERSION:https://.../manifest.json" value stored in the DeviceConfig flag. */
object ManifestFlag {

    private val REGEXES = listOf(
        "(\\d+):(https://storage\\.googleapis\\.com/music-iq-db/updatable_db_v3/(\\d{8})-(\\d{6})/manifest\\.json)".toRegex(),
        "(\\d+):(https://storage\\.googleapis\\.com/music-iq-db/updatable_ytm_db/(\\d{8})-(\\d{6})/manifest\\.json)".toRegex()
    )

    data class Parsed(val version: Int, val url: String)

    /** Returns null if the value would make ShardsRepository.getUrlData throw. */
    fun parse(value: String): Parsed? {
        val v = value.trim()
        REGEXES.forEach {
            val m = it.matchEntire(v) ?: return@forEach
            return Parsed(m.groupValues[1].toInt(), m.groupValues[2])
        }
        return null
    }
}
