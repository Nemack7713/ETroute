package org.nemack.universalfilelab.etroute

// Owner certainty depends on a delivered receipt, not merely on child exit or coroutine completion.
internal object ProbeCompletionReceipt {
    fun confirmed(result: NativeRunResult?): Boolean = result != null &&
        result.stage !in setOf(NativeSpawnStage.CLEANUP_UNCONFIRMED, NativeSpawnStage.WAITPID, NativeSpawnStage.ERROR_PIPE) &&
        (result.exitCode != null || result.signal != null)
    fun noChildStarted(result: NativeRunResult?): Boolean = result != null &&
        result.stage == NativeSpawnStage.CANCELLED && result.exitCode == null && result.signal == null &&
        result.spawnErrno == 0 && !result.timedOut
    fun deliver(result: NativeRunResult, simulateLoss: Boolean): NativeRunResult? = if (simulateLoss) null else result
}
