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
class RuntimePackPublisherAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun approvedCandidatePublishesImmutableGenerationAndReusesIt() {
        val candidate = File(
            context.cacheDir,
            "publisher-candidate-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }
        val artifact = File(candidate, "artifacts/tool.jar").apply {
            parentFile!!.mkdirs()
            writeText("publisher-fixture")
        }
        val hash = sha256(artifact)
        val manifest = RuntimePackManifest(
            packId = "org.etroute.publisher-test-${UUID.randomUUID()}",
            version = "1",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/tool.jar",
                    sha256 = hash,
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
        val approval = RuntimePackTrustApproval(
            decision = RuntimePackTrustDecision.APPROVED,
            approvalId = "publisher-approval",
            decidedAtEpochMs = 1
        )
        val publisher = RuntimePackPublisher(
            context,
            RuntimePackVerifier(
                RuntimePackPlatform(
                    apiLevel = 35,
                    supportedAbis = setOf("arm64-v8a")
                )
            )
        )

        val first = publisher.publish(candidate, manifest, approval)

        assertFalse(first.reusedExistingGeneration)
        assertEquals(manifest.generationId(), first.admitted.generationId)
        assertTrue(first.admitted.verified.root.isDirectory)
        assertTrue(
            File(
                first.admitted.verified.root,
                RuntimePackPublisher.CANONICAL_MANIFEST_NAME
            ).isFile
        )
        assertEquals(
            "publisher-fixture",
            first.admitted.verified.artifactFile("artifacts/tool.jar").readText()
        )

        val second = publisher.publish(candidate, manifest, approval)
        assertTrue(second.reusedExistingGeneration)
        assertEquals(first.admitted.generationId, second.admitted.generationId)
        assertEquals(
            first.admitted.verified.root.canonicalPath,
            second.admitted.verified.root.canonicalPath
        )
    }

    @Test
    fun publicationRejectsUnapprovedCandidate() {
        val candidate = File(
            context.cacheDir,
            "publisher-reject-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }
        val artifact = File(candidate, "artifacts/tool.jar").apply {
            parentFile!!.mkdirs()
            writeText("reject")
        }
        val hash = sha256(artifact)
        val manifest = RuntimePackManifest(
            packId = "org.etroute.publisher-reject-${UUID.randomUUID()}",
            version = "1",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/tool.jar",
                    sha256 = hash,
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
            RuntimePackPublisher(
                context,
                RuntimePackVerifier(
                    RuntimePackPlatform(35, setOf("arm64-v8a"))
                )
            ).publish(
                candidate,
                manifest,
                RuntimePackTrustApproval(
                    decision = RuntimePackTrustDecision.REJECTED,
                    approvalId = "publisher-rejected",
                    decidedAtEpochMs = 1
                )
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
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
