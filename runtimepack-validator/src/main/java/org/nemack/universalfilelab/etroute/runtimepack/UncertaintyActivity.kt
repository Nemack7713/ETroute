package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.os.Bundle
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.nemack.universalfilelab.etroute.FixedProbeEvidenceSession

// Debug-only receipt-loss exercise. Does not leave a deliberately orphaned native child.
class UncertaintyActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var run: Button
    @Volatile private var owner: FixedProbeEvidenceSession? = null
    private val stopped = AtomicBoolean(false)
    @Volatile private var disposed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(24, 24, 24, 24) }
        run = Button(this).apply { id = android.R.id.button1; text = "RUN SIMULATED RECEIPT LOSS"; setOnClickListener { startCheck() } }
        val verify = Button(this).apply { id = android.R.id.button2; text = "VERIFY RETENTION AFTER REOPEN"; setOnClickListener { showRetention() } }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { stopped.set(true); owner?.stop() } }
        output = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val last = Button(this).apply { text = "SHOW LAST RUN REPORT"; setOnClickListener { showLastReport() } }
        val explanation = TextView(this).apply {
            text = "Receipt loss intentionally blocks another run, including after reopening. Diagnostics are retained. Use Verify or Show Last Run Report."
        }
        root.addView(run); root.addView(verify); root.addView(stop); root.addView(last); root.addView(explanation)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateRunState()
        showLastReport()
    }
    private fun updateRunState() {
        val blocked = getSharedPreferences("probe-evidence-state", MODE_PRIVATE).getBoolean("uncertain", false)
        run.isEnabled = !blocked && !busy.get()
        run.text = if (blocked) "RUN BLOCKED — DIAGNOSTICS RETAINED" else "RUN SIMULATED RECEIPT LOSS"
    }
    private fun showLastReport() {
        val report = File(filesDir, "latest-receipt-loss.txt")
        output.text = when {
            !report.isFile -> "No run report yet. Controlled receipt-loss simulation only; general scripts disabled."
            report.length() > 24000 -> "RUN_REPORT_UNAVAILABLE: report exceeds 24000-byte bound"
            else -> report.readText()
        }
        updateRunState()
    }
    private fun startCheck() {
        if (disposed || !busy.compareAndSet(false, true)) return
        stopped.set(false); run.isEnabled = false
        output.text = "Running one fixed probe, then withholding the owner receipt…"
        Thread {
            var phase = "preflight"
            var probeReport = ""
            val report = try {
                val candidate = Candidate.installAndInspect(applicationContext)
                val session = FixedProbeEvidenceSession(applicationContext, candidate.spec, candidate.root)
                owner = session
                if (disposed) session.onDestroyed() else if (stopped.get()) session.stop()
                phase = "fixed-probe"
                val result = session.runReceiptLossCheck { !disposed && !stopped.get() }
                probeReport = result
                check(result.startsWith("ST06_SIMULATED_RECEIPT_LOSS=PASS")) { result }
                phase = "retention-snapshot"
                ReceiptLossRetention.snapshot(applicationContext, result)
                phase = "retry-gate"
                val retryBlocked = ReceiptLossRetention.verifyRetryBlocked(applicationContext, candidate)
                check(retryBlocked) { "Retry gate did not reject before approval" }
                phase = "retention-verification"
                "$result\nretryBlockedBeforeApproval=$retryBlocked\n" + ReceiptLossRetention.verify(applicationContext)
            } catch (error: Throwable) {
                "ST06_SIMULATED_RECEIPT_LOSS=ERROR\nphase=$phase\n${error::class.java.simpleName}: ${error.message?.take(1024)}\n" +
                    "RETAINED_PROBE_REPORT:\n$probeReport\ngeneralScriptExecutionEnabled=false"
            } finally { owner?.stop(); busy.set(false) }
            check(report.toByteArray().size <= 24000)
            val temporary = File(filesDir, "latest-receipt-loss.tmp")
            temporary.writeText(report)
            check(temporary.renameTo(File(filesDir, "latest-receipt-loss.txt")))
            runOnUiThread { if (!disposed) { output.text = report; updateRunState() } }
        }.start()
    }
    private fun showRetention() {
        if (busy.get()) return
        output.text = runCatching { ReceiptLossRetention.verify(applicationContext) }
            .getOrElse { "RETENTION_CHECK_UNAVAILABLE: ${it.message?.take(256)}" }
    }
    override fun onDestroy() { disposed = true; stopped.set(true); owner?.onDestroyed(); super.onDestroy() }
    companion object { private val busy = AtomicBoolean(false) }
}
