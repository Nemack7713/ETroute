package org.nemack.universalfilelab.etroute

import com.google.gson.GsonBuilder
import java.security.MessageDigest

// Trusted runner constructs the outer v1 envelope from its native observation and exact retained bytes.
internal object ProbeEvidenceSubmission {
    fun build(claim: ProbeAuthorizationRegistry.Claim.Consumed,
        observed: ProbeEvidenceValidator.Observation, payload: ByteArray): ProbeCoordinator.Submission {
        require(payload.size <= claim.scope.outputLimitBytes)
        val scope = claim.scope
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val envelope = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "probeStage" to "authorized-execution", "requestId" to observed.requestId,
            "authorization" to mapOf("capabilityId" to claim.capabilityId, "nonce" to scope.requestNonce,
                "scopeSha256" to scope.scopeSha256, "state" to "consumed"),
            "binding" to mapOf("inspectionSha256" to scope.inspectionSha256, "inventorySha256" to scope.inventorySha256,
                "probeScriptSha256" to scope.scriptSha256, "appBuildIdentity" to scope.appBuildIdentity,
                "processAbi" to observed.processAbi, "api" to observed.api),
            "execution" to mapOf("result" to observed.result, "durationMs" to observed.durationMs,
                "retainedOutputBytes" to observed.retainedOutputBytes, "droppedOutputBytes" to observed.droppedOutputBytes,
                "wallTimeLimitMs" to scope.wallTimeLimitMs, "outputLimitBytes" to scope.outputLimitBytes,
                "exitCode" to observed.exitCode, "signal" to observed.signal, "nativeStage" to observed.nativeStage),
            "evidence" to mapOf("payloadSha256" to hash, "payloadRef" to null),
            "cleanup" to mapOf("termination" to observed.termination, "artifacts" to observed.artifacts),
            "userScriptLaunchAuthorized" to false, "timestamp" to observed.timestamp, "attestation" to "unsigned")
        val raw = GsonBuilder().serializeNulls().create().toJson(envelope).toByteArray(Charsets.UTF_8)
        check(raw.size <= ProbeEvidenceValidator.MAX_ENVELOPE_BYTES)
        return ProbeCoordinator.Submission(raw, payload.copyOf(), observed)
    }
}
