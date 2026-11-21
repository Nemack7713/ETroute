package org.nemack.universalfilelab.etroute

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeStopControllerTest {
    private class Request { val cancels = AtomicInteger() }
    private fun controller() = NativeStopController<Request> { it.cancels.incrementAndGet() }

    @Test fun stopBeforeAttachmentIsLatchedWithoutCapabilityOrEvidence() {
        val control = controller(); control.requestStop(); control.requestStop()
        val request = Request(); assertTrue(control.attach(request))
        assertEquals(1, request.cancels.get()); assertTrue(control.isStopped())
        assertEquals(NativeStopController.Disposition.RETAIN_SESSION, control.complete(request, true))
    }
    @Test fun repeatedStopSignalsOwnedRequestOnce() {
        val control = controller(); val request = Request(); control.attach(request)
        repeat(10) { control.requestStop() }; assertEquals(1, request.cancels.get())
    }
    @Test fun normalCompletionAllowsNextRequestAndStaleCompletionCannotDetachIt() {
        val control = controller(); val first = Request(); val second = Request()
        control.attach(first)
        assertEquals(NativeStopController.Disposition.RELEASE_EPHEMERAL, control.complete(first, true))
        control.attach(second)
        assertEquals(NativeStopController.Disposition.RETAIN_SESSION, control.complete(first, false))
        assertFalse(control.requiresRecovery()); control.requestStop()
        assertEquals(0, first.cancels.get()); assertEquals(1, second.cancels.get())
    }
    @Test fun uncertainTerminationRetainsStateAndBlocksNewAttachment() {
        val control = controller(); val request = Request(); control.attach(request)
        assertEquals(NativeStopController.Disposition.RETAIN_SESSION, control.complete(request, false))
        assertTrue(control.requiresRecovery()); assertFalse(control.attach(Request()))
    }
    @Test fun destructionBeforeAttachmentCancelsAndRejectsLateRequest() {
        val control = controller(); control.close(); val request = Request()
        assertFalse(control.attach(request)); assertEquals(1, request.cancels.get())
    }
    @Test fun destructionDuringActiveRequestNeverRestartsOrReleasesSession() {
        val control = controller(); val request = Request(); control.attach(request)
        control.close(); control.close(); assertEquals(1, request.cancels.get())
        assertEquals(NativeStopController.Disposition.RETAIN_SESSION, control.complete(request, true))
        assertFalse(control.attach(Request()))
    }
    @Test fun cannotReplaceAnUnfinishedRequest() {
        val control = controller(); val request = Request(); control.attach(request)
        try { control.attach(Request()); fail("Replaced active request") } catch (_: IllegalStateException) { }
        control.requestStop(); assertEquals(1, request.cancels.get())
    }
    @Test fun concurrentStopAttachmentAndCompletionNeverSignalAnotherRequest() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(100) {
                val control = controller(); val request = Request()
                val attach = pool.submit(Callable { control.attach(request) })
                val stop = pool.submit(Callable { control.requestStop() })
                attach.get(5, TimeUnit.SECONDS); stop.get(5, TimeUnit.SECONDS)
                assertEquals(1, request.cancels.get())
                val complete = pool.submit(Callable { control.complete(request, true) })
                val again = pool.submit(Callable { control.requestStop() })
                complete.get(5, TimeUnit.SECONDS); again.get(5, TimeUnit.SECONDS)
                assertEquals(1, request.cancels.get())
            }
        } finally { pool.shutdownNow() }
    }
}
