package org.nemack.universalfilelab.etroute

import android.content.Context
import android.os.Looper
import java.io.File

// Trusted host composition supplies APK pins, never user-entered executable paths or commands.
@android.annotation.TargetApi(26)
class EtRouteFixedProbeBackend(context: Context, private val pack: PinnedRuntimePack,
    private val payloadRoot: File) {
    private val context = context.applicationContext
    private var reserved = false
    private var recoveryRequired = false

    @Synchronized fun createRequest(): FixedProbeRequest {
        check(!reserved && !recoveryRequired) { "Prior fixed request is active or needs recovery" }
        val owner = FixedProbeEvidenceSession(context, pack, payloadRoot)
        reserved = true
        return FixedProbeRequest(object : FixedProbeRequest.Port {
            override fun run(approved: () -> Boolean): FixedProbeResult {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Fixed probe execution must run off Main" }
                return owner.runTyped(approved)
            }
            override fun stop() = owner.stop()
        }) { result ->
            synchronized(this) {
                recoveryRequired = !result.completionBarrierReached || result.termination == FixedProbeResult.Termination.UNCONFIRMED
                reserved = false
            }
        }
    }
}
