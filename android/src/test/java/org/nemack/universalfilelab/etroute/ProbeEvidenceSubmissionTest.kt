package org.nemack.universalfilelab.etroute

import org.junit.Assert.*
import org.junit.Test

class ProbeEvidenceSubmissionTest {
    // User-reported output reproduced as a test fixture; these claims are test-only, not retroactive device authorization.
    private val payload = ("ETROUTE_PYTHON_PROBE_OK {\"python\": \"3.14.4\", \"openssl\": \"OpenSSL 3.0.19 27 Jan 2026\", \"sqlite\": \"3.50.4\"}\n").toByteArray()
    private val scope = ProbeScope("nonce", "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
        "build", "device", "/app/lib/worker.so", 10000, 8192)

    private fun checkSubmission(cancelled: Boolean): ProbeEvidenceValidator.Result.Validated {
        val registry = ProbeAuthorizationRegistry()
        val capability = registry.issueApprovedScope(scope, 30000)
        val claim = registry.claim(capability, scope) as ProbeAuthorizationRegistry.Claim.Consumed
        val bytes = if (cancelled) payload + "ETROUTE_PYTHON_PROBE_READY\n".toByteArray() else payload
        val observation = ProbeEvidenceValidator.Observation("device-fixture", "arm64-v8a", 35, "device",
            "2026-11-21T18:00:00Z", if (cancelled) "cancelled" else "completed", if (cancelled) 835 else 172,
            bytes.size.toLong(), 0, if (cancelled) null else 0, if (cancelled) 15 else null,
            if (cancelled) 14 else 0, "confirmed", "retained")
        val submission = ProbeEvidenceSubmission.build(claim, observation, bytes)
        val validator = ProbeEvidenceValidator(registry)
        val result = validator.validate(submission.envelope, submission.payload, claim, observation)
        assertEquals(ProbeEvidenceValidator.Reason.REPLAY_OR_UNTRUSTED_CLAIM,
            (validator.validate(submission.envelope, bytes, claim, observation) as ProbeEvidenceValidator.Result.Rejected).reason)
        return result as ProbeEvidenceValidator.Result.Validated
    }

    @Test fun reportedCompletionShapeValidatesWhileDiagnosticsRemainRetained() {
        val result = checkSubmission(false)
        assertTrue(result.successfulProbe); assertTrue(result.retainSession)
    }
    @Test fun reportedLifecycleShapeValidatesOnlyAsUnsuccessfulRetainedEvidence() {
        val result = checkSubmission(true)
        assertFalse(result.successfulProbe); assertTrue(result.retainSession)
    }
    @Test fun missingReceiptValidatesOnlyAsUnknownRetainedObservation() {
        val registry = ProbeAuthorizationRegistry()
        val claim = registry.claim(registry.issueApprovedScope(scope, 30000), scope) as ProbeAuthorizationRegistry.Claim.Consumed
        val observation = ProbeEvidenceValidator.Observation("receipt-loss", "arm64-v8a", 35, "device",
            "2026-11-21T18:00:00Z", "unknown", 335, payload.size.toLong(), 0,
            null, null, null, "unconfirmed", "retained")
        val submission = ProbeEvidenceSubmission.build(claim, observation, payload)
        val result = ProbeEvidenceValidator(registry).validate(submission.envelope, payload, claim, observation)
            as ProbeEvidenceValidator.Result.Validated
        assertFalse(result.successfulProbe); assertTrue(result.retainSession)
    }
    @Test fun builderRejectsOversizeBeforeSerialization() {
        val registry = ProbeAuthorizationRegistry()
        val claim = registry.claim(registry.issueApprovedScope(scope, 30000), scope) as ProbeAuthorizationRegistry.Claim.Consumed
        val observation = ProbeEvidenceValidator.Observation("request", "arm64-v8a", 35, "device",
            "2026-11-21T18:00:00Z", "failed", 1, 8193, 0, null, null, 15, "unconfirmed", "retained")
        try { ProbeEvidenceSubmission.build(claim, observation, ByteArray(8193)); fail("oversize accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
