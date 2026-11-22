package org.nemack.universalfilelab.etroute.runtimepack

import java.util.concurrent.atomic.AtomicReference

internal enum class ChainStopCause {
    OPERATOR, PARENT_DESTROY_FINISHING, PARENT_DESTROY_CONFIGURATION, PARENT_DESTROY_OTHER, LIFECYCLE_LAUNCH_ABORT
}

internal class ChainStopTrace {
    private sealed class State {
        object Pending : State()
        object Finished : State()
        data class Requested(val cause: ChainStopCause, val step: String) : State()
    }
    private val state = AtomicReference<State>(State.Pending)
    fun record(cause: ChainStopCause, step: String): Boolean =
        state.compareAndSet(State.Pending, State.Requested(cause, step.take(64)))
    fun finish() { state.compareAndSet(State.Pending, State.Finished) }
    fun report(): String = when (val current = state.get()) {
        is State.Requested -> "chainStopCause=${current.cause}\nchainStopStep=${current.step}"
        else -> "chainStopCause=NONE\nchainStopStep=NONE"
    }
}
