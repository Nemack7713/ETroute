package org.nemack.universalfilelab.etroute

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProbeEvidenceValidatorTest {
    private val payload = "probe output".toByteArray()
    private val scope = ProbeScope("nonce", "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
        "build", "device", "/app/lib/python.so", 5000, 8192)
    private fun observation(result: String = "completed", termination: String = "confirmed", artifacts: String = "removed") =
        ProbeEvidenceValidator.Observation("request", "arm64-v8a", 35, "device", "2026-11-21T00:00:00Z",
            result, 1200, payload.size.toLong(), 0, 0, null, 0, termination, artifacts)
    private class Fixture(val registry: ProbeAuthorizationRegistry, val claim: ProbeAuthorizationRegistry.Claim.Consumed,
        val json: JsonObject, val validator: ProbeEvidenceValidator)
    private fun fixture(observed: ProbeEvidenceValidator.Observation = observation(), clock: () -> Long = { 0 }): Fixture {
        val registry = ProbeAuthorizationRegistry()
        val cap = registry.issueApprovedScope(scope, 60_000)
        val claim = registry.claim(cap, scope) as ProbeAuthorizationRegistry.Claim.Consumed
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        val json = JsonParser.parseString("""{
          "schemaVersion":1,"probeStage":"authorized-execution","requestId":"request",
          "authorization":{"capabilityId":"${claim.capabilityId}","nonce":"nonce","scopeSha256":"${scope.scopeSha256}","state":"consumed"},
          "binding":{"inspectionSha256":"${scope.inspectionSha256}","inventorySha256":"${scope.inventorySha256}","probeScriptSha256":"${scope.scriptSha256}","appBuildIdentity":"build","processAbi":"arm64-v8a","api":35},
          "execution":{"result":"${observed.result}","durationMs":${observed.durationMs},"retainedOutputBytes":${payload.size},"droppedOutputBytes":0,"wallTimeLimitMs":5000,"outputLimitBytes":8192,"exitCode":0,"signal":null,"nativeStage":0},
          "evidence":{"payloadSha256":"$hash","payloadRef":null},
          "cleanup":{"termination":"${observed.termination}","artifacts":"${observed.artifacts}"},
          "userScriptLaunchAuthorized":false,"timestamp":"2026-11-21T00:00:00Z","attestation":"unsigned"
        }""").asJsonObject
        return Fixture(registry, claim, json, ProbeEvidenceValidator(registry, clock))
    }
    private fun validate(f: Fixture, bytes: ByteArray = f.json.toString().toByteArray(), data: ByteArray = payload,
        observed: ProbeEvidenceValidator.Observation = observation()) = f.validator.validate(bytes, data, f.claim, observed)
    private fun rejected(result: ProbeEvidenceValidator.Result, reason: ProbeEvidenceValidator.Reason) =
        assertEquals(reason, (result as ProbeEvidenceValidator.Result.Rejected).reason)

    @Test fun validEvidenceRequiresAuthoritativeClaimAndCannotBeReplayed() {
        val f = fixture(); val result = validate(f) as ProbeEvidenceValidator.Result.Validated
        assertTrue(result.successfulProbe); assertFalse(result.retainSession)
        rejected(validate(f), ProbeEvidenceValidator.Reason.REPLAY_OR_UNTRUSTED_CLAIM)
    }
    @Test fun oversizeIsRejectedBeforeParseAndBurnsAttempt() {
        val f = fixture()
        rejected(validate(f, ByteArray(ProbeEvidenceValidator.MAX_ENVELOPE_BYTES + 1)), ProbeEvidenceValidator.Reason.BOUNDS)
        rejected(validate(f), ProbeEvidenceValidator.Reason.REPLAY_OR_UNTRUSTED_CLAIM)
    }
    @Test fun malformedUtf8DuplicateKeysAndTrailingDocumentsReject() {
        val cases = listOf(byteArrayOf(0xc3.toByte(), 0x28), "{\"x\":1,\"x\":2}".toByteArray(),
            "{} {}".toByteArray(), "{ /*comment*/ }".toByteArray(), "{\"x\":[1]}".toByteArray())
        for (bytes in cases) rejected(validate(fixture(), bytes), ProbeEvidenceValidator.Reason.PARSE)
    }
    @Test fun depthAndStringLimitsReject() {
        rejected(validate(fixture(), "{\"a\":{\"b\":{\"c\":{\"d\":{}}}}}".toByteArray()), ProbeEvidenceValidator.Reason.BOUNDS)
        rejected(validate(fixture(), ("{\"a\":\"" + "x".repeat(257) + "\"}").toByteArray()), ProbeEvidenceValidator.Reason.BOUNDS)
    }
    @Test fun schemaMissingUnknownTypesVersionAndCalendarReject() {
        val edits: List<(JsonObject) -> Unit> = listOf(
            { it.remove("cleanup"); Unit }, { it.addProperty("launchPython", true) },
            { it.addProperty("schemaVersion", 2) }, { it.addProperty("schemaVersion", "1") },
            { it.addProperty("timestamp", "2026-02-30T00:00:00Z") },
            { it.addProperty("userScriptLaunchAuthorized", true) },
            { it.getAsJsonObject("execution").addProperty("durationMs", -1) },
            { it.getAsJsonObject("authorization").addProperty("state", "issued") },
            { it.getAsJsonObject("evidence").addProperty("payloadRef", "../payload") })
        for (edit in edits) { val f = fixture(); edit(f.json); rejected(validate(f), ProbeEvidenceValidator.Reason.SCHEMA) }
    }
    @Test fun envelopeReorderingDoesNotChangeExactPayloadHash() {
        val f = fixture(); val reversed = JsonObject()
        f.json.entrySet().toList().reversed().forEach { reversed.add(it.key, it.value) }
        assertTrue((validate(f, reversed.toString().toByteArray()) as ProbeEvidenceValidator.Result.Validated).successfulProbe)
    }
    @Test fun scopeAndObservationMismatchReject() {
        for (field in listOf("inspectionSha256", "inventorySha256", "probeScriptSha256")) {
            val f = fixture(); f.json.getAsJsonObject("binding").addProperty(field, "e".repeat(64))
            rejected(validate(f), ProbeEvidenceValidator.Reason.BINDING)
        }
        val f = fixture(); rejected(validate(f, observed = observation().copy(deviceBinding = "other")), ProbeEvidenceValidator.Reason.BINDING)
        val other = fixture(); rejected(validate(other, observed = observation().copy(durationMs = 1)), ProbeEvidenceValidator.Reason.BINDING)
    }
    @Test fun payloadTamperingAndBoundsReject() {
        val f = fixture(); rejected(validate(f, data = ByteArray(payload.size)), ProbeEvidenceValidator.Reason.HASH)
        rejected(validate(fixture(), data = ByteArray(8193)), ProbeEvidenceValidator.Reason.BOUNDS)
    }
    @Test fun forgedReceiptAndDifferentRegistryReject() {
        val f = fixture(); val forged = f.claim.copy()
        rejected(f.validator.validate(f.json.toString().toByteArray(), payload, forged, observation()),
            ProbeEvidenceValidator.Reason.REPLAY_OR_UNTRUSTED_CLAIM)
        val validator = ProbeEvidenceValidator(ProbeAuthorizationRegistry())
        rejected(validator.validate(f.json.toString().toByteArray(), payload, f.claim, observation()),
            ProbeEvidenceValidator.Reason.REPLAY_OR_UNTRUSTED_CLAIM)
    }
    @Test fun unconfirmedFailureIsRetainableButNotSuccessful() {
        val observed = observation("failed", "unconfirmed", "retained"); val f = fixture(observed)
        val result = validate(f, observed = observed) as ProbeEvidenceValidator.Result.Validated
        assertFalse(result.successfulProbe); assertTrue(result.retainSession)
    }
    @Test fun completedWithFailureStageOrBudgetOverrunCannotPromote() {
        for (observed in listOf(observation().copy(nativeStage = 4), observation().copy(durationMs = 5001))) {
            val f = fixture(observed)
            f.json.getAsJsonObject("execution").addProperty("nativeStage", observed.nativeStage)
            rejected(validate(f, observed = observed), ProbeEvidenceValidator.Reason.OUTCOME)
        }
    }
    @Test fun deadlineAndConcurrentSubmissionFailClosed() {
        var now = 0L
        val timed = fixture(clock = { now.also { now += ProbeEvidenceValidator.MAX_PARSE_NANOS + 1 } })
        rejected(validate(timed), ProbeEvidenceValidator.Reason.BOUNDS)
        val f = fixture(); val raw = f.json.toString().toByteArray(); val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { pool.submit(Callable { validate(f, raw) }) }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ProbeEvidenceValidator.Result.Validated })
            assertEquals(3, results.count { it is ProbeEvidenceValidator.Result.Rejected })
        } finally { pool.shutdownNow() }
    }
}
