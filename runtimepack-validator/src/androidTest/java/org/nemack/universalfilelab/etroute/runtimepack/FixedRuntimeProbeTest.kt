package org.nemack.universalfilelab.etroute.runtimepack

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.nemack.universalfilelab.etroute.*

// Explicitly invoked feasibility instrumentation. Not production probe authorization or default enablement.
@RunWith(AndroidJUnit4::class)
class FixedRuntimeProbeTest {
    @Test fun preflightThenFixedStartupImportsThroughAbsoluteExec() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val candidate = Candidate.installAndInspect(context)
        assertEquals(RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE, candidate.report.status)
        val id = "python-probe-" + UUID.randomUUID().toString()
        val paths = EtRouteWorkspaceManager(context).create(id)
        val native = File(context.applicationInfo.nativeLibraryDir)
        val launch = PreparedLaunch(File(native, candidate.spec.executable).absolutePath,
            listOf("--probe", candidate.root.absolutePath),
            mapOf("LD_LIBRARY_PATH" to native.absolutePath, "TMPDIR" to paths.tmp.absolutePath),
            paths.root.absolutePath, File(paths.diagnostics, "stdout.log").absolutePath,
            File(paths.diagnostics, "stderr.log").absolutePath,
            timeoutMs = 10_000, limits = NativeResourceLimits(cpuSeconds = 5, maxOpenFiles = 64, maxFileBytes = 8192),
            sessionId = id, requestId = id, originTag = "runtimepack-feasibility-instrumentation")
        val stop = NativeStopController<JniNativeSupervisor.Request> { it.cancel() }
        var request: JniNativeSupervisor.Request? = null
        var result: NativeRunResult? = null
        try {
            val runResult = EtRouteSessionRunner(context).run(launch) { request = it; check(stop.attach(it)) }.result
            result = runResult
            val stdout = File(launch.stdoutPath).readText().take(8192)
            val stderr = File(launch.stderrPath).readText().take(8192)
            assertTrue("$result stderr=$stderr", runResult.succeeded)
            assertTrue(stdout, "ETROUTE_PYTHON_PROBE_OK" in stdout)
        } finally {
            stop.close()
            request?.let { stop.complete(it, result != null && result?.stage != NativeSpawnStage.CLEANUP_UNCONFIRMED) }
            // Always retain feasibility evidence; no automatic cleanup or admission.
        }
    }
}
