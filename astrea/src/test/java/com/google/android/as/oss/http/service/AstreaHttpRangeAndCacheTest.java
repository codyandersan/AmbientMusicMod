package com.google.android.as.oss.http.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AstreaHttpRangeAndCacheTest {

  @Test
  public void testParseRangeHeaderValidFullAndOpenEnded() {
    long fileSize = 56_623_104L;

    long[] r1 = HttpGrpcBindableService.parseRangeHeader("bytes=0-1023", fileSize);
    assertNotNull(r1);
    assertArrayEquals(new long[] {0L, 1023L}, r1);

    long[] r2 = HttpGrpcBindableService.parseRangeHeader("bytes=1048576-", fileSize);
    assertNotNull(r2);
    assertArrayEquals(new long[] {1048576L, fileSize - 1L}, r2);

    // Clamps end offset if it exceeds fileSize - 1
    long[] r3 = HttpGrpcBindableService.parseRangeHeader("bytes=50000000-99999999", fileSize);
    assertNotNull(r3);
    assertArrayEquals(new long[] {50000000L, fileSize - 1L}, r3);
  }

  @Test
  public void testParseRangeHeaderInvalid() {
    long fileSize = 10_000L;
    assertNull(HttpGrpcBindableService.parseRangeHeader(null, fileSize));
    assertNull(HttpGrpcBindableService.parseRangeHeader("", fileSize));
    assertNull(HttpGrpcBindableService.parseRangeHeader("items=0-10", fileSize));
    assertNull(HttpGrpcBindableService.parseRangeHeader("bytes=10000-10050", fileSize));
    assertNull(HttpGrpcBindableService.parseRangeHeader("bytes=500-100", fileSize));
    assertNull(HttpGrpcBindableService.parseRangeHeader("bytes=0-10", 0L));
  }

  @Test
  public void testHttpsAndHostValidation() {
    assertTrue(
        HttpGrpcBindableService.isValidHttpsUrl(
            "https://storage.googleapis.com/music-iq-db/static/tree_core_shard"));
    assertFalse(
        HttpGrpcBindableService.isValidHttpsUrl(
            "http://storage.googleapis.com/music-iq-db/static/tree_core_shard"));

    assertTrue(
        HttpGrpcBindableService.isAllowedHost(
            "https://storage.googleapis.com/music-iq-db/static/tree_core_shard"));
    assertTrue(
        HttpGrpcBindableService.isAllowedHost(
            "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release_v3.json"));
    assertFalse(
        HttpGrpcBindableService.isAllowedHost("https://evil.example.com/music-iq-db/pack"));
  }

  @Test
  public void testAstreaCacheBridgeMetricsAndLogs() {
    AstreaCacheBridge.clearLogs();
    AstreaCacheBridge.appendLog("TestTag", "First diagnostic log line");
    assertEquals(1, AstreaCacheBridge.getMemoryLogs().size());
    assertTrue(AstreaCacheBridge.getMemoryLogs().get(0).contains("First diagnostic log line"));
  }
}
