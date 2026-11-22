package org.nemack.universalfilelab.etroute.runtimepack

import java.util.concurrent.atomic.AtomicBoolean

// Blocking, single-use sequence. An action returns only after its owner and report finalization have ended.
internal class SequentialAcceptanceChain(private val steps: List<Step>,
    private val errorContext: (Throwable) -> String = { "SOURCE_CONTEXT_UNAVAILABLE: source lookup was not configured." }) {
    data class Step(val id: String, val terminalRetention: Boolean = false, val action: () -> Result)
    data class Result(val passed: Boolean, val barrierReached: Boolean, val permitsNext: Boolean, val report: String)
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    fun stop() { stopped.set(true) }
    init {
        require(steps.isNotEmpty() && steps.size <= 16)
        require(steps.map { it.id }.distinct().size == steps.size)
        require(steps.dropLast(1).none { it.terminalRetention })
    }
    fun run(checkpoint: (String) -> Unit, detail: (String, String) -> Unit): String {
        check(started.compareAndSet(false, true)) { "Chain is single-use" }
        val lines = mutableListOf<String>()
        var completed = 0
        var status = "PASS"
        for ((index, step) in steps.withIndex()) {
            if (stopped.get()) { status = "STOPPED"; break }
            checkpoint((lines + "${step.id}=RUNNING").joinToString("\n"))
            if (stopped.get()) { status = "STOPPED"; break }
            val result = try { step.action() } catch (error: Throwable) {
                Result(false, false, false, "ERROR ${error::class.java.simpleName}: ${error.message?.take(1024)}\n" + errorContext(error))
            }
            check(result.report.toByteArray(Charsets.UTF_8).size <= 24000) { "Step report exceeds bound" }
            detail(step.id, result.report)
            val safeEnd = result.barrierReached && (result.permitsNext || (step.terminalRetention && index == steps.lastIndex))
            val passed = result.passed && safeEnd
            val interrupted = stopped.get()
            lines += "${step.id}=${if (interrupted) "INTERRUPTED" else if (passed) "PASS" else "FAIL"}; barrierReached=${result.barrierReached}; permitsNext=${result.permitsNext}"
            completed++
            checkpoint(lines.joinToString("\n"))
            if (interrupted || stopped.get()) { status = "STOPPED"; break }
            if (!passed) { status = "HALTED"; break }
        }
        steps.drop(completed).forEach { lines += "${it.id}=NOT_RUN" }
        if (status == "PASS" && steps.last().terminalRetention) status = "PASS_RETRY_BLOCKED"
        return "SEQUENTIAL_CHAIN=$status\n${lines.joinToString("\n")}\n" +
            "automaticRestart=false\nactualNativeUncertaintyTested=false\ngeneralScriptExecutionEnabled=false"
    }
}
