package org.nemack.universalfilelab.etroute.runtimepack

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.nemack.universalfilelab.etroute.*

// Fixed feasibility scenarios only. Never accepts user code, modules, commands or a target session ID.
internal class InterruptionAcceptance(private val context: Context) {
    private val prefs = context.getSharedPreferences("acceptance-state", Context.MODE_PRIVATE)
    private val owner = AtomicReference<NativeStopController<JniNativeSupervisor.Request>?>(null)
    private val aborted = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    fun stop() { aborted.set(true); owner.get()?.requestStop() }
    fun onDestroyed() { destroyed.set(true); aborted.set(true); owner.get()?.close() }

    fun runSingle(case: AcceptanceCase): String = exclusive {
        require(case != AcceptanceCase.DESTROY) { "Use the lifecycle host for destruction" }
        runCase(case)
    }

    fun runSuite(progress: (String) -> Unit): String = exclusive { runSuiteOwned(progress) }

    private fun runSuiteOwned(progress: (String) -> Unit): String {
        val lines = mutableListOf<String>()
        for (case in listOf(AcceptanceCase.BEFORE_ATTACH, AcceptanceCase.STOP, AcceptanceCase.REPEATED_STOP,
            AcceptanceCase.TIMEOUT, AcceptanceCase.COMPLETE)) {
            if (aborted.get()) { lines += "SUITE_ABORTED_BY_OWNER"; break }
            progress("Running ${case.id} ${case.name}…")
            lines += runCase(case)
            if (prefs.getBoolean("uncertain", false)) { lines += "RECOVERY_REQUIRED; remaining cases skipped"; break }
        }
        lines += "ST06_UNCERTAINTY=NOT_RUN_ON_DEVICE (deterministic JVM policy tests only)"
        lines += "ST08_PRODUCTION_EVIDENCE=NOT_CONNECTED"
        lines += "generalScriptExecutionEnabled=false"
        val summary = lines.map { it.substringBefore('\n') }.joinToString("\n")
        return ("INTERRUPTION_SUITE_SUMMARY\n$summary\n\nDETAILS\n" + lines.joinToString("\n")).also(::persistLatest)
    }

    fun runLifecycle(onReadyDestroyActivity: () -> Unit): String = exclusive {
        runCase(AcceptanceCase.DESTROY, onReadyDestroyActivity).also(::persistLatest)
    }

    private fun exclusive(action: () -> String): String {
        check(active.compareAndSet(false, true)) { "Another acceptance owner is active" }
        return try { action() } finally { active.set(false) }
    }

