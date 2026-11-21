package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// On-device fixed acceptance UI. No ADB, free-form command, module list or automatic probe launch.
class AcceptanceActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var suite: Button
    private lateinit var lifecycle: Button
    private lateinit var stop: Button
    private val busy = AtomicBoolean(false)
    @Volatile private var runner: InterruptionAcceptance? = null
    @Volatile private var disposed = false
    private val prefs by lazy { getSharedPreferences("acceptance-state", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true
        }
        suite = Button(this).apply { id = android.R.id.button1; text = "RUN STOP / TIMEOUT SUITE"; setOnClickListener { start(false) } }
        lifecycle = Button(this).apply { id = android.R.id.button2; text = "RUN LIFECYCLE CHECK (CLOSES APP)"; setOnClickListener { start(true) } }
        stop = Button(this).apply { text = "STOP"; isEnabled = false; setOnClickListener { runner?.stop(); text = "STOP REQUESTED" } }
        val latest = Button(this).apply { text = "SHOW LATEST REPORT"; setOnClickListener { showLatest() } }
        output = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(suite); root.addView(lifecycle); root.addView(stop); root.addView(latest)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showLatest()
    }

    private fun showLatest() {
        val file = File(filesDir, "latest-acceptance.txt")
        val message = if (file.isFile) file.inputStream().use { input ->
            val bytes = ByteArray(100000); var count = 0
            while (count < bytes.size) {
                val next = input.read(bytes, count, bytes.size - count)
                if (next < 0) break
                count += next
            }
            String(bytes, 0, count, Charsets.UTF_8)
        } else "No acceptance run yet. Run the Stop/timeout suite first. General scripts remain disabled."
        val uncertain = prefs.getBoolean("uncertain", false)
        output.text = (if (uncertain) "IN_FLIGHT_OR_UNCERTAIN: session retained; no automatic retry.\n" else "") + message
        if (!busy.get()) { suite.isEnabled = !uncertain; lifecycle.isEnabled = !uncertain }
    }

    private fun start(destroyCase: Boolean) {
        if (disposed || prefs.getBoolean("uncertain", false) || !busy.compareAndSet(false, true)) return
        val current = InterruptionAcceptance(applicationContext)
        runner = current
        suite.isEnabled = false; lifecycle.isEnabled = false; stop.isEnabled = true; stop.text = "STOP"
        output.text = if (destroyCase) "Starting fixed wait probe. App will close after readiness; reopen it and tap SHOW LATEST REPORT." else "Preparing interruption suite…"
        Thread {
            val report = try {
                if (destroyCase) current.runLifecycle {
                    runOnUiThread { if (!disposed && !isFinishing) finish() }
                } else current.runSuite { progress -> runOnUiThread { if (!disposed) output.text = progress } }
            } catch (error: Throwable) {
                "ACCEPTANCE_ERROR: ${error::class.java.simpleName}: ${error.message?.take(512)}\ngeneralScriptExecutionEnabled=false"
                    .also { current.persistLatest(it) }
            } finally {
                // Stop does not assert cleanup. The runner retains diagnostics on every outcome.
                current.stop(); busy.set(false)
            }
            runOnUiThread {
                if (!disposed) {
                    output.text = report
                    stop.isEnabled = false; stop.text = "STOP"
                    suite.isEnabled = !prefs.getBoolean("uncertain", false)
                    lifecycle.isEnabled = suite.isEnabled
                }
            }
        }.start()
    }

    override fun onDestroy() {
        disposed = true
        runner?.onDestroyed()
        super.onDestroy()
    }
}
