package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionEvidenceAndroidTest {

    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun apkInventoryEvidenceIsBoundToFinalizationReceipt() {
        val sessionId = "evidence-${UUID.randomUUID()}"
        val paths = EtRouteWorkspaceManager(context).create(sessionId)
        File(paths.output, "rebuilt.apk").writeBytes(byteArrayOf(1, 2, 3))

        val sourceRoot = File(
            context.cacheDir,
            "session-evidence-source-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }

        val before = File(sourceRoot, "before.json").apply {
            writeText("""{"schemaVersion":1,"apks":[{"file":"before.apk"}]}""")
        }
        val after = File(sourceRoot, "after.json").apply {
            writeText("""{"schemaVersion":1,"apks":[{"file":"after.apk"}]}""")
        }
        val comparison = File(sourceRoot, "comparison.json").apply {
            writeText(
                """{"schemaVersion":1,"summary":{"changed":true,"trustDecisionMade":false}}"""
            )
        }

        val attachment = SessionEvidenceStore(paths).attachApkInventoryEvidence(
            beforeInventory = before,
            afterInventory = after,
            comparison = comparison,
            nowEpochMs = 10
        )

        assertEquals(3, attachment.manifest.artifacts.size)
        assertEquals(64, attachment.manifestSha256.length)
        assertTrue(attachment.manifestFile.isFile)

        val manifestJson = JSONObject(attachment.manifestFile.readText())
        assertEquals(sessionId, manifestJson.getString("sessionId"))
        assertFalse(manifestJson.getBoolean("trustDecisionMade"))
        assertEquals(3, manifestJson.getJSONArray("artifacts").length())

        val journal = SessionJournalStore(paths.root.parentFile!!)
            .journalFor(sessionId)
        val finalized = TransactionalSessionFinalizer().finalize(
            request = SessionFinalizationRequest(
                paths = paths,
                preserveOutput = true,
                retainDiagnostics = false
            ),
            journal = journal,
            nowEpochMs = 20
        )

        assertEquals(
            attachment.manifestSha256,
            finalized.receipt.evidenceManifestSha256
        )
        assertEquals(SessionJournalState.FINALIZED, journal.read()!!.state)
        assertTrue(attachment.manifestFile.isFile)

        attachment.manifestFile.appendText("\n")

        var rejected = false
        try {
            TransactionalSessionFinalizer().finalize(
                request = SessionFinalizationRequest(
                    paths = paths,
                    preserveOutput = true,
                    retainDiagnostics = false
                ),
                journal = journal,
                nowEpochMs = 30
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun evidenceCannotBeAttachedAfterFinalization() {
        val sessionId = "evidence-late-${UUID.randomUUID()}"
        val paths = EtRouteWorkspaceManager(context).create(sessionId)
        val journal = SessionJournalStore(paths.root.parentFile!!)
            .journalFor(sessionId)

        TransactionalSessionFinalizer().finalize(
            request = SessionFinalizationRequest(
                paths = paths,
                preserveOutput = true,
                retainDiagnostics = false
            ),
            journal = journal,
            nowEpochMs = 10
        )

        val source = File(
            context.cacheDir,
            "late-evidence-${UUID.randomUUID()}"
        ).apply {
            check(mkdirs())
        }
        val before = File(source, "before.json").apply { writeText("""{"x":1}""") }
        val after = File(source, "after.json").apply { writeText("""{"x":2}""") }
        val comparison = File(source, "comparison.json").apply {
            writeText("""{"x":3}""")
        }

        var rejected = false
        try {
            SessionEvidenceStore(paths).attachApkInventoryEvidence(
                before,
                after,
                comparison,
                nowEpochMs = 20
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