    private fun runCase(case: AcceptanceCase, onReadyDestroyActivity: () -> Unit = {}): String {
        check(!prefs.getBoolean("uncertain", false)) { "Prior native termination is uncertain" }
        check(!aborted.get()) { "Owner stopped before preparation" }
        val installed = Candidate.installAndInspect(context)
        check(installed.report.status == RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE) { "Preflight rejected: ${installed.report.reason}" }
        check(!aborted.get()) { "Owner stopped during preflight" }
        val attempts = prefs.getInt("attempts", 0)
        check(attempts < 12) { "Diagnostic session ceiling reached (12)" }
        check(prefs.edit().putInt("attempts", attempts + 1).commit())
        val id = "accept-${case.id}-" + UUID.randomUUID().toString()
        val paths = EtRouteWorkspaceManager(context).create(id)
        val native = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val waiting = case != AcceptanceCase.COMPLETE && case != AcceptanceCase.BEFORE_ATTACH
        val launch = PreparedLaunch(File(native, installed.spec.executable).absolutePath,
            listOf(if (waiting) "--probe-wait" else "--probe", installed.root.absolutePath),
            mapOf("LD_LIBRARY_PATH" to native.absolutePath, "TMPDIR" to paths.tmp.absolutePath), paths.root.absolutePath,
            File(paths.diagnostics, "stdout.log").absolutePath, File(paths.diagnostics, "stderr.log").absolutePath,
            timeoutMs = if (case == AcceptanceCase.TIMEOUT) 3000 else 10000,
            limits = NativeResourceLimits(cpuSeconds = 5, maxOpenFiles = 64, maxFileBytes = 8192),
            sessionId = id, requestId = id, originTag = "fixed-interruption-acceptance")
        val cancels = AtomicInteger()
        val control = NativeStopController<JniNativeSupervisor.Request> { cancels.incrementAndGet(); it.cancel() }
        owner.set(control)
        if (aborted.get()) control.close()
        if (case == AcceptanceCase.BEFORE_ATTACH) repeat(3) { control.requestStop() }
        var request: JniNativeSupervisor.Request? = null
        var result: NativeRunResult? = null
        val finished = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        val watcher = if (waiting) Thread {
            val started = System.nanoTime()
            while (!finished.get() && !aborted.get() && System.nanoTime() - started < 8_000_000_000L) {
                val output = runCatching { ManualProbeDiagnostics.read(File(launch.stdoutPath)) }
                    .getOrElse { ManualProbeDiagnostics.Output("", true) }
                if (output.text.lineSequence().any { it == "ETROUTE_PYTHON_PROBE_READY" }) {
                    ready.set(true)
                    when (case) {
                        AcceptanceCase.STOP -> control.requestStop()
                        AcceptanceCase.REPEATED_STOP -> repeat(3) { control.requestStop() }
                        AcceptanceCase.DESTROY -> onReadyDestroyActivity()
                        else -> Unit
                    }
                    break
                }
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        } else null
        check(prefs.edit().putBoolean("uncertain", true).commit())
        val report = try {
            val completed = EtRouteSessionRunner(context).run(launch) {
                request = it; check(control.attach(it)); watcher?.start()
            }.result
            result = completed
            finished.set(true)
            watcher?.join(1000)
            val noChild = AcceptanceRules.noChildStarted(completed)
            val stdout = if (noChild && !File(launch.stdoutPath).exists()) ManualProbeDiagnostics.Output("", false)
                else ManualProbeDiagnostics.read(File(launch.stdoutPath))
            val stderr = if (noChild && !File(launch.stderrPath).exists()) ManualProbeDiagnostics.Output("", false)
                else ManualProbeDiagnostics.read(File(launch.stderrPath))
            val passed = AcceptanceRules.passed(case, completed, ready.get(), cancels.get(), destroyed.get(), stdout, stderr)
            buildString {
                appendLine("${case.id}_${case.name}=${if (passed) "PASS" else "FAIL"}")
                appendLine("stage=${completed.stage} exit=${completed.exitCode} signal=${completed.signal} errno=${completed.spawnErrno}")
                appendLine("timedOut=${completed.timedOut} durationMs=${completed.durationMs} ready=${ready.get()} nativeCancelCount=${cancels.get()} destroyed=${destroyed.get()}")
                appendLine("stdoutTruncated=${stdout.truncated} stderrTruncated=${stderr.truncated}")
                appendLine("termination=${if (AcceptanceRules.noChildStarted(completed)) "NOT_STARTED" else if (ManualProbeDiagnostics.terminationConfirmed(completed)) "CONFIRMED" else "UNCERTAIN"}")
                appendLine("session=$id; diagnosticsRetained=true; cleanupPerformed=false")
                appendLine("STDOUT: ${stdout.text}")
                appendLine("STDERR: ${stderr.text}")
            }
        } catch (error: Throwable) {
            "${case.id}_${case.name}=ERROR\n${error::class.java.simpleName}: ${error.message?.take(512)}\nsession=$id; diagnosticsRetained=true"
        } finally {
            finished.set(true)
            watcher?.interrupt()
            watcher?.join(1000)
            val completed = result
            val confirmed = completed != null && (ManualProbeDiagnostics.terminationConfirmed(completed) || AcceptanceRules.noChildStarted(completed))
            request?.let { control.complete(it, confirmed) }
            control.close(); owner.compareAndSet(control, null)
            if (confirmed) prefs.edit().putBoolean("uncertain", false).commit()
        }
        check(watcher?.isAlive != true) { "Diagnostic watcher did not end; chain must halt" }
        val bytes = report.toByteArray(Charsets.UTF_8)
        File(paths.diagnostics, "acceptance-report.txt").writeBytes(bytes.copyOfRange(0, minOf(bytes.size, 20000)))
        persistLatest(report)
        return report
    }

    fun persistLatest(text: String) = synchronized(reportLock) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val temporary = File(context.filesDir, "latest-acceptance.tmp")
        temporary.writeBytes(bytes.copyOfRange(0, minOf(bytes.size, 100000)))
        check(temporary.renameTo(File(context.filesDir, "latest-acceptance.txt")))
    }

    companion object {
        private val active = AtomicBoolean(false)
        private val reportLock = Any()
    }
}
