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
class JavaRuntimeAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun javaDependencyResolvesPinnedGenerationAndPlansAbsoluteLaunch() {
        val registry = RuntimePackRegistry()
        val javaPack = admit(createJavaPack(), "approve-java")
        registry.publish(javaPack)

        val apktoolPack = admit(
            createJarPack(
                dependency = RuntimePackDependency(
                    packId = javaPack.manifest.packId,
                    requiredGenerationId = javaPack.generationId
                )
            ),
            "approve-apktool"
        )
        registry.publish(apktoolPack)

        val apktoolLease = registry.acquire(apktoolPack.manifest.packId)
        val apktoolTool = apktoolLease.resolveTool("apktool")

        val java = JavaRuntimeResolver(registry).resolveFor(apktoolPack)
        try {
            assertEquals(javaPack.generationId, java.javaTool.generationId)
            assertTrue(java.javaTool.artifact.isAbsolute)
            assertEquals("bin", java.javaTool.artifact.parentFile!!.name)

            val session = EtRouteWorkspaceManager(context).create(
                "java-plan-${UUID.randomUUID()}"
            )
            val plan = JavaJarLaunchPlanner().plan(
                session = session,
                java = java,
                jarTool = apktoolTool,
                arguments = listOf("d", "input.apk", "-o", session.output.path),
                requestId = "request-java",
                originTag = "androidTest:java-plan",
                timeoutMs = 60_000
            )

            assertEquals(java.javaTool.artifact.canonicalPath, plan.preparedLaunch.executable)
            assertTrue(plan.preparedLaunch.arguments.contains("-jar"))
            assertTrue(plan.preparedLaunch.arguments.contains(apktoolTool.artifact.canonicalPath))
            assertFalse(plan.preparedLaunch.environment.containsKey("PATH"))
            assertEquals(java.javaHome.path, plan.preparedLaunch.environment["JAVA_HOME"])
            assertEquals(javaPack.generationId, plan.javaGenerationId)
            assertEquals(apktoolPack.generationId, plan.jarGenerationId)
        } finally {
            java.close()
            apktoolLease.close()
        }
    }

    private fun createJavaPack(): VerifiedRuntimePack {
        val root = File(context.cacheDir, "java-pack-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val executable = File(root, "bin/java").apply {
            parentFile!!.mkdirs()
            writeText("#!/system/bin/sh\nexit 0\n")
            check(setExecutable(true, true))
        }
        val hash = sha256(executable)

        val manifest = RuntimePackManifest(
            packId = JavaRuntimeResolver.DEFAULT_JAVA_PACK_ID,
            version = "17-test",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            abis = setOf("arm64-v8a"),
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "bin/java",
                    sha256 = hash,
                    sizeBytes = executable.length(),
                    executable = true
                )
            ),
            tools = listOf(
                ToolDescriptor(
                    toolId = JavaRuntimeResolver.DEFAULT_JAVA_TOOL_ID,
                    kind = RuntimePackToolKind.NATIVE_EXECUTABLE,
                    artifactPath = "bin/java"
                )
            )
        )

        return RuntimePackVerifier(
            RuntimePackPlatform(35, setOf("arm64-v8a"))
        ).verify(root, manifest)
    }

    private fun createJarPack(
        dependency: RuntimePackDependency
    ): VerifiedRuntimePack {
        val root = File(context.cacheDir, "apktool-pack-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val jar = File(root, "artifacts/apktool.jar").apply {
            parentFile!!.mkdirs()
            writeText("fake-jar")
        }
        val hash = sha256(jar)

        val manifest = RuntimePackManifest(
            packId = "org.etroute.apktool",
            version = "test",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            source = RuntimePackSource(
                repository = "iBotPeaches/Apktool",
                revision = "test",
                assetName = "apktool.jar",
                assetSha256 = hash
            ),
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/apktool.jar",
                    sha256 = hash,
                    sizeBytes = jar.length()
                )
            ),
            dependencies = listOf(dependency),
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
