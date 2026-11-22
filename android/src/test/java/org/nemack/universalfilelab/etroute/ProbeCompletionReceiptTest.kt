package org.nemack.universalfilelab.etroute

import org.junit.Assert.*
import org.junit.Test

class ProbeCompletionReceiptTest {
    private val ok = NativeRunResult(0, null, false, 172, 0, NativeSpawnStage.OK)
    @Test fun deliveredCompletionAllowsCertainty() {
        assertSame(ok, ProbeCompletionReceipt.deliver(ok, false))
        assertTrue(ProbeCompletionReceipt.confirmed(ok))
    }
    @Test fun receiptLossDoesNotAlterActualNativeResultAndCannotConfirm() {
        assertNull(ProbeCompletionReceipt.deliver(ok, true))
        assertFalse(ProbeCompletionReceipt.confirmed(null))
        assertTrue(ok.succeeded)
    }
    @Test fun nativeUncertaintyCannotBePromotedByAnExitCode() {
        for (stage in listOf(NativeSpawnStage.CLEANUP_UNCONFIRMED, NativeSpawnStage.WAITPID, NativeSpawnStage.ERROR_PIPE))
            assertFalse(ProbeCompletionReceipt.confirmed(ok.copy(stage = stage)))
    }
    @Test fun preForkCancellationIsNotStartedWithoutClaimingChildTermination() {
        val cancelled = NativeRunResult(null, null, false, 0, 0, NativeSpawnStage.CANCELLED)
        assertTrue(ProbeCompletionReceipt.noChildStarted(cancelled))
        assertFalse(ProbeCompletionReceipt.confirmed(cancelled))
        assertFalse(ProbeCompletionReceipt.noChildStarted(cancelled.copy(stage = NativeSpawnStage.CLEANUP_UNCONFIRMED)))
        assertFalse(ProbeCompletionReceipt.noChildStarted(cancelled.copy(spawnErrno = 5)))
        assertFalse(ProbeCompletionReceipt.noChildStarted(null))
    }
}
