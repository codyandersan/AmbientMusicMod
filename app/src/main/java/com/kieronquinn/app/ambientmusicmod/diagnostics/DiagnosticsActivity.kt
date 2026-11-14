package com.kieronquinn.app.ambientmusicmod.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.kieronquinn.app.ambientmusicmod.model.shards.ManifestFlag
import com.kieronquinn.app.ambientmusicmod.repositories.DeviceConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 *  Standalone "Download diagnostics" screen, built with plain views so it cannot be broken by,
 *  and cannot break, the navigation graph of the main app. Opened from its own launcher icon
 *  ("AMM Diagnostics").
 */
class DiagnosticsActivity : ComponentActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var manualInput: EditText
    private lateinit var runner: DiagnosticsRunner
    private val buffer = StringBuilder()
    private var job: Job? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val deviceConfig: DeviceConfigRepository? by lazy {
        try { GlobalContext.get().get<DeviceConfigRepository>() } catch (e: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runner = DiagnosticsRunner(applicationContext) { appendLine(it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(24 + b.left, 24 + b.top, 24 + b.right, 24 + b.bottom)
            insets
        }
        root.addView(TextView(this).apply { text = "Download diagnostics"; textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(row(
            button("Run all tests") { runAll() },
            button("Fix manifest") { fixManifest() }
        ))
        manualInput = EditText(this).apply {
            hint = "Manual manifest, e.g. 3099:https://storage.googleapis.com/music-iq-db/updatable_db_v3/20240901-030029/manifest.json"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 12f
        }
        root.addView(manualInput)
        root.addView(row(
            button("Use manual manifest") { applyManual() },
            button("Astrea log") { launchJob { runner.astreaLog(200) } }
        ))
        root.addView(row(button("Copy") { copy() }, button("Share") { share() }, button("Clear") { buffer.setLength(0); output.text = "" }))
        output = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        appendLine("Tap \"Run all tests\". Everything runs in this app only; nothing is changed except by \"Fix manifest\" / \"Use manual manifest\".")
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun appendLine(line: String) {
        runOnUiThread {
            buffer.append(time.format(Date())).append("  ").append(line).append('\n')
            output.text = buffer
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun launchJob(block: suspend () -> Unit) {
        job?.cancel()
        job = lifecycleScope.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { appendLine("Diagnostics step crashed: ${runner.describe(e)}") }
        }
    }

    private fun manifestInUse(): String = try { deviceConfig?.indexManifest?.getSync() ?: "(unavailable)" } catch (e: Exception) { "(error: ${e.message})" }

    private fun runAll() = launchJob {
        val dc = deviceConfig
        runner.environment(
            manifestInUse(),
            try { dc?.superpacksRequireWiFi?.getSync() ?: false } catch (e: Exception) { false },
            try { dc?.superpacksRequireCharging?.getSync() ?: false } catch (e: Exception) { false }
        )
        runner.connectivity()
        appendLine("== Manifest in use ==")
        val packs = runner.fetchManifest(manifestInUse())
        if (packs != null) runner.rangedPackTest(packs)
        else appendLine("Skipping ranged test: manifest in use could not be fetched. Try \"Fix manifest\".")
        runner.pamState()
        runner.astreaLog()
        DiagnosticsState.lastPointerError?.let { appendLine("Last pointer error recorded by the app: $it") }
        appendLine("== Done ==")
    }

    private fun fixManifest() = launchJob {
        val flag = runner.resolveWorkingManifest() ?: return@launchJob
        applyFlag(flag)
    }

    private fun applyManual() = launchJob {
        val flag = manualInput.text.toString().trim()
        if (ManifestFlag.parse(flag) == null) {
            appendLine("Rejected: must look like 3099:https://storage.googleapis.com/music-iq-db/updatable_db_v3/YYYYMMDD-HHMMSS/manifest.json (the app cannot parse any other form)")
            return@launchJob
        }
        appendLine("Testing manual manifest $flag")
        if (runner.fetchManifest(flag) == null) { appendLine("Not applied: manifest could not be fetched/parsed."); return@launchJob }
        applyFlag(flag)
    }

    private suspend fun applyFlag(flag: String) {
        val dc = deviceConfig ?: run { appendLine("Cannot apply: settings repository unavailable"); return }
        try {
            dc.indexManifest.set(flag)
            appendLine("APPLIED manifest flag: $flag (Now Playing is told via onConfigChanged; check Updates tab)")
        } catch (e: Exception) { appendLine("Apply FAILED: ${runner.describe(e)}") }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnostics", buffer.toString()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, buffer.toString())
        }, "Share diagnostics"))
    }
}
