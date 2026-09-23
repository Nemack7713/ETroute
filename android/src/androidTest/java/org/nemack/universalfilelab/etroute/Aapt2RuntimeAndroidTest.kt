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
class Aapt2RuntimeAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun apktoolPlanPinsJavaAndAapt2AndInjectsAbsoluteAaptPath() {
        val registry = RuntimePackRegistry()

        val javaPack = admit(createNativePack(
            packId = JavaRuntimeResolver.DEFAULT_JAVA_PACK_ID,
            toolId = JavaRuntimeResolver.DEFAULT_JAVA_TOOL_ID,
            relativePath = "bin/java",
            payload = "#!/system/bin/sh\nexit 0\n"
        ), "approve-java")
        registry.publish(javaPack)

        val aapt2Pack = admit(createNativePack(
            packId = Aapt2RuntimeResolver.DEFAULT_AAPT2_PACK_ID,
            toolId = Aapt2RuntimeResolver.DEFAULT_AAPT2_TOOL_ID,
            relativePath = "bin/aapt2",
            payload = "#!/system/bin/sh\nexit 0\n"
        ), "approve-aapt2")
        registry.publish(aapt2Pack)

        val apktoolPack = admit(createApktoolPack(
            javaDependency = RuntimePackDependency(
                javaPack.manifest.packId,
                javaPack.generationId
            ),
            aapt2Dependency = RuntimePackDependency(
                aapt2Pack.manifest.packId,
                aapt2Pack.generationId
            )
        ), "approve-apktool")
        registry.publish(apktoolPack)

        val apktoolLease = registry.acquire(apktoolPack.manifest.packId)
        val apktool = apktoolLease.resolveTool("apktool")
        val java = JavaRuntimeResolver(registry).resolveFor(apktoolPack)
        val aapt2 = Aapt2RuntimeResolver(registry).resolveFor(apktoolPack)

        try {
            val session = EtRouteWorkspaceManager(context).create(
                "apktool-plan-${UUID.randomUUID()}"
            )

            val plan = ApktoolLaunchPlanner().plan(
                session = session,
                java = java,
                aapt2 = aapt2,
                apktool = apktool,
                arguments = listOf("b", session.input.path, "-o", File(session.output, "out.apk").path),
                requestId = "request-apktool",
                originTag = "androidTest:apktool-plan",
                timeoutMs = 60_000
            )

            assertEquals(javaPack.generationId, plan.javaGenerationId)
            assertEquals(aapt2Pack.generationId, plan.aapt2GenerationId)
            assertEquals(apktoolPack.generationId, plan.apktoolGenerationId)

            val args = plan.preparedLaunch.arguments
            val aaptIndex = args.indexOf("--aapt")
            assertTrue(aaptIndex >= 0)
            assertEquals(
                aapt2.aapt2Tool.artifact.canonicalPath,
                args[aaptIndex + 1]
            )
            assertTrue(aapt2.aapt2Tool.artifact.isAbsolute)
            assertFalse(plan.preparedLaunch.environment.containsKey("PATH"))
        } finally {
            aapt2.close()
            java.close()
            apktoolLease.close()
        }
    }

    private fun createNativePack(
        packId: String,
        toolId: String,
        relativePath: String,
        payload: String
    ): VerifiedRuntimePack {
        val root = File(context.cacheDir, "native-pack-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val executable = File(root, relativePath).apply {
            parentFile!!.mkdirs()
            writeText(payload)
            check(setExecutable(true, true))
        }
        val hash = sha256(executable)

        val manifest = RuntimePackManifest(
            packId = packId,
            version = "test",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            abis = setOf("arm64-v8a"),
            artifacts = listOf(
                RuntimePackArtifact(
                    path = relativePath,
                    sha256 = hash,
                    sizeBytes = executable.length(),
                    executable = true
                )
            ),
            tools = listOf(
                ToolDescriptor(
                    toolId = toolId,
                    kind = RuntimePackToolKind.NATIVE_EXECUTABLE,
                    artifactPath = relativePath
                )
            )
        )

        return RuntimePackVerifier(
            RuntimePackPlatform(35, setOf("arm64-v8a"))
        ).verify(root, manifest)
    }

    private fun createApktoolPack(
        javaDependency: RuntimePackDependency,
        aapt2Dependency: RuntimePackDependency
    ): VerifiedRuntimePack {
        val root = File(context.cacheDir, "apktool-pack-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val jar = File(root, "artifacts/apktool.jar").apply {
            parentFile!!.mkdirs()
            writeText("apktool-fixture")
        }
        val hash = sha256(jar)

        val manifest = RuntimePackManifest(
            packId = "org.etroute.apktool",
            version = "test",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/apktool.jar",
                    sha256 = hash,
                    sizeBytes = jar.length()
                )
            ),
            dependencies = listOf(javaDependency, aapt2Dependency),
            tools = listOf(
                ToolDescriptor(
                    toolId = "apktool",
                    kind = RuntimePackToolKind.JAVA_JAR,
                    artifactPath = "artifacts/apktool.jar"
                )
            )
        )

        return RuntimePackVerifier(
            RuntimePackPlatform(35, setOf("arm64-v8a"))
        ).verify(root, manifest)
    }

    private fun admit(
        verified: VerifiedRuntimePack,
        approvalId: String
    ): AdmittedRuntimePack =
        AdmittedRuntimePack(
            verified = verified,
            trustApproval = RuntimePackTrustApproval(
                decision = RuntimePackTrustDecision.APPROVED,
                approvalId = approvalId,
                decidedAtEpochMs = 1
            )
        )

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
