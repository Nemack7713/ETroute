package org.nemack.universalfilelab.etroute.runtimepack

import org.nemack.universalfilelab.etroute.NativeRunResult
import org.nemack.universalfilelab.etroute.NativeSpawnStage

internal enum class AcceptanceCase(val id: String) {
    BEFORE_ATTACH("ST01"), STOP("ST02"), REPEATED_STOP("ST03"), TIMEOUT("ST04"), DESTROY("ST05"), COMPLETE("ST07")
}

internal object AcceptanceRules {
    fun noChildStarted(result: NativeRunResult): Boolean = result.stage == NativeSpawnStage.CANCELLED &&
        result.exitCode == null && result.signal == null && result.spawnErrno == 0 && !result.timedOut

    fun passed(case: AcceptanceCase, result: NativeRunResult, ready: Boolean, cancelCount: Int,
        destroyed: Boolean, stdout: ManualProbeDiagnostics.Output, stderr: ManualProbeDiagnostics.Output): Boolean {
        if (case == AcceptanceCase.BEFORE_ATTACH) return noChildStarted(result) && cancelCount == 1 && !ready
        if (result.spawnErrno != 0 || !ManualProbeDiagnostics.terminationConfirmed(result) || stdout.truncated || stderr.truncated) return false
        return when (case) {
            AcceptanceCase.STOP, AcceptanceCase.REPEATED_STOP -> ready && cancelCount == 1 &&
                result.stage == NativeSpawnStage.CANCELLED && !result.timedOut
            AcceptanceCase.DESTROY -> destroyed && ready && cancelCount == 1 &&
                result.stage == NativeSpawnStage.CANCELLED && !result.timedOut
            AcceptanceCase.TIMEOUT -> ready && cancelCount == 0 && result.timedOut && result.stage == NativeSpawnStage.TIMEOUT_KILL
            AcceptanceCase.COMPLETE -> ManualProbeDiagnostics.passed(result, stdout, stderr, false) && cancelCount == 0
            else -> false
        }
    }
}
