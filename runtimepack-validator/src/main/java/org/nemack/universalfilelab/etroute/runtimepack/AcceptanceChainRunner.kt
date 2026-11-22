package org.nemack.universalfilelab.etroute.runtimepack

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.nemack.universalfilelab.etroute.FixedProbeEvidenceSession

internal class AcceptanceChainRunner(context: Context, private val launchLifecycle: (String) -> Unit,
    private val progress: (String) -> Unit) {
    private val context = context.applicationContext
    private val halted = AtomicBoolean(false)
    private val cancel = AtomicReference<(() -> Unit)?>(null)
    private val stopTrace = ChainStopTrace()
    @Volatile private var currentStep = "BEFORE_CHAIN"
    @Volatile private lateinit var chain: SequentialAcceptanceChain
    fun stop(cause: ChainStopCause) {
        val recorded = stopTrace.record(cause, currentStep)
        halted.set(true); if (::chain.isInitialized) chain.stop(); cancel.get()?.invoke()
        if (recorded) runCatching {
            save("chain-stop-reason.txt", stopTrace.report(), 1024)
        }
    }
    fun close() { halted.set(true); if (::chain.isInitialized) chain.stop(); cancel.get()?.invoke() }
    fun stopReport(): String = stopTrace.report()
    private fun attach(stop: () -> Unit) { cancel.set(stop); if (halted.get()) stop() }
    private fun result(report: String, prefix: String, permitsNext: Boolean = true): SequentialAcceptanceChain.Result {
        val passed = report.startsWith(prefix)
        val barrier = if (permitsNext) report.contains("termination=CONFIRMED") || report.contains("termination=NOT_STARTED") ||
            report.contains("termination=confirmed") || report.contains("termination=not-started") else report.contains("actualNativeTerminationConfirmed=true")
        return SequentialAcceptanceChain.Result(passed, barrier, permitsNext, report)
    }
    fun run(): String {
        val steps = listOf(AcceptanceCase.BEFORE_ATTACH, AcceptanceCase.STOP, AcceptanceCase.REPEATED_STOP,
            AcceptanceCase.TIMEOUT, AcceptanceCase.COMPLETE).map { case ->
            SequentialAcceptanceChain.Step("${case.id}_${case.name}", action = {
                val owner = InterruptionAcceptance(context)
                attach(owner::stop)
                try {
                    val report = owner.runSingle(case)
                    val certain = !context.getSharedPreferences("acceptance-state", Context.MODE_PRIVATE).getBoolean("uncertain", false)
                    result(report, "${case.id}_${case.name}=PASS").copy(permitsNext = certain)
                } finally { owner.stop(); cancel.set(null) }
            })
        } + listOf(
            SequentialAcceptanceChain.Step("ST08_COMPLETE", action = {
                val candidate = Candidate.installAndInspect(context)
                val owner = FixedProbeEvidenceSession(context, candidate.spec, candidate.root)
                attach(owner::stop)
                try {
                    result(owner.run(false, approved = { !halted.get() }), "ST08_COMPLETE=PASS")
                } finally { owner.stop(); cancel.set(null) }
            }),
            SequentialAcceptanceChain.Step("ST05_ST08_LIFECYCLE", action = {
                val slot = ChainLifecycleBridge.Slot()
                check(ChainLifecycleBridge.active.compareAndSet(null, slot)) { "Lifecycle owner already active" }
                attach(slot::stop)
                try {
                    check(!halted.get()) { "Stopped before lifecycle launch" }
                    launchLifecycle(slot.token)
                    val report = try { slot.done.get(60, TimeUnit.SECONDS) } catch (error: Exception) {
                        slot.stop()
                        // Cancellation is explicit. A deadline never counts as confirmed completion.
                        runCatching { slot.done.get(15, TimeUnit.SECONDS) }
                        throw IllegalStateException("Lifecycle completion barrier missed; chain halted", error)
                    }
                    result(report, "ST08_LIFECYCLE=PASS")
                } finally {
                    slot.stop(); cancel.set(null)
                    ChainLifecycleBridge.active.compareAndSet(slot, null)
                }
            }),
            SequentialAcceptanceChain.Step("ST06_SIMULATED_RECEIPT_LOSS", terminalRetention = true, action = {
                val candidate = Candidate.installAndInspect(context)
                val owner = FixedProbeEvidenceSession(context, candidate.spec, candidate.root)
                attach(owner::stop)
                try {
                    val report = owner.runReceiptLossCheck { !halted.get() }
                    check(report.startsWith("ST06_SIMULATED_RECEIPT_LOSS=PASS")) { report.take(1024) }
                    ReceiptLossRetention.snapshot(context, report)
                    check(ReceiptLossRetention.verifyRetryBlocked(context, candidate)) { "Retry gate failed" }
                    val retained = ReceiptLossRetention.verify(context)
                    check(retained.startsWith("ST06_REOPEN_RETENTION=PASS")) { retained }
                    result("$report\nretryBlockedBeforeApproval=true\n" +
                        "retentionCheckedInSameRun=true (reopen verification is separate)\n$retained",
                        "ST06_SIMULATED_RECEIPT_LOSS=PASS", false)
                } finally { owner.stop(); cancel.set(null) }
            })
        )
        chain = SequentialAcceptanceChain(steps, errorContext = { org.nemack.universalfilelab.etroute.SourceErrorContext.describe(context, it) })
        if (halted.get()) chain.stop()
        return try { chain.run(checkpoint = {
            currentStep = it.lineSequence().last().substringBefore('=')
            save("latest-chain.txt", "SEQUENTIAL_CHAIN=RUNNING\n$it\nautomaticRestart=false\ngeneralScriptExecutionEnabled=false", 16000)
            progress(it)
        }, detail = { id, text ->
            check(id.matches(Regex("[A-Z0-9_]+")))
            save("chain-$id.txt", text, 24000)
        }).let { summary ->
            stopTrace.finish()
            (summary + "\n" + stopTrace.report()).also { save("latest-chain.txt", it, 16000) }
        } } finally { stopTrace.finish() }
    }
    private fun save(name: String, text: String, limit: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= limit) { "Chain report exceeds bound" }
        val temporary = File(context.filesDir, "$name.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(File(context.filesDir, name))) { "Unable to finalize chain report" }
    }
}
