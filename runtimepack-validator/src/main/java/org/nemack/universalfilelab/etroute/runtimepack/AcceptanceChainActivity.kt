package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

// One tap approves a fixed list of isolated tests, never caller-supplied commands or general scripts.
class AcceptanceChainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var stop: Button
    @Volatile private var destroyed = false
    private val prefs by lazy { getSharedPreferences("acceptance-chain", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(24, 24, 24, 24) }
        run = Button(this).apply { id = android.R.id.button1; text = "RUN FIXED TEST CHAIN"; setOnClickListener { startChain() } }
        stop = Button(this).apply { text = "STOP CHAIN"; setOnClickListener {
            active.get()?.stop(ChainStopCause.OPERATOR); isEnabled = false; text = "STOP REQUESTED — WAITING FOR OWNER"
        } }
        val summary = Button(this).apply { text = "SHOW CHAIN SUMMARY"; setOnClickListener { showSummary() } }
        val details = Button(this).apply { text = "SHOW TEST REPORTS"; setOnClickListener { showDetails() } }
        val verify = Button(this).apply { text = "VERIFY RETENTION AFTER REOPEN"; setOnClickListener {
            if (active.get() == null) output.text = runCatching { ReceiptLossRetention.verify(applicationContext) }
                .getOrElse { "RETENTION_UNAVAILABLE: ${it.message?.take(512)}" }
        } }
        output = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(run); root.addView(stop); root.addView(summary); root.addView(details); root.addView(verify)
        root.addView(TextView(this).apply { text = "Strictly sequential. A failure or Stop halts later tests. Receipt-loss simulation is last and intentionally blocks another chain. No automatic restart." })
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); showSummary()
    }
    private fun updateControls() {
        run.isEnabled = !prefs.getBoolean("claimed", false) && active.get() == null
        run.text = if (prefs.getBoolean("claimed", false)) "CHAIN ALREADY CLAIMED — REPORTS RETAINED" else "RUN FIXED TEST CHAIN"
        stop.isEnabled = active.get() != null
        stop.text = if (active.get() == null) "STOP — NO ACTIVE CHAIN" else "STOP CHAIN"
    }
    private fun showSummary() {
        val file = File(filesDir, "latest-chain.txt")
        output.text = if (file.isFile && file.length() <= 16000) file.readText()
            else "Ready: ST01, ST02, ST03, ST04, ST07, ST08 completion, actual child-Activity destruction, then simulated receipt loss. General scripts disabled."
        updateControls()
    }
    private fun showDetails() {
        val files = filesDir.listFiles { f -> f.name.startsWith("chain-") && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        check(files.size <= 9)
        check(files.all { it.length() <= 24000 })
        output.text = files.joinToString("\n\n") { "${it.name}\n${it.readText()}" }.ifEmpty { "No completed test reports yet." }
    }
    private fun startChain() {
        if (destroyed || prefs.getBoolean("claimed", false)) return
        val current = AcceptanceChainRunner(applicationContext, launchLifecycle = { token ->
            runOnUiThread {
                if (!destroyed) startActivity(Intent(this, ChainLifecycleActivity::class.java).putExtra("chainToken", token))
                else active.get()?.stop(ChainStopCause.LIFECYCLE_LAUNCH_ABORT)
            }
        }, progress = { message -> runOnUiThread { if (!destroyed) output.text = message } })
        if (!active.compareAndSet(null, current)) return
        if (!prefs.edit().putBoolean("claimed", true).commit()) { active.compareAndSet(current, null); error("Unable to claim chain") }
        updateControls()
        Thread {
            val report = try { current.run() } catch (error: Throwable) {
                "SEQUENTIAL_CHAIN=ERROR\n${error::class.java.simpleName}: ${error.message?.take(1024)}\n" +
                    "${current.stopReport()}\nautomaticRestart=false\ngeneralScriptExecutionEnabled=false"
            } finally { current.close(); active.compareAndSet(current, null) }
            val temp = File(filesDir, "latest-chain-final.tmp")
            temp.writeText(report); check(temp.renameTo(File(filesDir, "latest-chain.txt")))
            runOnUiThread { if (!destroyed) { output.text = report; updateControls() } }
        }.start()
    }
    override fun onDestroy() {
        destroyed = true
        val cause = when {
            isChangingConfigurations -> ChainStopCause.PARENT_DESTROY_CONFIGURATION
            isFinishing -> ChainStopCause.PARENT_DESTROY_FINISHING
            else -> ChainStopCause.PARENT_DESTROY_OTHER
        }
        active.get()?.stop(cause)
        super.onDestroy()
    }
    companion object { private val active = AtomicReference<AcceptanceChainRunner?>(null) }
}
