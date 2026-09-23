package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TransactionalFinalizerAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun finalizationReusesReceiptAndDoesNotExportTwice() {
        val sessionId = "txn-${UUID.randomUUID()}"
        val paths = EtRouteWorkspaceManager(context).create(sessionId)
        File(paths.output, "result.txt").writeText("stable-output")
        File(paths.input, "input.txt").writeText("delete")
        File(paths.tmp, "tmp.txt").writeText("delete")

        val sessionsRoot = paths.root.parentFile!!
        val journal = SessionJournalStore(sessionsRoot).journalFor(sessionId)
        val calls = AtomicInteger(0)
        val sink = IdempotentSessionExportSink { _, key ->
            calls.incrementAndGet()
            "export://$key"
        }

        val request = SessionFinalizationRequest(
            paths = paths,
            preserveOutput = true,
            retainDiagnostics = false
        )

        val first = TransactionalSessionFinalizer().finalize(
            request = request,
            journal = journal,
            exportSink = sink,
            nowEpochMs = 10
        )

        assertEquals(1, calls.get())
        assertTrue(first.cleanup.cleaned)
        assertTrue(first.cleanup.outputPreserved)
        assertTrue(File(paths.root, TransactionalSessionFinalizer.RECEIPT_NAME).isFile)
        assertTrue(!paths.diagnostics.exists())
        assertEquals(SessionJournalState.FINALIZED, journal.read()!!.state)

        val second = TransactionalSessionFinalizer().finalize(
            request = request,
            journal = journal,
            exportSink = sink,
            nowEpochMs = 20
        )

        assertEquals(1, calls.get())
        assertTrue(second.reusedReceipt)
        assertEquals(first.receipt.outputSha256, second.receipt.outputSha256)
        assertEquals(first.receipt.exportedTo, second.receipt.exportedTo)
        assertEquals("stable-output", File(paths.output, "result.txt").readText())
    }
}
