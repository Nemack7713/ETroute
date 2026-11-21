package org.nemack.universalfilelab.etroute

import android.content.Context
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
    fun run(destroyAfterReady: Boolean, approved: () -> Boolean, onReadyDestroy: () -> Unit = {}): String {
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
                "${context.packageName}:fixed-probe-evidence-v4", UUID.randomUUID().toString(),
                wallTimeLimitMs = 10000, outputLimitBytes = 8192)
            var report = ""
            var lifecycleConfirmed = false
            coordinator = ProbeCoordinator(policy,
                preflight = { RuntimePackPreflight(mapOf(pack.id to pack), platform).verify(it) },
                approval = { !stopped.get() && approved() },
                runner = ProbeCoordinator.Runner { id, claim ->
                    check(!stopped.get()) { "Owner stopped before launch" }
                    val paths = EtRouteWorkspaceManager(context).create(id)
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
                    check(prefs.edit().putBoolean("uncertain", true).commit())
                    try {
                        val completed = EtRouteSessionRunner(context).run(launch) {
                            request = it; check(control.attach(it)); watcher?.start()
                        }.result
                        result = completed; done.set(true); watcher?.join(1000)
                        // Never truncate JSON and parse its prefix. Reject over-limit bytes before envelope construction.
                        val stdout = readBounded(File(launch.stdoutPath), 4096)
                        val stderr = readBounded(File(launch.stderrPath), 4096)
                        val payload = stdout + stderr
                        check(payload.size <= 8192)
                        val confirmed = confirmed(completed)
                        val marker = String(stdout, Charsets.UTF_8).lineSequence().any { it.startsWith("ETROUTE_PYTHON_PROBE_OK ") }
                        val outcome = when {
                            completed.cancelled -> "cancelled"
                            completed.timedOut -> "timed-out"
                            completed.succeeded && marker && stderr.isEmpty() && !stopped.get() -> "completed"
                            else -> "failed"
                        }
                        lifecycleConfirmed = completed.cancelled && confirmed && ready.get() && destroyed.get()
                        val observation = ProbeEvidenceValidator.Observation(id, pack.abi, Build.VERSION.SDK_INT,
                            policy.deviceBinding, Instant.now().toString(), outcome, completed.durationMs,
                            payload.size.toLong(), 0, completed.exitCode?.toLong(), completed.signal?.toLong(),
                            completed.stage.wire.toLong(), if (confirmed) "confirmed" else "unconfirmed", "retained")
                        val submission = ProbeEvidenceSubmission.build(claim, observation, payload)
                        File(paths.diagnostics, "probe-envelope.json").writeBytes(submission.envelope)
                        File(paths.diagnostics, "probe-payload.bin").writeBytes(submission.payload)
                        report = "session=$id\nnativeStage=${completed.stage} durationMs=${completed.durationMs} termination=${observation.termination}\n" +
                            "destroyed=${destroyed.get()} ready=${ready.get()} diagnosticsRetained=true cleanupPerformed=false\n" +
                            "ENVELOPE:\n${String(submission.envelope, Charsets.UTF_8)}\nSTDOUT:\n${String(stdout, Charsets.UTF_8)}\nSTDERR:\n${String(stderr, Charsets.UTF_8)}"
                        submission
                    } finally {
                        done.set(true); watcher?.interrupt(); watcher?.join(1000)
                        val certain = result?.let(::confirmed) == true
                        request?.let { control.complete(it, certain) }
                        if (certain) check(prefs.edit().putBoolean("uncertain", false).commit())
                    }
                })
            val inspected = coordinator.inspect() as? ProbeCoordinator.Outcome.Inspected
                ?: return "ST08_EVIDENCE=REJECTED_PREFLIGHT\ngeneralScriptExecutionEnabled=false"
            val ticket = inspected.ticket
            val authorization = coordinator.authorize(ticket)
            if (authorization !is ProbeCoordinator.Outcome.Authorized)
                return "ST08_EVIDENCE=REJECTED_AUTHORIZATION\n$authorization\ngeneralScriptExecutionEnabled=false"
            val outcome = coordinator.dispatch(ticket)
            val validation = (outcome as? ProbeCoordinator.Outcome.Evidence)?.validation
            val validated = validation as? ProbeEvidenceValidator.Result.Validated
            val replayBlocked = (coordinator.dispatch(ticket) as? ProbeCoordinator.Outcome.Rejected)?.reason == ProbeCoordinator.Reason.WRONG_STATE
            val expected = validated != null && replayBlocked && if (destroyAfterReady)
                (!validated.successfulProbe && validated.retainSession && lifecycleConfirmed) else validated.successfulProbe
            return "ST08_${if (destroyAfterReady) "LIFECYCLE" else "COMPLETE"}=${if (expected) "PASS" else "FAIL"}\n" +
                "validation=$validation coordinator=$outcome replayBlocked=$replayBlocked\n$report\ngeneralScriptExecutionEnabled=false"
        } finally {
            control.close(); coordinator?.close(); active.set(false)
        }
    }

    companion object {
        private val active = AtomicBoolean(false)
        private fun confirmed(r: NativeRunResult) = r.stage !in setOf(NativeSpawnStage.CLEANUP_UNCONFIRMED,
            NativeSpawnStage.WAITPID, NativeSpawnStage.ERROR_PIPE) && (r.exitCode != null || r.signal != null)
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
