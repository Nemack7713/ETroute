package org.nemack.universalfilelab.etroute

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProbeCoordinatorTest {
    private val policy = ProbeCoordinator.Policy("python", "pack-1", "3.13.9", 35, "arm64-v8a",
        "/app/lib/python.so", "a".repeat(64), "build", "device")
    private fun report() = RuntimePackPreflightReport("python", RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE,
        RuntimePackCheckReason.DEVICE_PROBE_REQUIRED, 35, "arm64-v8a", "pack-1", "3.13.9",
        linkedMapOf("native/python.so" to "b".repeat(64), "payload/stdlib.zip" to "c".repeat(64)))
    private fun ticket(coordinator: ProbeCoordinator) = (coordinator.inspect() as ProbeCoordinator.Outcome.Inspected).ticket
    private fun rejected(outcome: ProbeCoordinator.Outcome, reason: ProbeCoordinator.Reason) =
        assertEquals(reason, (outcome as ProbeCoordinator.Outcome.Rejected).reason)
    private fun submission(id: String, claim: ProbeAuthorizationRegistry.Claim.Consumed): ProbeCoordinator.Submission {
        val scope = claim.scope; val timestamp = "2026-11-21T00:00:00Z"
        val observation = ProbeEvidenceValidator.Observation(id, "arm64-v8a", 35, "device", timestamp,
            "completed", 1, 0, 0, 0, null, 0, "confirmed", "none")
        val json = mapOf("schemaVersion" to 1, "probeStage" to "authorized-execution", "requestId" to id,
            "authorization" to mapOf("capabilityId" to claim.capabilityId, "nonce" to scope.requestNonce,
                "scopeSha256" to scope.scopeSha256, "state" to "consumed"),
            "binding" to mapOf("inspectionSha256" to scope.inspectionSha256, "inventorySha256" to scope.inventorySha256,
                "probeScriptSha256" to scope.scriptSha256, "appBuildIdentity" to scope.appBuildIdentity,
                "processAbi" to "arm64-v8a", "api" to 35),
            "execution" to mapOf("result" to "completed", "durationMs" to 1, "retainedOutputBytes" to 0,
                "droppedOutputBytes" to 0, "wallTimeLimitMs" to scope.wallTimeLimitMs,
                "outputLimitBytes" to scope.outputLimitBytes, "exitCode" to 0, "signal" to null, "nativeStage" to 0),
            "evidence" to mapOf("payloadSha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "payloadRef" to null), "cleanup" to mapOf("termination" to "confirmed", "artifacts" to "none"),
            "userScriptLaunchAuthorized" to false, "timestamp" to timestamp, "attestation" to "unsigned")
        return ProbeCoordinator.Submission(com.google.gson.GsonBuilder().serializeNulls().create().toJson(json).toByteArray(),
            byteArrayOf(), observation)
    }
    private val goodRunner = ProbeCoordinator.Runner { id, claim -> submission(id, claim) }

    @Test fun fullPipelineRechecksAndValidatesWithoutNativeExecution() {
        var checks = 0; var approvals = 0
        val coordinator = ProbeCoordinator(policy, { checks++; report() }, { approvals++; true }, goodRunner)
        val ticket = ticket(coordinator)
        assertEquals(ProbeCoordinator.Outcome.Authorized, coordinator.authorize(ticket))
        val result = coordinator.dispatch(ticket) as ProbeCoordinator.Outcome.Evidence
        assertTrue((result.validation as ProbeEvidenceValidator.Result.Validated).successfulProbe)
        assertEquals(3, checks); assertEquals(1, approvals)
        rejected(coordinator.dispatch(ticket), ProbeCoordinator.Reason.WRONG_STATE)
    }
    @Test fun failedPreflightDoesNotRequestApproval() {
        var approvals = 0
        val coordinator = ProbeCoordinator(policy, { report().copy(status = RuntimePackCheckStatus.REJECTED) },
            { approvals++; true }, goodRunner)
        rejected(coordinator.inspect(), ProbeCoordinator.Reason.PREFLIGHT); assertEquals(0, approvals)
    }
    @Test fun everyIdentityMismatchRejectsInspection() {
        val variants = listOf(report().copy(packId = "other"), report().copy(packVersion = "other"),
            report().copy(pythonVersion = "other"), report().copy(api = 34), report().copy(processAbi = "x86_64"),
            report().copy(measuredSha256 = emptyMap()), report().copy(measuredSha256 = mapOf("x" to "bad")))
        for (value in variants) rejected(ProbeCoordinator(policy, { value }, { true }, goodRunner).inspect(),
            ProbeCoordinator.Reason.PREFLIGHT)
    }
    @Test fun missingRunnerNeverRequestsApprovalOrIssuesClaim() {
        var approvals = 0
        val coordinator = ProbeCoordinator(policy, { report() }, { approvals++; true })
        rejected(coordinator.authorize(ticket(coordinator)), ProbeCoordinator.Reason.RUNNER_UNAVAILABLE)
        assertEquals(0, approvals)
    }
    @Test fun denialAndApprovalExceptionAreTerminal() {
        for (throws in listOf(false, true)) {
            val coordinator = ProbeCoordinator(policy, { report() }, { if (throws) error("gate") else false }, goodRunner)
            val ticket = ticket(coordinator)
            rejected(coordinator.authorize(ticket), if (throws) ProbeCoordinator.Reason.APPROVAL_ERROR else ProbeCoordinator.Reason.APPROVAL_DENIED)
            rejected(coordinator.authorize(ticket), ProbeCoordinator.Reason.WRONG_STATE)
        }
    }
    @Test fun packChangeDuringApprovalRejectsBeforeIssuance() {
        var current = report()
        val coordinator = ProbeCoordinator(policy, { current }, {
            current = current.copy(measuredSha256 = mapOf("changed" to "d".repeat(64))); true
        }, goodRunner)
        rejected(coordinator.authorize(ticket(coordinator)), ProbeCoordinator.Reason.STALE_BINDING)
    }
    @Test fun packChangeBeforeDispatchNeverCallsRunner() {
        var current = report(); var calls = 0
        val coordinator = ProbeCoordinator(policy, { current }, { true }, ProbeCoordinator.Runner { _, _ -> calls++; error("run") })
        val ticket = ticket(coordinator); coordinator.authorize(ticket)
        current = current.copy(measuredSha256 = mapOf("changed" to "d".repeat(64)))
        rejected(coordinator.dispatch(ticket), ProbeCoordinator.Reason.STALE_BINDING); assertEquals(0, calls)
    }
    @Test fun inventoryOrderingIsDeterministic() {
        var checks = 0
        val coordinator = ProbeCoordinator(policy, {
            checks++; val r = report()
            if (checks % 2 == 0) r.copy(measuredSha256 = r.measuredSha256.entries.reversed().associate { it.toPair() }) else r
        }, { true }, goodRunner)
        val ticket = ticket(coordinator)
        assertEquals(ProbeCoordinator.Outcome.Authorized, coordinator.authorize(ticket))
        assertTrue(coordinator.dispatch(ticket) is ProbeCoordinator.Outcome.Evidence)
    }
    @Test fun expiredClaimCannotDispatch() {
        var now = 0L
        val coordinator = ProbeCoordinator(policy, { report() }, { true }, goodRunner,
            ProbeAuthorizationRegistry(nanoTime = { now }))
        val ticket = ticket(coordinator); coordinator.authorize(ticket); now = 30_000_000_000L
        val outcome = coordinator.dispatch(ticket) as ProbeCoordinator.Outcome.Rejected
        rejected(outcome, ProbeCoordinator.Reason.REGISTRY_REJECTED)
        assertTrue(outcome.registryDetail!!.startsWith("CLAIM_EXPIRED;"))
        assertEquals("ProbeCoordinator.kt", outcome.rejectionLocation!!.fileName)
        assertTrue(outcome.rejectionLocation.lineNumber > 0)
        rejected(coordinator.dispatch(ticket), ProbeCoordinator.Reason.WRONG_STATE)
    }
    @Test fun expiryDuringDispatchPreflightRejectsWithoutRunningOrRefunding() {
        var now = 0L; var checks = 0; var calls = 0
        val coordinator = ProbeCoordinator(policy, {
            checks++
            if (checks == 3) now = 30_000_000_000L
            report()
        }, { true }, ProbeCoordinator.Runner { id, claim -> calls++; submission(id, claim) },
            ProbeAuthorizationRegistry(nanoTime = { now }))
        val ticket = ticket(coordinator)
        assertEquals(ProbeCoordinator.Outcome.Authorized, coordinator.authorize(ticket))
        val outcome = coordinator.dispatch(ticket) as ProbeCoordinator.Outcome.Rejected
        assertTrue(outcome.registryDetail!!.contains("CLAIM_EXPIRED"))
        assertTrue(outcome.registryDetail.contains("authorizationTtlMs=30000"))
        assertEquals(0, calls)
        rejected(coordinator.dispatch(ticket), ProbeCoordinator.Reason.WRONG_STATE)
    }
    @Test fun exhaustedRegistryReportsIssuanceGateAndRemainsTerminal() {
        val coordinator = ProbeCoordinator(policy, { report() }, { true }, goodRunner,
            ProbeAuthorizationRegistry(maxEntries = 1))
        val first = ticket(coordinator)
        assertEquals(ProbeCoordinator.Outcome.Authorized, coordinator.authorize(first))
        val second = ticket(coordinator)
        val outcome = coordinator.authorize(second) as ProbeCoordinator.Outcome.Rejected
        assertEquals("ISSUE_CAPACITY", outcome.registryDetail)
        assertEquals("ProbeCoordinator.kt", outcome.rejectionLocation!!.fileName)
        rejected(coordinator.dispatch(second), ProbeCoordinator.Reason.WRONG_STATE)
    }
    @Test fun concurrentDispatchesRunExactlyOnceAndExceptionCannotRetry() {
        val calls = AtomicInteger()
        val coordinator = ProbeCoordinator(policy, { report() }, { true }, ProbeCoordinator.Runner { _, _ ->
            calls.incrementAndGet(); error("runner failed") })
        val ticket = ticket(coordinator); coordinator.authorize(ticket)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { pool.submit(Callable { coordinator.dispatch(ticket) }) }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertEquals(1, results.count { it == ProbeCoordinator.Outcome.Rejected(ProbeCoordinator.Reason.RUNNER_ERROR) })
            rejected(coordinator.dispatch(ticket), ProbeCoordinator.Reason.WRONG_STATE)
        } finally { pool.shutdownNow() }
    }
    @Test fun closeForgeryAndCapacityFailClosed() {
        val coordinator = ProbeCoordinator(policy, { report() }, { true }, goodRunner, maxTickets = 1)
        val ticket = ticket(coordinator)
        rejected(coordinator.authorize(ProbeCoordinator.Ticket(ticket.requestId)), ProbeCoordinator.Reason.UNKNOWN_TICKET)
        rejected(coordinator.inspect(), ProbeCoordinator.Reason.CAPACITY)
        coordinator.close()
        rejected(coordinator.authorize(ticket), ProbeCoordinator.Reason.CLOSED)
        rejected(coordinator.inspect(), ProbeCoordinator.Reason.CLOSED)
    }
    @Test fun foreignObservationRequestIsRejected() {
        val coordinator = ProbeCoordinator(policy, { report() }, { true }, ProbeCoordinator.Runner { id, claim ->
            submission(id, claim).let { it.copy(observation = it.observation.copy(requestId = "other")) }
        })
        val ticket = ticket(coordinator); coordinator.authorize(ticket)
        val result = coordinator.dispatch(ticket) as ProbeCoordinator.Outcome.Evidence
        assertEquals(ProbeEvidenceValidator.Result.Rejected(ProbeEvidenceValidator.Reason.BINDING), result.validation)
    }
}
