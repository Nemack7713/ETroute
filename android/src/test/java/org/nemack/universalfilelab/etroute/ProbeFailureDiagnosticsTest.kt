package org.nemack.universalfilelab.etroute

import org.junit.Assert.*
import org.junit.Test

class ProbeFailureDiagnosticsTest {
    @Test fun missingNativeReturnIsNotRepresentedAsConfirmedTermination() {
        val report = ProbeFailureDiagnostics.describe("native-attach", IllegalStateException("Owner closed before attachment"),
            null, true, true, false)
        assertTrue(report.contains("runnerFailurePhase=native-attach"))
        assertTrue(report.contains("nativeReturnObserved=false"))
        assertTrue(report.contains("ownerStopRequested=true activityDestroyed=true"))
        assertFalse(report.contains("termination=confirmed"))
    }
    @Test fun observedNativeResultAndUnderlyingCauseRemainAvailable() {
        val result = NativeRunResult(0, null, false, 335, 0, NativeSpawnStage.OK)
        val error = IllegalStateException("read failed", java.io.FileNotFoundException("stdout.log missing"))
        val report = ProbeFailureDiagnostics.describe("stdout-read", error, result, false, false, false)
        assertTrue(report.contains("observedNativeStage=OK exit=0"))
        assertTrue(report.contains("java.io.FileNotFoundException: stdout.log missing"))
    }
    @Test fun unicodeDiagnosticsStayWithinByteLimit() {
        val report = ProbeFailureDiagnostics.describe("diagnostics", Exception("😀".repeat(5000)), null, false, false, true)
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= ProbeFailureDiagnostics.MAX_BYTES)
        assertFalse(report.contains('\uFFFD'))
    }
}
