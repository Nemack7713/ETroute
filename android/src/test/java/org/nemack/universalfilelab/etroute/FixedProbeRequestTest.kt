package org.nemack.universalfilelab.etroute

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class FixedProbeRequestTest {
    private fun result() = FixedProbeResult("request", "session", FixedProbeResult.Termination.CONFIRMED,
        FixedProbeResult.Validation.VALIDATED, true,
        NativeRunResult(0, null, false, 10, 0, NativeSpawnStage.OK), "owned/evidence", null, true, true)
    private class Port : FixedProbeRequest.Port {
        val calls = AtomicInteger(); val cancels = AtomicInteger()
        val attached = CountDownLatch(1); val terminated = CountDownLatch(1)
        override fun run(approved: () -> Boolean): FixedProbeResult {
            check(approved()); calls.incrementAndGet(); attached.countDown()
            check(terminated.await(5, TimeUnit.SECONDS))
            return FixedProbeResult("request", "session", FixedProbeResult.Termination.CONFIRMED,
                FixedProbeResult.Validation.VALIDATED, false,
                NativeRunResult(null, 15, false, 10, 0, NativeSpawnStage.CANCELLED), null, null, true, true)
        }
        override fun stop() { cancels.incrementAndGet() }
    }
    @Test fun stopBeforeExecutionNeverApprovesOrRunsAndIsIdempotent() {
        val port = Port(); var releases = 0
        val request = FixedProbeRequest(port) { releases++ }
        request.stop(); request.stop()
        val result = request.execute { fail("Must not approve"); true }
        assertEquals(0, port.calls.get()); assertEquals(1, port.cancels.get())
        assertEquals(FixedProbeResult.Termination.NOT_STARTED, result.termination)
        assertFalse(result.successfulProbe); assertEquals(1, releases)
        assertEquals(FixedProbeRequest.Phase.FINISHED, request.state.value.phase)
    }
    @Test fun stopDoesNotPublishCompletionOrReleaseUntilOwnerBarrier() {
        val port = Port(); val releases = AtomicInteger()
        val request = FixedProbeRequest(port) { releases.incrementAndGet() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<FixedProbeResult> { request.execute { true } }
            assertTrue(port.attached.await(5, TimeUnit.SECONDS))
            repeat(3) { request.stop() }
            assertEquals(1, port.cancels.get()); assertEquals(0, releases.get())
            assertEquals(FixedProbeRequest.Phase.STOP_REQUESTED, request.state.value.phase)
            assertFalse(future.isDone)
            port.terminated.countDown()
            assertEquals(FixedProbeResult.Termination.CONFIRMED, future.get(5, TimeUnit.SECONDS).termination)
            request.stop(); assertEquals(1, port.cancels.get()); assertEquals(1, releases.get())
        } finally { port.terminated.countDown(); pool.shutdownNow() }
    }
    @Test fun completionRaceAndReplayCannotRunOrCancelANewerOwner() {
        val calls = AtomicInteger(); val cancels = AtomicInteger()
        val request = FixedProbeRequest(object : FixedProbeRequest.Port {
            override fun run(approved: () -> Boolean): FixedProbeResult { check(approved()); calls.incrementAndGet(); return result() }
            override fun stop() { cancels.incrementAndGet() }
        }) {}
        assertTrue(request.execute { true }.successfulProbe)
        request.stop(); assertEquals(0, cancels.get())
        try { request.execute { true }; fail("Replay") } catch (_: IllegalStateException) { }
        assertEquals(1, calls.get()); assertFalse(request.state.value.result!!.generalScriptExecutionEnabled)
    }
    @Test fun uncertainOwnerBlocksRecoveryAndNeverClaimsSuccess() {
        var released: FixedProbeResult? = null
        val request = FixedProbeRequest(object : FixedProbeRequest.Port {
            override fun run(approved: () -> Boolean): FixedProbeResult = error("owner failed")
            override fun stop() { }
        }) { released = it }
        try { request.execute { true }; fail("Expected owner failure") } catch (_: IllegalStateException) { }
        assertEquals(FixedProbeRequest.Phase.RECOVERY_REQUIRED, request.state.value.phase)
        assertFalse(released!!.completionBarrierReached)
        assertFalse(released!!.actualNativeUncertaintyTested)
    }
    @Test fun terminalStateIsVisibleBeforeBackendRelease() {
        lateinit var request: FixedProbeRequest
        request = FixedProbeRequest(object : FixedProbeRequest.Port {
            override fun run(approved: () -> Boolean): FixedProbeResult { check(approved()); return result() }
            override fun stop() { }
        }) { assertEquals(FixedProbeRequest.Phase.FINISHED, request.state.value.phase) }
        request.execute { true }
    }
    @Test fun ownerFailureAfterStopDoesNotSignalCancelTwice() {
        val port = Port(); val request = FixedProbeRequest(port) {}
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<FixedProbeResult> { request.execute { true } }
            assertTrue(port.attached.await(5, TimeUnit.SECONDS))
            request.stop()
            pool.shutdownNow()
            try { future.get(5, TimeUnit.SECONDS); fail("Expected interrupted owner") } catch (_: java.util.concurrent.ExecutionException) { }
            assertEquals(1, port.cancels.get())
            assertEquals(FixedProbeRequest.Phase.RECOVERY_REQUIRED, request.state.value.phase)
        } finally { port.terminated.countDown(); pool.shutdownNow() }
    }
    @Test fun cancelledAwaitSignalsStopButDoesNotInventTermination() = runBlocking {
        val port = Port(); val request = FixedProbeRequest(port) {}
        val waiter = launch { request.awaitCompletion() }
        yield(); waiter.cancelAndJoin()
        assertEquals(1, port.cancels.get())
        assertNull(request.state.value.result)
        assertEquals(FixedProbeRequest.Phase.STOP_REQUESTED, request.state.value.phase)
    }
}
