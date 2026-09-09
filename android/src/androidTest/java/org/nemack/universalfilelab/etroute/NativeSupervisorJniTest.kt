package org.nemack.universalfilelab.etroute

import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeSupervisorJniTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val supervisor = JniNativeSupervisor()

    private fun launch(
        executable: String = "/system/bin/sh",
        arguments: List<String>,
        workingDirectory: File = context.cacheDir,
        timeoutMs: Long = 2_000,
        terminateGraceMs: Long = 100,
        limits: NativeResourceLimits = NativeResourceLimits()
    ): Pair<PreparedLaunch, NativeRunResult> {
        val id = UUID.randomUUID().toString()
        val stdout = File(context.cacheDir, "$id-stdout.log")
        val stderr = File(context.cacheDir, "$id-stderr.log")
        val prepared = PreparedLaunch(
            executable = executable,
            arguments = arguments,
            environment = mapOf(
                "PATH" to "/system/bin",
                "ETROUTE_JNI_TEST" to "1"
            ),
            workingDirectory = workingDirectory.absolutePath,
            stdoutPath = stdout.absolutePath,
            stderrPath = stderr.absolutePath,
            timeoutMs = timeoutMs,
            terminateGraceMs = terminateGraceMs,
            limits = limits,
            sessionId = "jni-$id",
            requestId = "request-$id",
            originTag = "androidTest:NativeSupervisorJniTest"
        )
        return prepared to supervisor.run(prepared)
    }

    @Test
    fun abiV1Negotiates() {
        assertEquals(1L, supervisor.abiVersionForValidation())
    }

    @Test
    fun successfulRunRoundTrips() {
        val (prepared, result) = launch(
            arguments = listOf("-c", "sleep 0.05; printf ETROUTE_JNI_SMOKE_OK")
        )

        assertTrue(result.succeeded)
        assertEquals(NativeSpawnStage.OK, result.stage)
        assertEquals(0, result.exitCode)
        assertNull(result.signal)
        assertFalse(result.timedOut)
        assertEquals(0, result.spawnErrno)
        assertTrue(result.durationMs > 0)
        assertEquals("ETROUTE_JNI_SMOKE_OK", File(prepared.stdoutPath).readText())
    }

    @Test
    fun execFailurePreservesErrno() {
        val (_, result) = launch(
            executable = "/system/bin/etroute-does-not-exist",
            arguments = emptyList()
        )

        assertEquals(NativeSpawnStage.EXECVE, result.stage)
        assertEquals(2, result.spawnErrno)
        assertEquals(127, result.exitCode)
        assertFalse(result.succeeded)
    }

    @Test
    fun chdirFailurePreservesErrno() {
        val missingDir = File(context.cacheDir, "missing-${UUID.randomUUID()}")
        val (_, result) = launch(
            arguments = listOf("-c", "true"),
            workingDirectory = missingDir
        )

        assertEquals(NativeSpawnStage.CHDIR, result.stage)
        assertEquals(2, result.spawnErrno)
        assertFalse(result.succeeded)
    }

    @Test
    fun timeoutKillsProcessGroup() {
        val leaderPid = File(context.cacheDir, "leader-${UUID.randomUUID()}.pid")
        val childPid = File(context.cacheDir, "child-${UUID.randomUUID()}.pid")
        val command = "echo \$\$ > ${leaderPid.absolutePath}; " +
            "sleep 30 & echo \$! > ${childPid.absolutePath}; wait"

        val (_, result) = launch(
            arguments = listOf("-c", command),
            timeoutMs = 150,
            terminateGraceMs = 100
        )

        assertEquals(NativeSpawnStage.TIMEOUT_KILL, result.stage)
        assertTrue(result.timedOut)
        assertFalse(result.succeeded)

        Thread.sleep(100)
        if (leaderPid.isFile) {
            val pid = leaderPid.readText().trim()
            assertFalse(File("/proc/$pid").exists())
        }
        if (childPid.isFile) {
            val pid = childPid.readText().trim()
            assertFalse(File("/proc/$pid").exists())
        }
    }

    @Test
    fun workingDirectoryAndOutputPermissionsAreCorrect() {
        val workDir = File(context.cacheDir, "cwd-${UUID.randomUUID()}").apply { mkdirs() }
        val (prepared, result) = launch(
            arguments = listOf("-c", "pwd"),
            workingDirectory = workDir
        )

        assertTrue(result.succeeded)
        assertEquals(workDir.canonicalPath, File(prepared.stdoutPath).readText().trim())

        val stdoutMode = Os.stat(prepared.stdoutPath).st_mode and 0x1FF
        val stderrMode = Os.stat(prepared.stderrPath).st_mode and 0x1FF
        assertEquals(0x180, stdoutMode)
        assertEquals(0x180, stderrMode)
    }

    @Test
    fun resourceLimitsReachChild() {
        val (prepared, result) = launch(
            arguments = listOf("-c", "ulimit -n"),
            limits = NativeResourceLimits(maxOpenFiles = 64)
        )

        assertTrue(result.succeeded)
        assertEquals("64", File(prepared.stdoutPath).readText().trim())
    }

    @Test
    fun sessionRunnerSmokeCreatesIntentionalOutput() {
        val run = EtRouteSessionRunner(context).runSystemSmoke()
        assertTrue(run.result.succeeded)
        assertEquals(
            "ETROUTE_ANDROID_SMOKE_OK",
            File(run.paths.diagnostics, "stdout.log").readText()
        )
        assertEquals(
            "ETROUTE_ANDROID_SMOKE_OK",
            File(run.paths.output, "etroute-smoke.txt").readText()
        )
    }

    @Test
    fun preparedLaunchValidatorRejectsWorkspaceEscape() {
        val sessionId = "escape-${UUID.randomUUID()}"
        val paths = EtRouteWorkspaceManager(context).create(sessionId)
        val launch = PreparedLaunch(
            executable = "/system/bin/sh",
            arguments = listOf("-c", "true"),
            environment = mapOf("PATH" to "/system/bin"),
            workingDirectory = context.cacheDir.absolutePath,
            stdoutPath = File(paths.diagnostics, "stdout.log").absolutePath,
            stderrPath = File(paths.diagnostics, "stderr.log").absolutePath,
            sessionId = sessionId,
            requestId = "request-${UUID.randomUUID()}",
            originTag = "androidTest:workspace-escape"
        )

        var rejected = false
        try {
            PreparedLaunchValidator(context, paths).validate(launch)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun finalizerPreservesOutputAndBoundsDiagnostics() {
        val sessionId = "finalize-${UUID.randomUUID()}"
        val paths = EtRouteWorkspaceManager(context).create(sessionId)
        File(paths.output, "result.txt").writeText("keep")
        File(paths.input, "input.txt").writeText("delete")
        File(paths.tmp, "tmp.txt").writeText("delete")
        File(paths.diagnostics, "stdout.log").writeText("x".repeat(4096))
        File(paths.diagnostics, "other.log").writeText("delete")

        val result = RuntimeSessionFinalizer().finalize(
            SessionFinalizationRequest(
                paths = paths,
                preserveOutput = true,
                retainDiagnostics = true,
                maxDiagnosticBytesPerFile = 1024
            )
        )

        assertTrue(result.cleaned)
        assertTrue(result.outputPreserved)
        assertEquals("keep", File(paths.output, "result.txt").readText())
        assertFalse(paths.input.exists())
        assertFalse(paths.tmp.exists())
        assertEquals(1024L, File(paths.diagnostics, "stdout.log").length())
        assertFalse(File(paths.diagnostics, "other.log").exists())
    }
}
