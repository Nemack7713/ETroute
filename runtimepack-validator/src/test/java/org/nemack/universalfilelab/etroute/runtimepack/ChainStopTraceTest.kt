package org.nemack.universalfilelab.etroute.runtimepack

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ChainStopTraceTest {
    @Test fun firstStopCauseCannotBeOverwrittenByLaterDestruction() {
        val trace = ChainStopTrace()
        assertTrue(trace.record(ChainStopCause.OPERATOR, "ST08_COMPLETE"))
        assertFalse(trace.record(ChainStopCause.PARENT_DESTROY_OTHER, "ST08_COMPLETE"))
        trace.finish()
        assertEquals("chainStopCause=OPERATOR\nchainStopStep=ST08_COMPLETE", trace.report())
    }
    @Test fun normalFinalizationDoesNotInventAStopRequest() {
        val trace = ChainStopTrace(); trace.finish()
        assertFalse(trace.record(ChainStopCause.PARENT_DESTROY_FINISHING, "AFTER_CHAIN"))
        assertTrue(trace.report().contains("chainStopCause=NONE"))
    }
    @Test fun concurrentStopSourcesHaveExactlyOneWinner() {
        val trace = ChainStopTrace(); val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { Callable { trace.record(ChainStopCause.PARENT_DESTROY_OTHER, "STEP_$it") } }
            val results = pool.invokeAll(tasks).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertTrue(trace.report().contains("chainStopCause=PARENT_DESTROY_OTHER"))
        } finally { pool.shutdownNow() }
    }
}
