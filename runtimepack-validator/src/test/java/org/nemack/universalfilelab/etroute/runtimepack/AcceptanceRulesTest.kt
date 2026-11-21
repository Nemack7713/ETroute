package org.nemack.universalfilelab.etroute.runtimepack

import org.junit.Assert.*
import org.junit.Test
import org.nemack.universalfilelab.etroute.NativeRunResult
import org.nemack.universalfilelab.etroute.NativeSpawnStage

class AcceptanceRulesTest {
    private val stopped = NativeRunResult(null, 15, false, 200, 0, NativeSpawnStage.CANCELLED)
    private val output = ManualProbeDiagnostics.Output("ETROUTE_PYTHON_PROBE_OK {}\nETROUTE_PYTHON_PROBE_READY\n", false)
    private val empty = ManualProbeDiagnostics.Output("", false)
    private fun passes(case: AcceptanceCase, result: NativeRunResult = stopped, ready: Boolean = true,
        count: Int = 1, destroyed: Boolean = false, truncated: Boolean = false) =
        AcceptanceRules.passed(case, result, ready, count, destroyed, output.copy(truncated = truncated), empty)
    @Test fun preAttachmentRequiresNoChildAndOneSignal() {
        val before = stopped.copy(signal = null)
        assertTrue(passes(AcceptanceCase.BEFORE_ATTACH, before, ready = false))
        assertFalse(passes(AcceptanceCase.BEFORE_ATTACH, ready = false))
        assertFalse(passes(AcceptanceCase.BEFORE_ATTACH, before, ready = true))
    }
    @Test fun stopAndRepeatedStopRequireReadinessOneNativeSignalAndConfirmedCancellation() {
        for (case in listOf(AcceptanceCase.STOP, AcceptanceCase.REPEATED_STOP)) {
            assertTrue(passes(case)); assertFalse(passes(case, ready = false))
            assertFalse(passes(case, count = 3)); assertFalse(passes(case, truncated = true))
            assertFalse(passes(case, stopped.copy(stage = NativeSpawnStage.CLEANUP_UNCONFIRMED)))
        }
    }
    @Test fun timeoutIsDistinctFromOwnerCancellation() {
        val timeout = stopped.copy(stage = NativeSpawnStage.TIMEOUT_KILL, timedOut = true)
        assertTrue(passes(AcceptanceCase.TIMEOUT, timeout, count = 0))
        assertFalse(passes(AcceptanceCase.TIMEOUT, count = 0))
        assertFalse(passes(AcceptanceCase.TIMEOUT, timeout, count = 1))
    }
    @Test fun lifecycleRequiresActualDestroyObservation() {
        assertTrue(passes(AcceptanceCase.DESTROY, destroyed = true))
        assertFalse(passes(AcceptanceCase.DESTROY, destroyed = false))
    }
    @Test fun confirmedNormalCompletionNeedsSuccessMarkerAndNoCancellation() {
        val complete = stopped.copy(exitCode = 0, signal = null, stage = NativeSpawnStage.OK)
        assertTrue(passes(AcceptanceCase.COMPLETE, complete, count = 0))
        assertFalse(passes(AcceptanceCase.COMPLETE, complete, count = 1))
        assertFalse(passes(AcceptanceCase.COMPLETE, complete.copy(exitCode = 1), count = 0))
    }
}
