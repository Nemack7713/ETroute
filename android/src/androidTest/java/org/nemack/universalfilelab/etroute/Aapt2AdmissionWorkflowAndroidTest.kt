package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Aapt2AdmissionWorkflowAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun successRunsVersionCompileLinkAndWritesCombinedEvidence() {
        val fixture = createFixture()
        val binding = createBinding()
        val commands = mutableListOf<String>()
        val finalized = mutableListOf<Pair<String, Boolean>>()

        val executor = Aapt2StepExecutor { paths, launch, tool ->
            val command = launch.arguments.first()
            commands += command

            when (command) {
                "compile" -> {
                    val output = File(launch.arguments[launch.arguments.indexOf("-o") + 1])
                    output.parentFile?.mkdirs()
                    output.writeBytes("compiled".toByteArray())
                }

                "link" -> {
                    val output = File(launch.arguments[launch.arguments.indexOf("-o") + 1])
                    output.parentFile?.mkdirs()
                    output.writeBytes("apk".toByteArray())
                }
            }

            successRun(paths, launch, tool)
        }
        val finalizer = Aapt2StepFinalizer { paths, preserveOutput ->
            finalized += paths.sessionId to preserveOutput
        }

        try {
            val workflowId = "aapt2-success-${UUID.randomUUID()}"
            val result = Aapt2AdmissionWorkflow(
                context = context,
                executor = executor,
                stepFinalizer = finalizer
            ).run(
                aapt2 = binding,
                fixture = fixture,
                workflowId = workflowId,
                requestId = "request-success",
                originTag = "androidTest:aapt2-admission"
            )

            assertEquals(
                Aapt2AdmissionWorkflowStatus.SMOKE_VERIFIED,
                result.status
            )
            assertTrue(result.smokeVerified)
            assertEquals(listOf("version", "compile", "link"), commands)
            assertEquals(3, finalized.size)
            assertEquals(false, finalized[0].second)
            assertEquals(false, finalized[1].second)
            assertEquals(true, finalized[2].second)
            assertNotNull(result.linkedApk)
            assertTrue(result.linkedApk!!.isFile)
            assertNotNull(result.linkedApkSha256)
            assertEquals(64, result.linkedApkSha256!!.length)

            val evidence = JSONObject(result.evidenceFile.readText())
            assertEquals("SMOKE_VERIFIED", evidence.getString("status"))
            assertTrue(evidence.getBoolean("runtimeProbePassed"))
            assertTrue(evidence.getBoolean("compileSmokePassed"))
            assertTrue(evidence.getBoolean("linkSmokePassed"))
            assertFalse(evidence.getBoolean("trustGrantedByWorkflow"))
            assertFalse(evidence.getBoolean("publishedByWorkflow"))
            assertEquals(
                binding.aapt2Tool.generationId.value,
                evidence.getString("generationId")
            )
        } finally {
            binding.close()
        }
    }

    @Test
    fun compileFailureStopsBeforeLink() {
        val fixture = createFixture()
        val binding = createBinding()
        val commands = mutableListOf<String>()

        val executor = Aapt2StepExecutor { paths, launch, tool ->
            val command = launch.arguments.first()
            commands += command
            if (command == "compile") {
                failedRun(paths, launch, tool, exitCode = 2)
            } else {
                successRun(paths, launch, tool)
            }
        }

        try {
            val result = Aapt2AdmissionWorkflow(
                context = context,
                executor = executor,
                stepFinalizer = Aapt2StepFinalizer { _, _ -> }
            ).run(
                aapt2 = binding,
                fixture = fixture,
                workflowId = "aapt2-compile-fail-${UUID.randomUUID()}",
                requestId = "request-compile-fail"
            )

            assertEquals(
                Aapt2AdmissionWorkflowStatus.COMPILE_FAILED,
                result.status
            )
            assertEquals(listOf("version", "compile"), commands)
            assertFalse(result.smokeVerified)
            assertNotNull(result.versionReport)
            assertNotNull(result.compileReport)
            assertNull(result.linkReport)
            assertNull(result.linkedApkSha256)

            val evidence = JSONObject(result.evidenceFile.readText())
            assertTrue(evidence.getBoolean("runtimeProbePassed"))
            assertFalse(evidence.getBoolean("compileSmokePassed"))
            assertFalse(evidence.getBoolean("linkSmokePassed"))
        } finally {
            binding.close()
        }
    }

    @Test
    fun linkFailurePreservesCompileSuccessButDoesNotVerifyWorkflow() {
        val fixture = createFixture()
        val binding = createBinding()
        val commands = mutableListOf<String>()

        val executor = Aapt2StepExecutor { paths, launch, tool ->
            val command = launch.arguments.first()
            commands += command

            if (command == "compile") {
                val output = File(launch.arguments[launch.arguments.indexOf("-o") + 1])
                output.parentFile?.mkdirs()
                output.writeBytes("compiled".toByteArray())
                successRun(paths, launch, tool)
            } else if (command == "link") {
                failedRun(paths, launch, tool, exitCode = 3)
            } else {
                successRun(paths, launch, tool)
            }
        }

        try {
            val result = Aapt2AdmissionWorkflow(
                context = context,
                executor = executor,
                stepFinalizer = Aapt2StepFinalizer { _, _ -> }
            ).run(
                aapt2 = binding,
                fixture = fixture,
                workflowId = "aapt2-link-fail-${UUID.randomUUID()}",
                requestId = "request-link-fail"
            )

            assertEquals(
                Aapt2AdmissionWorkflowStatus.LINK_FAILED,
                result.status
            )
            assertEquals(listOf("version", "compile", "link"), commands)
            assertFalse(result.smokeVerified)
            assertEquals(
                TerminationClass.TERM_OK,
                result.compileReport!!.termination
            )
            assertEquals(
                TerminationClass.EXIT_NONZERO,
                result.linkReport!!.termination
            )
            assertNull(result.linkedApkSha256)

            val evidence = JSONObject(result.evidenceFile.readText())
            assertTrue(evidence.getBoolean("runtimeProbePassed"))
            assertTrue(evidence.getBoolean("compileSmokePassed"))
            assertFalse(evidence.getBoolean("linkSmokePassed"))
        } finally {
            binding.close()
        }
    }

    private fun successRun(
        paths: RuntimeSessionPaths,
        launch: PreparedLaunch,
        tool: ResolvedTool
    ): EtRouteSessionRun =
        buildRun(paths, launch, tool, exitCode = 0)

    private fun failedRun(
        paths: RuntimeSessionPaths,
        launch: PreparedLaunch,
        tool: ResolvedTool,
        exitCode: Int
    ): EtRouteSessionRun =
        buildRun(paths, launch, tool, exitCode)

    private fun buildRun(
        paths: RuntimeSessionPaths,
        launch: PreparedLaunch,
        tool: ResolvedTool,
        exitCode: Int
    ): EtRouteSessionRun {
        val result = NativeRunResult(
            exitCode = exitCode,
            signal = null,
            timedOut = false,
            durationMs = 1,
            spawnErrno = 0,
            stage = NativeSpawnStage.OK
        )
        val report = RunReport.from(
            launch = launch,
            result = result,
            resolvedTool = tool,
            toolchainGenerations = mapOf(
                "aapt2" to tool.generationId
            )
        )
        return EtRouteSessionRun(
            paths = paths,
            launch = launch,
            result = result,
            report = report
        )
    }

    private fun createFixture(): Aapt2AdmissionFixture {
        val root = File(
            context.cacheDir,
            "aapt2-admission-fixture-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }
        val resources = File(root, "res").apply {
            check(mkdirs())
            val values = File(this, "values").apply { check(mkdirs()) }
            File(values, "strings.xml").writeText(
                "<resources><string name=\"app_name\">ETroute</string></resources>"
            )
        }
        val manifest = File(root, "AndroidManifest.xml").apply {
            writeText(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "package=\"org.etroute.fixture\"><application /></manifest>"
            )
        }
        val androidJar = File(root, "android.jar").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }

        return Aapt2AdmissionFixture(
            resources = resources,
            manifest = manifest,
            androidJar = androidJar
        )
    }

    private fun createBinding(): Aapt2RuntimeBinding {
        val root = File(
            context.cacheDir,
            "aapt2-admission-pack-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }
        val executable = File(root, "bin/aapt2").apply {
            parentFile!!.mkdirs()
            writeText("#!/system/bin/sh\nexit 0\n")
            check(setExecutable(true, true))
        }
        val hash = sha256(executable)

        val verified = RuntimePackVerifier(
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

        val admitted = AdmittedRuntimePack(
            verified = verified,
            trustApproval = RuntimePackTrustApproval(
                decision = RuntimePackTrustDecision.APPROVED,
                approvalId = "approve-aapt2-workflow",
                decidedAtEpochMs = 1
            )
        )
        val registry = RuntimePackRegistry().apply {
            publish(admitted)
        }
        val lease = registry.acquire(admitted.manifest.packId)
        return Aapt2RuntimeBinding(
            lease = lease,
            aapt2Tool = lease.resolveTool(
                Aapt2RuntimeResolver.DEFAULT_AAPT2_TOOL_ID
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
