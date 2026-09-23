package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Aapt2SmokePlannerAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun smokePlanUsesPinnedExecutableAndSessionPaths() {
        val verified = createAapt2Pack()
        val admitted = AdmittedRuntimePack(
            verified = verified,
            trustApproval = RuntimePackTrustApproval(
                decision = RuntimePackTrustDecision.APPROVED,
                approvalId = "approve-aapt2-smoke",
                decidedAtEpochMs = 1
            )
        )
        val registry = RuntimePackRegistry().apply {
            publish(admitted)
        }
        val binding = Aapt2RuntimeResolver(registry).resolveFor(admitted)

        try {
            val session = EtRouteWorkspaceManager(context).create(
                "aapt2-smoke-${UUID.randomUUID()}"
            )
            val resDir = File(session.input, "res").apply {
                check(mkdirs())
                File(this, "values").mkdirs()
                File(File(this, "values"), "strings.xml").writeText(
                    "<resources><string name=\"app_name\">ETroute</string></resources>"
                )
            }
            val manifest = File(session.input, "AndroidManifest.xml").apply {
                writeText(
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                        "package=\"org.etroute.fixture\"><application /></manifest>"
                )
            }
            val androidJar = File(session.input, "android.jar").apply {
                writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            }

            val plan = Aapt2SmokePlanner().plan(
                session = session,
                aapt2 = binding,
                resourceDirectory = resDir,
                manifestFile = manifest,
                androidJar = androidJar,
                requestId = "request-aapt2",
                originTag = "androidTest:aapt2-smoke"
            )

            val expectedExecutable = binding.aapt2Tool.artifact.canonicalPath
            assertEquals(expectedExecutable, plan.versionLaunch.executable)
            assertEquals(expectedExecutable, plan.compileLaunch.executable)
            assertEquals(expectedExecutable, plan.linkLaunch.executable)
            assertEquals(verified.generationId, plan.aapt2GenerationId)

            assertEquals(listOf("version"), plan.versionLaunch.arguments)
            assertTrue(plan.compileLaunch.arguments.contains("--dir"))
            assertTrue(plan.compileLaunch.arguments.contains(resDir.canonicalPath))
            assertTrue(plan.linkLaunch.arguments.contains("-I"))
            assertTrue(plan.linkLaunch.arguments.contains(androidJar.canonicalPath))
            assertTrue(plan.linkLaunch.arguments.contains(manifest.canonicalPath))
            assertTrue(plan.compiledArchive.path.startsWith(session.tmp.canonicalPath))
            assertTrue(plan.linkedApk.path.startsWith(session.output.canonicalPath))
            assertFalse(plan.versionLaunch.environment.containsKey("PATH"))
        } finally {
            binding.close()
        }
    }

    private fun createAapt2Pack(): VerifiedRuntimePack {
        val root = File(context.cacheDir, "aapt2-smoke-pack-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val executable = File(root, "bin/aapt2").apply {
            parentFile!!.mkdirs()
            writeText("#!/system/bin/sh\nexit 0\n")
            check(setExecutable(true, true))
        }
        val hash = sha256(executable)

        return RuntimePackVerifier(
            RuntimePackPlatform(35, setOf("arm64-v8a"))
        ).verify(
            root,
            RuntimePackManifest(
                packId = Aapt2RuntimeResolver.DEFAULT_AAPT2_PACK_ID,
                version = "test",
                provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
                minApi = 24,
                abis = setOf("arm64-v8a"),
                artifacts = listOf(
                    RuntimePackArtifact(
                        path = "bin/aapt2",
                        sha256 = hash,
                        sizeBytes = executable.length(),
                        executable = true
                    )
                ),
                tools = listOf(
                    ToolDescriptor(
                        toolId = Aapt2RuntimeResolver.DEFAULT_AAPT2_TOOL_ID,
                        kind = RuntimePackToolKind.NATIVE_EXECUTABLE,
                        artifactPath = "bin/aapt2"
                    )
                )
            )
        )
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
}
