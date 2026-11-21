package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.nemack.universalfilelab.etroute.FixedProbeEvidenceSession

// Each deliberate tap approves one fixed probe, not general Python execution.
class EvidenceActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var complete: Button
    private lateinit var lifecycle: Button
    private lateinit var stop: Button
    private val busy = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    @Volatile private var disposed = false
    @Volatile private var session: FixedProbeEvidenceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(24, 24, 24, 24)
        }
        complete = Button(this).apply { id = android.R.id.button1; text = "RUN ST08 COMPLETION EVIDENCE"; setOnClickListener { start(false) } }
        lifecycle = Button(this).apply { id = android.R.id.button2; text = "RUN ST08 LIFECYCLE (CLOSES SCREEN)"; setOnClickListener { start(true) } }
        stop = Button(this).apply { text = "STOP"; isEnabled = false; setOnClickListener { stopped.set(true); session?.stop() } }
        val latest = Button(this).apply { text = "SHOW LATEST EVIDENCE"; setOnClickListener { showLatest() } }
        output = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(complete); root.addView(lifecycle); root.addView(stop); root.addView(latest)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); showLatest()
    }

    private fun showLatest() {
        val file = File(filesDir, "latest-probe-evidence.txt")
        output.text = if (file.isFile) file.inputStream().use { input ->
            val bytes = ByteArray(24000); var count = 0
            while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
            String(bytes, 0, count, Charsets.UTF_8)
        } else "ST08 pending. Each tap approves one fixed probe. General scripts remain disabled."
        if (!busy.get()) {
            val uncertain = getSharedPreferences("probe-evidence-state", MODE_PRIVATE).getBoolean("uncertain", false)
            complete.isEnabled = !uncertain; lifecycle.isEnabled = !uncertain
            if (uncertain) output.append("\nIN_FLIGHT_OR_UNCERTAIN: retained; no automatic retry")
        }
    }

    private fun start(destroyCase: Boolean) {
        if (disposed || !busy.compareAndSet(false, true)) return
        stopped.set(false); complete.isEnabled = false; lifecycle.isEnabled = false; stop.isEnabled = true
        output.text = "Rechecking APK-pinned inventory and creating one-use evidence request…"
        Thread {
            val report = try {
                val candidate = Candidate.installAndInspect(applicationContext)
                val current = FixedProbeEvidenceSession(applicationContext, candidate.spec, candidate.root)
                session = current
                if (disposed) current.onDestroyed() else if (stopped.get()) current.stop()
                current.run(destroyCase, approved = { !disposed && !stopped.get() }, onReadyDestroy = {
                    runOnUiThread { if (!disposed && !isFinishing) finish() }
                })
            } catch (error: Throwable) {
                "ST08_EVIDENCE=ERROR\n${error::class.java.simpleName}: ${error.message?.take(512)}\ngeneralScriptExecutionEnabled=false"
            } finally { session?.stop(); busy.set(false) }
            val bytes = report.toByteArray(Charsets.UTF_8)
            check(bytes.size <= 24000) { "Evidence report exceeds bound" }
            val temporary = File(filesDir, "latest-probe-evidence.tmp")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(File(filesDir, "latest-probe-evidence.txt")))
            runOnUiThread { if (!disposed) { stop.isEnabled = false; showLatest() } }
        }.start()
    }

    override fun onDestroy() {
        disposed = true; stopped.set(true); session?.onDestroyed(); super.onDestroy()
    }
}
