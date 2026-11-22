package org.nemack.universalfilelab.etroute

// Typed diagnostic envelope. This value is never a capability, launch permit or attestation.
data class FixedProbeResult internal constructor(
    val requestId: String?,
    val sessionId: String?,
    val termination: Termination,
    val validation: Validation,
    val successfulProbe: Boolean,
    val nativeResult: NativeRunResult?,
    val evidenceRef: String?,
    val rejection: String?,
    val completionBarrierReached: Boolean,
    val retainSession: Boolean,
    val diagnosticReport: String = "",
    val replayBlocked: Boolean = false
) {
    enum class Termination { NOT_STARTED, CONFIRMED, UNCONFIRMED }
    enum class Validation { NOT_ATTEMPTED, REJECTED, VALIDATED }
    val schemaVersion: Int get() = 1
    val generalScriptExecutionEnabled: Boolean get() = false
    val actualNativeUncertaintyTested: Boolean get() = false
    val attestation: String get() = "unsigned"
    internal companion object {
        fun notStarted(reason: String) = FixedProbeResult(null, null, Termination.NOT_STARTED,
            Validation.NOT_ATTEMPTED, false, null, null, reason, true, true)
        fun uncertain() = FixedProbeResult(null, null, Termination.UNCONFIRMED,
            Validation.NOT_ATTEMPTED, false, null, null, "OWNER_ERROR", false, true)
    }
}
