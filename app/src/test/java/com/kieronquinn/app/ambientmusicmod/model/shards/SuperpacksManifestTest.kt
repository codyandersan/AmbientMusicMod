package com.kieronquinn.app.ambientmusicmod.model.shards

import com.kieronquinn.app.ambientmusicmod.utils.manifest.ManifestUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class SuperpacksManifestTest {

    private val sampleManifestJson = """
        {
          "packs": [
            {
              "name": "matcher_tah.leveldb",
              "size": 56623104,
              "compressed_size": 56623104,
              "download_urls": [
                "https://storage.googleapis.com/music-iq-db/static/tree_core_shard"
              ],
              "extra_type": "core"
            },
            {
              "name": "in_shard_00.leveldb",
              "size": 9437184,
              "compressed_size": 9437184,
              "download_urls": [
                "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/in_shard_00.leveldb"
              ],
              "extra_type": "shard",
              "extra_country_codes": "in"
            },
            {
              "name": "us_shard_00.leveldb",
              "size": 10485760,
              "compressed_size": 10485760,
              "download_urls": [
                "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/us_shard_00.leveldb"
              ],
              "extra_type": "shard",
              "extra_country_codes": "us,xa"
            },
            {
              "name": "gb_shard_00.leveldb",
              "size": 8388608,
              "compressed_size": 8388608,
              "download_urls": [
                "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/gb_shard_00.leveldb"
              ],
              "extra_type": "shard",
              "extra_country_codes": "gb"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseManifestAndCountryFiltering() {
        val manifest = SuperpacksManifest.parse(sampleManifestJson)
        assertEquals(4, manifest.packs.size)

        val corePack = manifest.packs[0]
        assertTrue(corePack.isCorePack())
        assertEquals("tree_core_shard", corePack.lastUrlPathSegment)
        assertTrue(corePack.candidateFileNames().contains("matcher_tah.leveldb"))
        assertTrue(corePack.candidateFileNames().contains("tree_core_shard"))

        // Selecting "in" should return ONLY core + India shard
        val indiaPacks = manifest.selectPacksForCountries(listOf("in"))
        assertEquals(2, indiaPacks.size)
        assertEquals("matcher_tah.leveldb", indiaPacks[0].name)
        assertEquals("in_shard_00.leveldb", indiaPacks[1].name)

        // Selecting "us,xa" or "us" should match the "us,xa" shard + core
        val usPacksByCombined = manifest.selectPacksForCountries(listOf("us,xa"))
        assertEquals(2, usPacksByCombined.size)
        assertEquals("us_shard_00.leveldb", usPacksByCombined[1].name)

        val usPacksBySingle = manifest.selectPacksForCountries(listOf("us"))
        assertEquals(2, usPacksBySingle.size)
        assertEquals("us_shard_00.leveldb", usPacksBySingle[1].name)

        // Selecting primary "in" + extra "us,xa" should return core + in + us (3 packs, not gb)
        val multiPacks = manifest.selectPacksForCountries(listOf("in", "us,xa"))
        assertEquals(3, multiPacks.size)
        assertFalse(multiPacks.any { it.name == "gb_shard_00.leveldb" })
    }

    @Test
    fun testManifestUrlParser() {
        val standard = "3099:https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json"
        val parsed = ManifestUrlParser.parse(standard)
        assertNotNull(parsed)
        assertEquals(3099, parsed!!.versionCode)
        assertEquals(
            "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json",
            parsed.httpsUrl
        )
        assertEquals(standard, parsed.deviceConfigValue)

        val rawUrl = "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20241015-120000/manifest.json"
        val parsedRaw = ManifestUrlParser.parse(rawUrl, defaultVersionCode = 3150)
        assertNotNull(parsedRaw)
        assertEquals(3150, parsedRaw!!.versionCode)
        assertEquals(rawUrl, parsedRaw.httpsUrl)
        assertEquals("3150:$rawUrl", parsedRaw.deviceConfigValue)

        assertNull(ManifestUrlParser.parse("http://insecure.example.com/manifest.json"))
    }

    @Test
    fun testPartFileResumeAndExactSizeVerification() {
        val tempDir = createTempDirectory("amm_cache_test").toFile()
        try {
            val partFile = File(tempDir, "in_shard_00.leveldb.part")
            val finalFile = File(tempDir, "in_shard_00.leveldb")
            val expectedContent = ByteArray(4096) { (it % 251).toByte() }

            // Write first 1500 bytes to .part file
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.write(expectedContent, 0, 1500)
            }
            assertEquals(1500L, partFile.length())

            // Simulate HTTP 206 resume from offset 1500
            val resumeOffset = partFile.length()
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(resumeOffset)
                raf.write(expectedContent, resumeOffset.toInt(), expectedContent.size - resumeOffset.toInt())
            }
            assertEquals(expectedContent.size.toLong(), partFile.length())

            // Verify exact size and atomic rename
            assertTrue(partFile.renameTo(finalFile))
            assertTrue(finalFile.exists())
            assertFalse(partFile.exists())
            assertEquals(expectedContent.size.toLong(), finalFile.length())
            assertTrue(finalFile.readBytes().contentEquals(expectedContent))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
