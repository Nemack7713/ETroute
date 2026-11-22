package org.nemack.universalfilelab.etroute

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted application composition only: APK-pinned pack, fixed worker modes, explicit user approval. */
@android.annotation.TargetApi(26)
class FixedProbeEvidenceSession(context: Context, private val pack: PinnedRuntimePack,
    private val payloadRoot: File) {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val control = NativeStopController<JniNativeSupervisor.Request> { it.cancel() }

    fun stop() { stopped.set(true); control.requestStop() }
    fun onDestroyed() { destroyed.set(true); stopped.set(true); control.close() }

    // Blocking: caller must run off Main. Approval is a trusted UI action, not an envelope field.
    fun run(destroyAfterReady: Boolean, approved: () -> Boolean, onReadyDestroy: () -> Unit = {}): String =
        runFixed(destroyAfterReady, approved, onReadyDestroy, false)

    // Acceptance fault injection only. The native child is reaped normally; only delivery to the owner is withheld.
    fun runReceiptLossCheck(approved: () -> Boolean): String {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Receipt-loss test requires a debuggable app" }
        return runFixed(false, approved, {}, true)
    }

    private fun runFixed(destroyAfterReady: Boolean, approved: () -> Boolean,
        onReadyDestroy: () -> Unit, simulateReceiptLoss: Boolean): String {
        check(started.compareAndSet(false, true)) { "Evidence session is single-use" }
        check(active.compareAndSet(false, true)) { "Another evidence session is active" }
        val prefs = context.getSharedPreferences("probe-evidence-state", Context.MODE_PRIVATE)
        var coordinator: ProbeCoordinator? = null
        try {
            check(!prefs.getBoolean("uncertain", false)) { "Prior probe needs recovery" }
            val attempts = prefs.getInt("attempts", 0)
            check(attempts < 8) { "Evidence session ceiling reached (8)" }
            check(prefs.edit().putInt("attempts", attempts + 1).commit())
            val platform = RuntimePackPlatform.fromAndroid(context)
            val expectedRoot = File(platform.payloadPacksDirectory.canonicalFile, pack.id)
            check(payloadRoot.absoluteFile == expectedRoot && payloadRoot.canonicalFile == expectedRoot)
            val native = File(context.applicationInfo.nativeLibraryDir).canonicalFile
            val executable = File(native, pack.executable).canonicalFile
            check(executable.parentFile == native)
            val workerHash = pack.nativeSha256[pack.executable] ?: error("Worker pin missing")
            val policy = ProbeCoordinator.Policy(pack.id, pack.version, pack.pythonVersion,
                Build.VERSION.SDK_INT, pack.abi, executable.path, workerHash,
                "${context.packageName}:fixed-probe-evidence-v5", UUID.randomUUID().toString(),
                wallTimeLimitMs = 10000, outputLimitBytes = 8192)
            var report = ""
            var lifecycleConfirmed = false
            coordinator = ProbeCoordinator(policy,
                preflight = { RuntimePackPreflight(mapOf(pack.id to pack), platform).verify(it) },
                approval = { !stopped.get() && approved() },
                runner = ProbeCoordinator.Runner { id, claim ->
                    var phase = "owner-check"
                    var observedNative: NativeRunResult? = null
                    var failureDirectory: File? = null
                    var originalFailure: Exception? = null
                    var originalPhase: String? = null
                    try {
                    check(!stopped.get()) { "Owner stopped before launch" }
                    phase = "workspace-create"
                    val paths = EtRouteWorkspaceManager(context).create(id)
                    failureDirectory = paths.diagnostics
                    phase = "launch-prepare"
                    val launch = PreparedLaunch(executable.path,
                        listOf(if (destroyAfterReady) "--probe-wait" else "--probe", payloadRoot.path),
                        mapOf("LD_LIBRARY_PATH" to native.path, "TMPDIR" to paths.tmp.path),
                        paths.root.path, File(paths.diagnostics, "stdout.log").path,
                        File(paths.diagnostics, "stderr.log").path, timeoutMs = 10000,
                        limits = NativeResourceLimits(cpuSeconds = 5, maxOpenFiles = 64, maxFileBytes = 4096),
                        sessionId = id, requestId = id, originTag = "fixed-probe-evidence-v1")
                    var request: JniNativeSupervisor.Request? = null
                    var result: NativeRunResult? = null
                    val done = AtomicBoolean(false)
                    val ready = AtomicBoolean(false)
                    val watcher = if (destroyAfterReady) Thread {
                        val deadline = System.nanoTime() + 8_000_000_000L
                        while (!done.get() && !stopped.get() && System.nanoTime() < deadline) {
                            val bytes = runCatching { readBounded(File(launch.stdoutPath), 4096) }.getOrNull()
                            if (bytes != null && String(bytes, Charsets.UTF_8).lineSequence().any { it == "ETROUTE_PYTHON_PROBE_READY" }) {
                                ready.set(true); onReadyDestroy(); break
                            }
                            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                        }
                    } else null
                    phase = "in-flight-marker"
                    check(prefs.edit().putBoolean("uncertain", true).commit()) { "Unable to persist in-flight marker" }
                    try {
                        phase = "native-run"
                        val completed = EtRouteSessionRunner(context).run(launch) {
                            phase = "native-attach"
                            request = it
                            check(control.attach(it)) { "Owner closed before native attachment" }
                            watcher?.start()
                            phase = "native-run"
                        }.result
                        observedNative = completed
                        phase = "receipt-delivery"
                        result = ProbeCompletionReceipt.deliver(completed, simulateReceiptLoss)
                        done.set(true); watcher?.join(1000)
                        if (simulateReceiptLoss) {
                            check(completed.succeeded) { "Simulation requires actual normal native completion" }
                            File(paths.diagnostics, "native-result.txt").writeText(
                                "simulation=receipt-loss; actualNativeStage=${completed.stage}; actualExit=${completed.exitCode}; " +
                                "actualSignal=${completed.signal}; actualNativeTerminationConfirmed=${ProbeCompletionReceipt.confirmed(completed)}")
                        }
                        // Never truncate JSON and parse its prefix. Reject over-limit bytes before envelope construction.
                        val noChild = ProbeCompletionReceipt.noChildStarted(result)
                        phase = "stdout-read"
                        val stdout = if (noChild && !File(launch.stdoutPath).exists()) ByteArray(0)
                            else readBounded(File(launch.stdoutPath), 4096)
                        phase = "stderr-read"
                        val stderr = if (noChild && !File(launch.stderrPath).exists()) ByteArray(0)
                            else readBounded(File(launch.stderrPath), 4096)
                        val payload = stdout + stderr
                        check(payload.size <= 8192)
                        val confirmed = ProbeCompletionReceipt.confirmed(result)
                        val marker = String(stdout, Charsets.UTF_8).lineSequence().any { it.startsWith("ETROUTE_PYTHON_PROBE_OK ") }
                        val outcome = when {
                            simulateReceiptLoss -> "unknown"
                            noChild -> "not-started"
                            completed.cancelled -> "cancelled"
                            completed.timedOut -> "timed-out"
                            completed.succeeded && marker && stderr.isEmpty() && !stopped.get() -> "completed"
                            else -> "failed"
                        }
                        lifecycleConfirmed = completed.cancelled && confirmed && ready.get() && destroyed.get()
                        val observation = ProbeEvidenceValidator.Observation(id, pack.abi, Build.VERSION.SDK_INT,
                            policy.deviceBinding, Instant.now().toString(), outcome, completed.durationMs,
                            payload.size.toLong(), 0, result?.exitCode?.toLong(), result?.signal?.toLong(),
                            result?.stage?.wire?.toLong(), if (noChild) "not-started" else if (confirmed) "confirmed" else "unconfirmed", "retained")
                        phase = "envelope-build"
                        val submission = ProbeEvidenceSubmission.build(claim, observation, payload)
                        phase = "envelope-write"
                        File(paths.diagnostics, "probe-envelope.json").writeBytes(submission.envelope)
                        phase = "payload-write"
                        File(paths.diagnostics, "probe-payload.bin").writeBytes(submission.payload)
                        report = "simulation=${if (simulateReceiptLoss) "receipt-loss" else "none"} actualNativeTerminationConfirmed=${ProbeCompletionReceipt.confirmed(completed)}\n" +
                            "session=$id\nnativeStage=${completed.stage} durationMs=${completed.durationMs} termination=${observation.termination}\n" +
                            "destroyed=${destroyed.get()} ready=${ready.get()} diagnosticsRetained=true cleanupPerformed=false\n" +
                            "ENVELOPE:\n${String(submission.envelope, Charsets.UTF_8)}\nSTDOUT:\n${String(stdout, Charsets.UTF_8)}\nSTDERR:\n${String(stderr, Charsets.UTF_8)}"
                        submission
                    } catch (error: Exception) {
                        originalFailure = error; originalPhase = phase
                        throw error
                    } finally {
                        phase = "owner-finalization"
                        done.set(true); watcher?.interrupt(); watcher?.join(1000)
                        check(watcher?.isAlive != true) { "Readiness watcher did not end; chain must halt" }
                        val certain = ProbeCompletionReceipt.confirmed(result) || ProbeCompletionReceipt.noChildStarted(result)
                        request?.let { control.complete(it, certain) }
                        if (certain) check(prefs.edit().putBoolean("uncertain", false).commit()) { "Unable to persist confirmed completion" }
                    }
                    } catch (error: Exception) {
                        report = "session=$id\n" + ProbeFailureDiagnostics.describe(
                            if (error === originalFailure) originalPhase ?: phase else phase,
                            error, observedNative, stopped.get(), destroyed.get(), Thread.currentThread().isInterrupted) +
                            "\n" + SourceErrorContext.describe(context, error)
                        // Failure evidence never becomes Accepted; retain an independent bounded diagnostic when possible.
                        failureDirectory?.let { directory ->
                            runCatching { File(directory, "probe-runner-error.txt").writeText(report) }
                        }
                        throw error
                    }
                })
            val inspected = coordinator.inspect() as? ProbeCoordinator.Outcome.Inspected
                ?: return "ST08_EVIDENCE=REJECTED_PREFLIGHT\ngeneralScriptExecutionEnabled=false"
            val ticket = inspected.ticket
            val authorization = coordinator.authorize(ticket)
            if (authorization !is ProbeCoordinator.Outcome.Authorized)
                return "ST08_EVIDENCE=REJECTED_AUTHORIZATION\n$authorization\n" +
                    rejectionContext(authorization) + "\ngeneralScriptExecutionEnabled=false"
            val outcome = coordinator.dispatch(ticket)
            val validation = (outcome as? ProbeCoordinator.Outcome.Evidence)?.validation
            val validated = validation as? ProbeEvidenceValidator.Result.Validated
            val replayBlocked = (coordinator.dispatch(ticket) as? ProbeCoordinator.Outcome.Rejected)?.reason == ProbeCoordinator.Reason.WRONG_STATE
            val expected = validated != null && replayBlocked && if (simulateReceiptLoss)
                (!validated.successfulProbe && validated.retainSession && prefs.getBoolean("uncertain", false))
            else if (destroyAfterReady)
                (!validated.successfulProbe && validated.retainSession && lifecycleConfirmed) else validated.successfulProbe
            if (!expected && outcome is ProbeCoordinator.Outcome.Rejected) report += "\n" + rejectionContext(outcome)
            if (!expected && !report.contains("SOURCE_CONTEXT")) report +=
                "\nSOURCE_CONTEXT_UNAVAILABLE: outcome has no mapped exception location."
            if (simulateReceiptLoss) return "ST06_SIMULATED_RECEIPT_LOSS=${if (expected) "PASS" else "FAIL"}\n" +
                "validation=$validation replayBlocked=$replayBlocked uncertainMarker=${prefs.getBoolean("uncertain", false)}\n" +
                "$report\nactualNativeUncertaintyTested=false\ngeneralScriptExecutionEnabled=false"
            return "ST08_${if (destroyAfterReady) "LIFECYCLE" else "COMPLETE"}=${if (expected) "PASS" else "FAIL"}\n" +
                "validation=$validation coordinator=$outcome replayBlocked=$replayBlocked\n$report\ngeneralScriptExecutionEnabled=false"
        } finally {
            control.close(); coordinator?.close(); active.set(false)
        }
    }

    private fun rejectionContext(outcome: ProbeCoordinator.Outcome): String {
        val rejection = outcome as? ProbeCoordinator.Outcome.Rejected
        val location = rejection?.rejectionLocation
        return "registryDetail=${rejection?.registryDetail ?: "not-applicable"}\n" +
            if (location != null) SourceErrorContext.describeRejection(context, location)
            else "SOURCE_CONTEXT_UNAVAILABLE: rejection has no captured gate location."
    }

    companion object {
        private val active = AtomicBoolean(false)
        private fun readBounded(file: File, limit: Int): ByteArray {
            val bytes = ByteArray(limit + 1)
            val count = file.inputStream().use { input ->
                var total = 0
                while (total < bytes.size) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read < 0) break
                    total += read
                }
                total
            }
            check(count <= limit) { "Diagnostic byte bound exceeded" }
            return bytes.copyOf(count)
        }
    }
}
