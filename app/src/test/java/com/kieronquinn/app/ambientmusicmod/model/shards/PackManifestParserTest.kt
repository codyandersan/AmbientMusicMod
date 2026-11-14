package com.kieronquinn.app.ambientmusicmod.model.shards

import org.junit.Assert.*
import org.junit.Test

class PackManifestParserTest {

    private val json = """
        {"packs":[
          {"name":"tree_core_shard","size":54000000,"compressed_size":50000000,
           "download_urls":["https://storage.googleapis.com/music-iq-db/static/tree_core_shard"],
           "extra_type":"core"},
          {"name":"in_1","size":1000,"compressed_size":900,
           "download_urls":["https://storage.googleapis.com/music-iq-db/updatable_db_v3/x/in_1"],
           "extra_type":"shard","extra_country_codes":"in"},
          {"name":"us_1","size":2000,"compressed_size":1800,
           "download_urls":["https://storage.googleapis.com/music-iq-db/updatable_db_v3/x/us_1"],
           "extra_type":"shard","extra_country_codes":"us,xa"},
          {"name":"gb_1","size":3000,"compressed_size":2700,
           "download_urls":["https://storage.googleapis.com/music-iq-db/updatable_db_v3/x/gb_1"],
           "extra_type":"shard","extra_country_codes":"gb"}
        ]}
    """.trimIndent()

    private fun packs() = (PackManifestParser.parse(json) as ManifestParseResult.Success).packs

    @Test fun parsesCoreAndShards() {
        val p = packs()
        assertEquals(4, p.size)
        assertTrue(p[0].isCore)
        assertEquals("tree_core_shard", p[0].fileName)
        assertEquals(54000000L, p[0].size)
        assertNull(p[0].countryCodes)
    }

    @Test fun selectsCorePlusRequestedCountriesOnly() {
        val names = PackManifestParser.select(packs(), listOf("us,xa", "IN")).map { it.name }
        assertEquals(listOf("tree_core_shard", "in_1", "us_1"), names)
    }

    @Test fun skipsPacksWithBadUrls() {
        val bad = """{"packs":[
          {"name":"a","size":1,"download_urls":["http://x/a"]},
          {"name":"b","size":1,"download_urls":["https://x/b","https://y/b"]},
          {"name":"c","size":0,"download_urls":["https://x/c"]},
          {"name":"d","size":5,"download_urls":["https://x/d"]}]}"""
        val r = PackManifestParser.parse(bad) as ManifestParseResult.Success
        assertEquals(listOf("d"), r.packs.map { it.name })
        assertEquals(3, r.warnings.size)
    }

    @Test fun failsOnGarbageAndEmpty() {
        assertTrue(PackManifestParser.parse("<html>") is ManifestParseResult.Failure)
        assertTrue(PackManifestParser.parse("{}") is ManifestParseResult.Failure)
        assertTrue(PackManifestParser.parse("""{"packs":[]}""") is ManifestParseResult.Failure)
    }

    @Test fun manifestFlagParsing() {
        val ok = ManifestFlag.parse("3099:https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json")
        assertEquals(3099, ok?.version)
        assertNull(ManifestFlag.parse("https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json"))
        assertNull(ManifestFlag.parse("3099:http://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json"))
        assertNull(ManifestFlag.parse("3099:https://evil.example/music-iq-db/updatable_db_v3/20240901-030029/manifest.json"))
    }
}
