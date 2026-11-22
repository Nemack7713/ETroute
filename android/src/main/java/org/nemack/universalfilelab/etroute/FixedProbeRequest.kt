package org.nemack.universalfilelab.etroute

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// No command/source/path fields are exposed. A handle belongs to exactly one fixed-probe owner.
class FixedProbeRequest internal constructor(
    private val port: Port,
    private val finished: (FixedProbeResult) -> Unit
) {
    enum class Phase { READY, RUNNING, STOP_REQUESTED, FINISHED, RECOVERY_REQUIRED }
    data class Snapshot(val handleId: String, val phase: Phase, val result: FixedProbeResult? = null)
    internal interface Port {
        fun run(approved: () -> Boolean): FixedProbeResult
        fun stop()
    }
    val handleId: String = UUID.randomUUID().toString()
    private val lock = Any()
    private var claimed = false
    private var terminal = false
    private var stopped = false
    private val mutableState = MutableStateFlow(Snapshot(handleId, Phase.READY))
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()
    private val completed = CompletableDeferred<FixedProbeResult>()

    // Blocking: use a worker/IO dispatcher. Return occurs only after the underlying owner finalizes.
    fun execute(approved: () -> Boolean): FixedProbeResult {
        synchronized(lock) {
            check(!claimed) { "Fixed probe request is single-use" }
            claimed = true
            mutableState.value = Snapshot(handleId, if (stopped) Phase.STOP_REQUESTED else Phase.RUNNING)
        }
        val result = try {
            if (synchronized(lock) { stopped }) FixedProbeResult.notStarted("STOP_BEFORE_START")
            else port.run { !synchronized(lock) { stopped } && approved() }
        } catch (error: Exception) {
            // Signal explicitly; never infer native termination from a caught exception.
            port.stop()
            publish(FixedProbeResult.uncertain())
            throw error
        }
        publish(result)
        return result
    }

    // Fast request-local cancellation, separate from execution queues and start authorization.
    fun stop() = synchronized(lock) {
        if (!terminal && !stopped) {
            stopped = true
            mutableState.value = Snapshot(handleId, Phase.STOP_REQUESTED)
            port.stop()
        }
    }

    // Observing/recollecting state never starts work. Cancelling this wait explicitly requests Stop.
    suspend fun awaitCompletion(): FixedProbeResult = try { completed.await() }
        catch (cancelled: CancellationException) { stop(); throw cancelled }

    private fun publish(result: FixedProbeResult) = synchronized(lock) {
        if (terminal) return@synchronized
        terminal = true
        // Release the backend reservation only after the owner barrier; uncertain owners stay blocked.
        finished(result)
        mutableState.value = Snapshot(handleId,
            if (result.completionBarrierReached && result.termination != FixedProbeResult.Termination.UNCONFIRMED)
                Phase.FINISHED else Phase.RECOVERY_REQUIRED, result)
        completed.complete(result)
    }
}
