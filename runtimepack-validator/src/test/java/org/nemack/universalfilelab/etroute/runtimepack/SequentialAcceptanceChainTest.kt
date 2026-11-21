package org.nemack.universalfilelab.etroute.runtimepack

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SequentialAcceptanceChainTest {
    private fun ok() = SequentialAcceptanceChain.Result(true, true, true, "completed")
    private fun run(chain: SequentialAcceptanceChain) = chain.run({}, { _, _ -> })

    @Test fun nextActionCannotBeginUntilPriorActionAndReportFinalize() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val chain = SequentialAcceptanceChain(listOf(
            SequentialAcceptanceChain.Step("FIRST", action = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); events += "ended-FIRST"; ok() }),
            SequentialAcceptanceChain.Step("SECOND", action = { events += "began-SECOND"; ok() })))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<String> { chain.run({}, { id, _ -> events += "report-$id" }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(events.contains("began-SECOND"))
            release.countDown(); assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("SEQUENTIAL_CHAIN=PASS"))
            assertEquals(listOf("ended-FIRST", "report-FIRST", "began-SECOND", "report-SECOND"), events)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun failureUncertaintyAndExceptionsSkipLaterSteps() {
        val failures = listOf<() -> SequentialAcceptanceChain.Result>(
            { SequentialAcceptanceChain.Result(false, true, true, "failed") },
            { SequentialAcceptanceChain.Result(true, false, true, "no completion barrier") },
            { SequentialAcceptanceChain.Result(true, true, false, "uncertain") },
            { throw IllegalStateException("runner error") })
        for (action in failures) {
            var later = false
            val chain = SequentialAcceptanceChain(listOf(SequentialAcceptanceChain.Step("FIRST", action = action),
                SequentialAcceptanceChain.Step("LATER", action = { later = true; ok() })))
            assertTrue(run(chain).contains("LATER=NOT_RUN")); assertFalse(later)
        }
    }
    @Test fun stopDuringCurrentStepWaitsForItsEndAndNeverStartsNext() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var later = false
        val chain = SequentialAcceptanceChain(listOf(
            SequentialAcceptanceChain.Step("FIRST", action = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ok() }),
            SequentialAcceptanceChain.Step("NEXT", action = { later = true; ok() })))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<String> { run(chain) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); chain.stop(); chain.stop()
            assertFalse(future.isDone); release.countDown()
            val result = future.get(5, TimeUnit.SECONDS)
            assertTrue(result.startsWith("SEQUENTIAL_CHAIN=STOPPED")); assertFalse(later)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun retainedSimulationIsAllowedOnlyAsFinalEndedStep() {
        val finalStep = SequentialAcceptanceChain.Step("RETAIN", true) {
            SequentialAcceptanceChain.Result(true, true, false, "native ended; owner receipt lost")
        }
        assertTrue(run(SequentialAcceptanceChain(listOf(SequentialAcceptanceChain.Step("OK", action = ::ok), finalStep)))
            .startsWith("SEQUENTIAL_CHAIN=PASS_RETRY_BLOCKED"))
        try { SequentialAcceptanceChain(listOf(finalStep, SequentialAcceptanceChain.Step("NEXT", action = ::ok))); fail("terminal step not last") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun stoppedOrReplayedChainDoesNotDispatch() {
        var calls = 0
        val chain = SequentialAcceptanceChain(listOf(SequentialAcceptanceChain.Step("ONE", action = { calls++; ok() })))
        chain.stop(); assertTrue(run(chain).contains("ONE=NOT_RUN")); assertEquals(0, calls)
        try { run(chain); fail("replayed chain") } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }
}
