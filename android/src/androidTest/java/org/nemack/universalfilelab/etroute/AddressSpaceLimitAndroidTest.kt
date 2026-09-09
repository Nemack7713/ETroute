package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AddressSpaceLimitAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val supervisor = JniNativeSupervisor()

    @Test
    fun defaultDoesNotInjectAddressSpaceLimitAndExplicitLimitStillApplies() {
        val defaultOutput = runUlimit(NativeResourceLimits())

        assertTrue(defaultOutput.isNotBlank())
        assertTrue(
            defaultOutput.equals("unlimited", ignoreCase = true) ||
                defaultOutput.toLongOrNull() != null
        )

        val explicitBytes = 512L * 1024L * 1024L * 1024L
        val explicitOutput = runUlimit(
            NativeResourceLimits(maxAddressSpaceBytes = explicitBytes)
        )

        assertTrue(explicitOutput.isNotBlank())
        assertFalse(explicitOutput.equals("unlimited", ignoreCase = true))
        assertTrue(explicitOutput.toLongOrNull() != null)
        assertNotEquals(defaultOutput, explicitOutput)
    }

    private fun runUlimit(limits: NativeResourceLimits): String {
        val id = UUID.randomUUID().toString()
        val stdout = File(context.cacheDir, "$id-stdout.log")
        val stderr = File(context.cacheDir, "$id-stderr.log")
        val launch = PreparedLaunch(
            executable = "/system/bin/sh",
            arguments = listOf("-c", "ulimit -v"),
            environment = mapOf("PATH" to "/system/bin"),
            workingDirectory = context.cacheDir.absolutePath,
            stdoutPath = stdout.absolutePath,
            stderrPath = stderr.absolutePath,
            timeoutMs = 2_000,
            terminateGraceMs = 100,
            limits = limits,
            sessionId = "rlimit-$id",
            requestId = "request-$id",
            originTag = "androidTest:AddressSpaceLimitAndroidTest"
        )

        val result = supervisor.run(launch)
        check(result.succeeded) {
            "ulimit probe failed: result=$result stderr=${stderr.takeIf { it.isFile }?.readText()}"
        }
        return stdout.readText().trim()
    }
}
