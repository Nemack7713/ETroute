package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Aapt2CandidateImporterAndroidTest {

    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun pinnedStreamImportsAsUntrustedCandidateAndReusesGeneration() {
        val payload = minimalElf64Aarch64Pie()
        val policy = Aapt2SourcePolicy(
            repository = "test/source",
            commit = "1".repeat(40),
            path = "arm64/aapt2",
            gitBlobSha1 = gitBlobSha1(payload),
            sizeBytes = payload.size.toLong(),
            minApi = 24
        )
        val importer = Aapt2CandidateImporter(
            context = context,
            sourcePolicy = policy,
            verifier = RuntimePackVerifier(
                RuntimePackPlatform(35, setOf("arm64-v8a"))
            )
        )

        val first = importer.importFromStream(ByteArrayInputStream(payload))

        assertFalse(first.reusedExistingCandidate)
        assertEquals("org.etroute.aapt2", first.manifest.packId)
        assertEquals(setOf("arm64-v8a"), first.manifest.abis)
        assertEquals("aapt2", first.manifest.tools.single().toolId)
        assertEquals(
            RuntimePackToolKind.NATIVE_EXECUTABLE,
            first.manifest.tools.single().kind
        )
        assertTrue(first.verified.artifactFile("artifacts/aapt2").canExecute())

        val evidence = JSONObject(first.evidenceFile.readText())
        assertEquals(
            "IMPORTED_IDENTITY_VERIFIED",
            evidence.getString("status")
        )
        assertFalse(evidence.getBoolean("trustGranted"))
        assertFalse(evidence.getBoolean("published"))
        assertFalse(evidence.getBoolean("runtimeSmokeVerified"))
        assertEquals(first.sha256, evidence.getString("sha256"))
        assertEquals(
            policy.gitBlobSha1,
            evidence.getString("gitBlobSha1")
        )

        val second = importer.importFromStream(ByteArrayInputStream(payload))
        assertTrue(second.reusedExistingCandidate)
        assertEquals(
            first.verified.generationId,
            second.verified.generationId
        )
        assertEquals(
            first.root.canonicalPath,
            second.root.canonicalPath
        )
    }

    @Test
    fun wrongPinnedBlobIdentityIsRejectedBeforeCandidatePublication() {
        val payload = minimalElf64Aarch64Pie()
        val policy = Aapt2SourcePolicy(
            repository = "test/source",
            commit = "2".repeat(40),
            path = "arm64/aapt2",
            gitBlobSha1 = "0".repeat(40),
            sizeBytes = payload.size.toLong(),
            minApi = 24
        )
        val importer = Aapt2CandidateImporter(
            context = context,
            sourcePolicy = policy,
            verifier = RuntimePackVerifier(
                RuntimePackPlatform(35, setOf("arm64-v8a"))
            )
        )

        var rejected = false
        try {
            importer.importFromStream(ByteArrayInputStream(payload))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun publicationRequiresExplicitApproval() {
        val payload = minimalElf64Aarch64Pie()
        val policy = Aapt2SourcePolicy(
            repository = "test/source",
            commit = "3".repeat(40),
            path = "arm64/aapt2",
            gitBlobSha1 = gitBlobSha1(payload),
            sizeBytes = payload.size.toLong(),
            minApi = 24
        )
        val importer = Aapt2CandidateImporter(
            context = context,
            sourcePolicy = policy,
            verifier = RuntimePackVerifier(
                RuntimePackPlatform(35, setOf("arm64-v8a"))
            )
        )
        val candidate = importer.importFromStream(ByteArrayInputStream(payload))

        var rejected = false
        try {
            importer.publishApproved(
                candidate,
                RuntimePackTrustApproval(
                    decision = RuntimePackTrustDecision.REJECTED,
                    approvalId = "reject-aapt2",
                    decidedAtEpochMs = 1
                )
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    private fun minimalElf64Aarch64Pie(): ByteArray {
        val data = ByteArray(64)
        data[0] = 0x7f
        data[1] = 'E'.code.toByte()
        data[2] = 'L'.code.toByte()
        data[3] = 'F'.code.toByte()
        data[4] = 2
        data[5] = 1
        data[6] = 1

        putU16Le(data, 16, 3)
        putU16Le(data, 18, 183)
        return data
    }

    private fun putU16Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun gitBlobSha1(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${payload.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(payload)
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
