package org.nemack.universalfilelab.etroute

// Own one sequential native-request stream. Stop is independent of start authorization/evidence.
// signalCancel must be a nonblocking, nonthrowing request-local signal (Request.cancel in production).
class NativeStopController<T : Any>(private val signalCancel: (T) -> Unit) {
    enum class Disposition { RELEASE_EPHEMERAL, RETAIN_SESSION }
    private var active: T? = null
    private var signaled = false
    private var stopped = false
    private var closed = false
    private var uncertain = false

    @Synchronized fun attach(request: T): Boolean {
        check(active == null) { "Previous native request has not completed" }
        if (uncertain) return false
        if (closed) {
            signalCancel(request)
            return false
        }
        active = request
        signaled = false
        if (stopped) cancelActive()
        return true
    }

    @Synchronized fun requestStop() {
        stopped = true
        cancelActive()
    }

    @Synchronized fun close() {
        closed = true
        requestStop()
    }

    @Synchronized fun complete(request: T, terminationConfirmed: Boolean): Disposition {
        // A late completion must never detach or cancel a newer request.
        if (active !== request) return Disposition.RETAIN_SESSION
        active = null
        signaled = false
        if (!terminationConfirmed) uncertain = true
        return if (uncertain || stopped || closed) Disposition.RETAIN_SESSION else Disposition.RELEASE_EPHEMERAL
    }

    @Synchronized fun requiresRecovery(): Boolean = uncertain
    @Synchronized fun isStopped(): Boolean = stopped || closed

    private fun cancelActive() {
        val request = active ?: return
        if (!signaled) {
            signaled = true
            signalCancel(request)
        }
    }
}
