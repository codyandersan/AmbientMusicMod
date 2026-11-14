package com.kieronquinn.app.ambientmusicmod.diagnostics

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.as.oss.http.service.AstreaRequestLog
import com.google.gson.Gson
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.model.shards.ManifestFlag
import com.kieronquinn.app.ambientmusicmod.model.shards.ManifestParseResult
import com.kieronquinn.app.ambientmusicmod.model.shards.PackManifestParser
import com.kieronquinn.app.ambientmusicmod.model.shards.ShardManifest
import com.kieronquinn.app.ambientmusicmod.model.shards.ValidPack
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getPackageInfoCompat
import com.kieronquinn.app.ambientmusicmod.utils.extensions.isArmv7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 *  Each test reports real errors (exception class, message, cause chain) through [log] instead
 *  of swallowing them. Nothing here touches the existing download path, so a failure in a test
 *  cannot break the app.
 */
class DiagnosticsRunner(private val context: Context, private val log: (String) -> Unit) {

    companion object {
        const val POINTER_URL =
            "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release_v3.json"
        const val POINTER_URL_ARMV7 =
            "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release.json"
        private const val PAM_PROVIDER = "com.google.android.as.pam.ambientmusic.leveldbprovider"
        private const val PREFS = "download_diagnostics"
        private const val KEY_LAST_WORKING = "last_working_manifest_flag"
        private const val MAX_MANIFEST_BYTES = 20 * 1024 * 1024
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun describe(e: Throwable): String {
        val sb = StringBuilder("${e.javaClass.name}: ${e.message}")
        var c = e.cause
        var depth = 0
        while (c != null && depth < 4) {
            sb.append(" <- ${c.javaClass.name}: ${c.message}"); c = c.cause; depth++
        }
        return sb.toString()
    }

    // ---------- environment ----------

    fun environment(manifestFlag: String, requireWifi: Boolean, requireCharging: Boolean) {
        log("== Environment ==")
        log("AMM ${BuildConfig.TAG_NAME} (${BuildConfig.VERSION_CODE}), github owner=${BuildConfig.GITHUB_OWNER}")
        log("Manifest flag in use: $manifestFlag")
        log("superpacksRequireWiFi=$requireWifi superpacksRequireCharging=$requireCharging")
        if (requireWifi) log("WARN: requireWiFi=true -> Superpacks job will NOT run on mobile data or metered Wi-Fi")
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps == null) log("Network: NONE active") else {
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
                    NetworkCapabilities.TRANSPORT_VPN to "VPN",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET"
                ).filter { caps.hasTransport(it.first) }.joinToString { it.second }
                log("Network: transports=[$transports] unmetered=" +
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) +
                    " validated=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                    " activeNetworkMetered=" + cm.isActiveNetworkMetered)
            }
            log("Data saver (restrict background) status=${cm.restrictBackgroundStatus} (1=disabled 2=whitelisted 3=enabled)")
        } catch (e: Exception) { log("Network info failed: ${describe(e)}") }
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            log("Ignoring battery optimisations: ${pm.isIgnoringBatteryOptimizations(context.packageName)}")
            val usm = context.getSystemService(UsageStatsManager::class.java)
            log("App standby bucket: ${usm.appStandbyBucket} (10=active 20=working 30=frequent 40=rare 45=restricted)")
        } catch (e: Exception) { log("Power info failed: ${describe(e)}") }
        try {
            val info = context.packageManager.getPackageInfoCompat(PACKAGE_NAME_PAM)
            log("Now Playing installed: versionName=${info.versionName} code=${info.longVersionCode}")
        } catch (e: Exception) { log("Now Playing NOT found: ${describe(e)}") }
    }

    // ---------- connectivity ----------

    suspend fun connectivity() = withContext(Dispatchers.IO) {
        log("== DNS + HTTPS ==")
        listOf(
            "api.github.com" to "https://api.github.com/zen",
            "raw.githubusercontent.com" to (if (isArmv7) POINTER_URL_ARMV7 else POINTER_URL),
            "storage.googleapis.com" to "https://storage.googleapis.com/music-iq-db/static/tree_core_shard"
        ).forEach { (host, url) ->
            try {
                val t = SystemClock.elapsedRealtime()
                val addrs = InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" }
                log("DNS $host OK (${SystemClock.elapsedRealtime() - t}ms): $addrs")
            } catch (e: Exception) { log("DNS $host FAILED: ${describe(e)}") }
            try {
                val t = SystemClock.elapsedRealtime()
                val c = open(url, mapOf("Range" to "bytes=0-0"))
                val code = c.responseCode
                log("HTTPS $host -> $code in ${SystemClock.elapsedRealtime() - t}ms " +
                    "(Range bytes=0-0; 206 means ranges work) content-range=${c.getHeaderField("Content-Range")}")
                c.disconnect()
            } catch (e: Exception) { log("HTTPS $host FAILED: ${describe(e)}") }
        }
    }

    // ---------- manifest ----------

    suspend fun fetchPointer(): String? = withContext(Dispatchers.IO) {
        val url = if (isArmv7) POINTER_URL_ARMV7 else POINTER_URL
        try {
            val body = httpGetString(url)
            val pointer = Gson().fromJson(body, ShardManifest::class.java)
            log("Pointer OK: url=${pointer.url} (versionCode=${pointer.versionCode})")
            pointer.url
        } catch (e: Exception) {
            log("Pointer FAILED ($url): ${describe(e)}")
            null
        }
    }

    /** Fetches and parses the manifest for a "VERSION:URL" flag value. */
    suspend fun fetchManifest(flag: String): List<ValidPack>? = withContext(Dispatchers.IO) {
        val parsed = ManifestFlag.parse(flag)
        if (parsed == null) { log("Manifest flag not in expected VERSION:URL format: $flag"); return@withContext null }
        try {
            val t = SystemClock.elapsedRealtime()
            val body = httpGetString(parsed.url)
            when (val r = PackManifestParser.parse(body)) {
                is ManifestParseResult.Failure -> { log("Manifest PARSE FAILED (${parsed.url}): ${r.reason}"); null }
                is ManifestParseResult.Success -> {
                    val core = r.packs.count { it.isCore }
                    val byCountry = r.packs.filter { !it.isCore }.groupBy { it.countryCodes }
                    log("Manifest OK v${parsed.version} in ${SystemClock.elapsedRealtime() - t}ms: " +
                        "${r.packs.size} packs, $core core, ${byCountry.size} country values, " +
                        "total ${gb(r.packs.sumOf { it.size })}")
                    byCountry.entries.sortedBy { it.key }.forEach { (cc, packs) ->
                        log("  country=$cc packs=${packs.size} size=${gb(packs.sumOf { it.size })}")
                    }
                    r.warnings.take(10).forEach { log("  warning: $it") }
                    r.packs
                }
            }
        } catch (e: Exception) { log("Manifest FETCH FAILED (${parsed.url}): ${describe(e)}"); null }
    }

    /** Chain: remote pointer -> last known working -> built-in default. Returns first that works. */
    suspend fun resolveWorkingManifest(): String? {
        log("== Manifest fallback chain ==")
        val candidates = LinkedHashMap<String, String>()
        fetchPointer()?.let { candidates["remote pointer"] = it }
        prefs.getString(KEY_LAST_WORKING, null)?.let { candidates["last known working"] = it }
        candidates["built-in default"] = if (isArmv7) BuildConfig.DEFAULT_MANIFEST else BuildConfig.DEFAULT_MANIFEST_V3
        for ((label, flag) in candidates) {
            log("Trying $label: $flag")
            if (fetchManifest(flag) != null) {
                prefs.edit().putString(KEY_LAST_WORKING, flag).apply()
                log("Using $label")
                return flag
            }
        }
        log("No candidate manifest worked. Enter a manifest URL manually.")
        return null
    }

    // ---------- ranged pack request ----------

    suspend fun rangedPackTest(packs: List<ValidPack>) = withContext(Dispatchers.IO) {
        log("== Ranged request on a real pack ==")
        val pack = packs.firstOrNull { it.isCore } ?: packs.first()
        log("Pack ${pack.name} url=${pack.url} manifest size=${pack.size} compressed_size=${pack.compressedSize}")
        try {
            val c = open(pack.url, mapOf("Range" to "bytes=0-1023"))
            val code = c.responseCode
            val range = c.getHeaderField("Content-Range")
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readNBytesCompat(2048) } ?: 0
            log("Status=$code read=$bytes bytes content-range=$range content-length=${c.getHeaderField("Content-Length")} " +
                "etag=${c.getHeaderField("ETag")} accept-ranges=${c.getHeaderField("Accept-Ranges")} " +
                "content-type=${c.getHeaderField("Content-Type")}")
            val total = range?.substringAfter('/')?.toLongOrNull()
            if (total != null) {
                log("Real file length=$total -> matches size=${total == pack.size}, matches compressed_size=${total == pack.compressedSize}")
            }
            if (code != 206) log("WARN: not 206, server ignored Range; resume will not work")
            c.disconnect()
        } catch (e: Exception) { log("Ranged request FAILED: ${describe(e)}") }
    }

    // ---------- PAM ----------

    suspend fun pamState() {
        log("== Now Playing (PAM) ==")
        val intent = Intent("com.kieronquinn.app.pixelambientmusic.RECOGNITION_SERVICE").apply { `package` = PACKAGE_NAME_PAM }
        val connected = CompletableDeferred<Boolean>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(binder.pingBinder()) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        try {
            val started = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!started) log("bindService returned false (not installed, or signature permission not granted: AMM and PAM must share a signing key)")
            else {
                val ok = withTimeoutOrNull(8000) { connected.await() }
                log(if (ok == null) "Bind started but onServiceConnected not called in 8s" else "Bound OK, pingBinder=$ok")
            }
        } catch (e: Exception) { log("Bind FAILED: ${describe(e)}") }
        finally { try { context.unbindService(conn) } catch (_: Exception) {} }
        withContext(Dispatchers.IO) {
            query("downloadstate", "pending Superpacks downloads for ambientmusic-index (count)")
            query("jobs", "scheduled Superpacks download job ids")
        }
    }

    private fun query(path: String, label: String) {
        try {
            val uri = Uri.parse("content://$PAM_PROVIDER/$path")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor == null) { log("$label: provider returned null"); return }
            cursor.use {
                val values = ArrayList<String>()
                while (it.moveToNext()) values += it.getString(0) ?: "null"
                log("$label: ${values.size} row(s) $values")
            }
        } catch (e: Exception) { log("$label: query FAILED: ${describe(e)}") }
    }

    fun astreaLog(max: Int = 60) {
        log("== Astrea request log (last $max) ==")
        val lines = AstreaRequestLog.snapshot()
        if (lines.isEmpty()) log("(empty: Astrea has not handled a request since this process started, or no Superpacks job has run)")
        lines.takeLast(max).forEach { log(it) }
    }

    // ---------- helpers ----------

    private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)

    private fun open(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "AmbientMusicMod-diagnostics")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun httpGetString(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code ${c.responseMessage}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_MANIFEST_BYTES) throw java.io.IOException("Response larger than 20MB, aborting")
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): Int {
        val buf = ByteArray(max); var total = 0
        while (total < max) { val n = read(buf, total, max - total); if (n < 0) break; total += n }
        return total
    }
}
