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
        root.addView(run); root.addView(verify); root.addView(stop)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        run.isEnabled = !getSharedPreferences("probe-evidence-state", MODE_PRIVATE).getBoolean("uncertain", false)
        val report = File(filesDir, "latest-receipt-loss.txt")
        output.text = if (report.isFile && report.length() <= 24000) report.readText()
            else "Controlled simulation only: the fixed native probe exits normally; the owner loses its completion receipt. General scripts disabled."
    }
    private fun startCheck() {
        if (disposed || !busy.compareAndSet(false, true)) return
        stopped.set(false); run.isEnabled = false
        output.text = "Running one fixed probe, then withholding the owner receipt…"
        Thread {
            val report = try {
                val candidate = Candidate.installAndInspect(applicationContext)
                val session = FixedProbeEvidenceSession(applicationContext, candidate.spec, candidate.root)
                owner = session
                if (disposed) session.onDestroyed() else if (stopped.get()) session.stop()
                val result = session.runReceiptLossCheck { !disposed && !stopped.get() }
                check(result.startsWith("ST06_SIMULATED_RECEIPT_LOSS=PASS")) { result }
                ReceiptLossRetention.snapshot(applicationContext, result)
                val retryBlocked = ReceiptLossRetention.verifyRetryBlocked(applicationContext, candidate)
                check(retryBlocked) { "Retry gate did not reject before approval" }
                "$result\nretryBlockedBeforeApproval=$retryBlocked\n" + ReceiptLossRetention.verify(applicationContext)
            } catch (error: Throwable) {
                "ST06_SIMULATED_RECEIPT_LOSS=ERROR\n${error::class.java.simpleName}: ${error.message?.take(1024)}\ngeneralScriptExecutionEnabled=false"
            } finally { owner?.stop(); busy.set(false) }
            check(report.toByteArray().size <= 24000)
            val temporary = File(filesDir, "latest-receipt-loss.tmp")
            temporary.writeText(report)
            check(temporary.renameTo(File(filesDir, "latest-receipt-loss.txt")))
            runOnUiThread { if (!disposed) output.text = report }
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
