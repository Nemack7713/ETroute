package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimePackAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun canonicalManifestIdentityIsOrderStable() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)

        val first = RuntimePackManifest(
            packId = "org.etroute.example",
            version = "1.0.0",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            abis = setOf("x86_64", "arm64-v8a"),
            artifacts = listOf(
                RuntimePackArtifact("bin/b", hashB, 2, executable = true),
                RuntimePackArtifact("bin/a", hashA, 1, executable = true)
            ),
            tools = listOf(
                ToolDescriptor("b", RuntimePackToolKind.NATIVE_EXECUTABLE, "bin/b"),
                ToolDescriptor("a", RuntimePackToolKind.NATIVE_EXECUTABLE, "bin/a")
            )
        )

        val second = first.copy(
            abis = setOf("arm64-v8a", "x86_64"),
            artifacts = first.artifacts.reversed(),
            tools = first.tools.reversed()
        )

        assertEquals(first.generationId(), second.generationId())
        assertEquals(
            first.canonicalBytes().decodeToString(),
            second.canonicalBytes().decodeToString()
        )
    }

    @Test
    fun verifierRejectsArtifactHashMismatch() {
        val root = newPackRoot()
        val artifact = File(root, "artifacts/tool.jar").apply {
            parentFile!!.mkdirs()
            writeText("verified-content")
        }

        val manifest = RuntimePackManifest(
            packId = "org.etroute.hash-test",
            version = "1",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/tool.jar",
                    sha256 = "0".repeat(64),
                    sizeBytes = artifact.length()
                )
            ),
            tools = listOf(
                ToolDescriptor(
                    toolId = "tool",
                    kind = RuntimePackToolKind.JAVA_JAR,
                    artifactPath = "artifacts/tool.jar"
                )
            )
        )

        var rejected = false
        try {
            RuntimePackVerifier(
                RuntimePackPlatform(apiLevel = 35, supportedAbis = setOf("arm64-v8a"))
            ).verify(root, manifest)
        } catch (_: RuntimePackVerificationException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun verifiedPackRequiresExplicitTrustBeforeAdmission() {
        val verified = createVerifiedJarPack("org.etroute.trust-test", "one")

        var rejected = false
        try {
            AdmittedRuntimePack(
                verified = verified,
                trustApproval = RuntimePackTrustApproval(
                    decision = RuntimePackTrustDecision.REJECTED,
                    approvalId = "trust-rejected",
                    decidedAtEpochMs = 1
                )
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)

        val admitted = AdmittedRuntimePack(
            verified = verified,
            trustApproval = RuntimePackTrustApproval(
                decision = RuntimePackTrustDecision.APPROVED,
                approvalId = "trust-approved",
                decidedAtEpochMs = 2
            )
        )

        assertEquals(verified.generationId, admitted.generationId)
    }

    @Test
    fun generationLeasePinsOldGenerationAcrossActivation() {
        val first = admit(createVerifiedJarPack("org.etroute.lease-test", "one"), "approve-one")
        val second = admit(createVerifiedJarPack("org.etroute.lease-test", "two"), "approve-two")
        assertNotEquals(first.generationId, second.generationId)

        val registry = RuntimePackRegistry()
        registry.publish(first, activate = true)

        val lease = registry.acquire(first.manifest.packId)
        assertEquals(1, registry.leaseCount(first.manifest.packId, first.generationId))

        registry.publish(second, activate = true)
        assertEquals(second.generationId, registry.activeGeneration(first.manifest.packId))
        assertFalse(registry.retire(first.manifest.packId, first.generationId))

        val resolved = lease.resolveTool("apktool")
        assertEquals(first.generationId, resolved.generationId)
        assertTrue(resolved.artifact.isFile)

        lease.close()
        assertEquals(0, registry.leaseCount(first.manifest.packId, first.generationId))
        assertTrue(registry.retire(first.manifest.packId, first.generationId))
    }

    @Test
    fun dependencyMustAlreadyBePublished() {
        val dependency = admit(
            createVerifiedJarPack("org.etroute.java", "java"),
            "approve-java"
        )
        val apktoolVerified = createVerifiedJarPack(
            packId = "org.etroute.apktool",
            payload = "apktool",
            dependencies = listOf(
                RuntimePackDependency(
                    packId = dependency.manifest.packId,
                    requiredGenerationId = dependency.generationId
                )
            )
        )
        val apktool = admit(apktoolVerified, "approve-apktool")

        val registry = RuntimePackRegistry()
        var rejected = false
        try {
            registry.publish(apktool)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        registry.publish(dependency)
        registry.publish(apktool)
        assertEquals(
            apktool.generationId,
            registry.activeGeneration(apktool.manifest.packId)
        )
    }

    private fun createVerifiedJarPack(
        packId: String,
        payload: String,
        dependencies: List<RuntimePackDependency> = emptyList()
    ): VerifiedRuntimePack {
        val root = newPackRoot()
        val artifact = File(root, "artifacts/apktool.jar").apply {
            parentFile!!.mkdirs()
            writeText(payload)
        }
        val hash = sha256(artifact)

        val manifest = RuntimePackManifest(
            packId = packId,
            version = "test-$payload",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            source = RuntimePackSource(
                repository = "example/$packId",
                revision = "test",
                assetName = "apktool.jar",
                assetSha256 = hash
            ),
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/apktool.jar",
                    sha256 = hash,
                    sizeBytes = artifact.length()
                )
            ),
            dependencies = dependencies,
            tools = listOf(
                ToolDescriptor(
                    toolId = "apktool",
                    kind = RuntimePackToolKind.JAVA_JAR,
                    artifactPath = "artifacts/apktool.jar"
                )
            )
        )

        return RuntimePackVerifier(
            RuntimePackPlatform(apiLevel = 35, supportedAbis = setOf("arm64-v8a"))
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

    private fun newPackRoot(): File =
        File(context.cacheDir, "runtime-pack-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
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
