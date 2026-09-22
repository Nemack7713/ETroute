package org.nemack.universalfilelab.etroute

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionJournalAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun journalTransitionsPersistAndRecover() {
        val sessionsRoot = File(context.cacheDir, "journal-store-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val store = SessionJournalStore(sessionsRoot)
        val sessionId = "session-${UUID.randomUUID()}"
        val journal = store.journalFor(sessionId)

        val created = journal.initialize(sessionId, nowEpochMs = 1)
        assertEquals(SessionJournalState.CREATED, created.state)

        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.PREPARED,
            requestId = "request-1",
            nowEpochMs = 2
        )
        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.RUNNING,
            nowEpochMs = 3
        )

        val recovered = store.scanRecoverable()
        assertTrue(recovered.corruptJournals.isEmpty())
        assertEquals(1, recovered.recoverable.size)
        assertEquals(SessionJournalState.RUNNING, recovered.recoverable.single().state)
        assertEquals("request-1", recovered.recoverable.single().requestId)

        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.TERMINATED,
            termination = TerminationClass.TERM_OK,
            nowEpochMs = 4
        )
        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.FINALIZING,
            nowEpochMs = 5
        )
        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.FINALIZED,
            nowEpochMs = 6
        )

        assertTrue(store.scanRecoverable().recoverable.isEmpty())
    }

    @Test
    fun finalizedJournalCannotMoveBackward() {
        val sessionsRoot = File(context.cacheDir, "journal-final-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val sessionId = "session-${UUID.randomUUID()}"
        val journal = SessionJournalStore(sessionsRoot).journalFor(sessionId)

        journal.initialize(sessionId, nowEpochMs = 1)
        journal.transition(sessionId, SessionJournalState.FINALIZING, nowEpochMs = 2)
        journal.transition(sessionId, SessionJournalState.FINALIZED, nowEpochMs = 3)

        var rejected = false
        try {
            journal.transition(sessionId, SessionJournalState.RUNNING, nowEpochMs = 4)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun scanSeparatesCorruptJournalFromRecoverableSessions() {
        val sessionsRoot = File(context.cacheDir, "journal-corrupt-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val store = SessionJournalStore(sessionsRoot)

        val goodSession = "good-${UUID.randomUUID()}"
        store.journalFor(goodSession).initialize(goodSession, nowEpochMs = 1)

        val badSession = File(sessionsRoot, "bad-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        File(badSession, SessionJournalStore.JOURNAL_NAME).writeText("{not-json")

        val scan = store.scanRecoverable()
        assertEquals(1, scan.recoverable.size)
        assertEquals(goodSession, scan.recoverable.single().sessionId)
        assertEquals(1, scan.corruptJournals.size)
        assertTrue(scan.corruptJournals.single().endsWith(SessionJournalStore.JOURNAL_NAME))
    }

    @Test
    fun repeatedSameStateTransitionIsIdempotent() {
        val sessionsRoot = File(context.cacheDir, "journal-idempotent-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val sessionId = "session-${UUID.randomUUID()}"
        val journal = SessionJournalStore(sessionsRoot).journalFor(sessionId)

        journal.initialize(sessionId, nowEpochMs = 1)
        journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.PREPARED,
            requestId = "request-2",
            nowEpochMs = 2
        )
        val second = journal.transition(
            sessionId = sessionId,
            next = SessionJournalState.PREPARED,
            nowEpochMs = 3
        )

        assertEquals(SessionJournalState.PREPARED, second.state)
        assertEquals("request-2", second.requestId)
        assertFalse(storeFile(sessionsRoot, sessionId).readText().isBlank())
    }

    private fun storeFile(sessionsRoot: File, sessionId: String): File =
        File(File(sessionsRoot, sessionId), SessionJournalStore.JOURNAL_NAME)
}
