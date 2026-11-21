package org.nemack.universalfilelab.etroute

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProbeAuthorizationRegistryTest {
    private fun scope(nonce: String = "request-1") = ProbeScope(
        nonce, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
        "build-1", "device-1", "/app/lib/libpython-worker.so", 5000, 8192)
    private fun reason(claim: ProbeAuthorizationRegistry.Claim) =
        (claim as ProbeAuthorizationRegistry.Claim.Rejected).reason

    @Test fun claimIsIrreversibleEvenWithoutExecutionOrEvidence() {
        val registry = ProbeAuthorizationRegistry(); val scope = scope()
        val capability = registry.issueApprovedScope(scope, 1000)
        assertTrue(registry.claim(capability, scope) is ProbeAuthorizationRegistry.Claim.Consumed)
        assertEquals(ProbeAuthorizationRegistry.Rejection.CONSUMED, reason(registry.claim(capability, scope)))
        assertFalse(registry.revoke(capability))
    }

    @Test fun concurrentClaimsHaveExactlyOneWinner() {
        val registry = ProbeAuthorizationRegistry(); val scope = scope()
        val capability = registry.issueApprovedScope(scope, 60_000)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..8).map { pool.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS)); registry.claim(capability, scope)
            }) }
            start.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ProbeAuthorizationRegistry.Claim.Consumed })
            assertEquals(7, results.count { it is ProbeAuthorizationRegistry.Claim.Rejected })
        } finally { pool.shutdownNow() }
    }

    @Test fun exactDeadlineAndClockRollbackReject() {
        var now = 10L
        val registry = ProbeAuthorizationRegistry(nanoTime = { now }); val scope = scope()
        val capability = registry.issueApprovedScope(scope, 1)
        now += 1_000_000
        assertEquals(ProbeAuthorizationRegistry.Rejection.EXPIRED, reason(registry.claim(capability, scope)))
        val other = registry.issueApprovedScope(scope("request-2"), 1)
        now--
        assertEquals(ProbeAuthorizationRegistry.Rejection.EXPIRED, reason(registry.claim(other, scope("request-2"))))
    }

    @Test fun everyBindingMismatchRevokesCapability() {
        val original = scope()
        val variants = listOf(original.copy(requestNonce = "other"), original.copy(inspectionSha256 = "e".repeat(64)),
            original.copy(inventorySha256 = "e".repeat(64)), original.copy(scriptSha256 = "e".repeat(64)),
            original.copy(scopeSha256 = "e".repeat(64)), original.copy(appBuildIdentity = "other"),
            original.copy(deviceBinding = "other"), original.copy(executable = "/other/python"),
            original.copy(wallTimeLimitMs = 6000), original.copy(outputLimitBytes = 4096))
        for (changed in variants) {
            val registry = ProbeAuthorizationRegistry(); val cap = registry.issueApprovedScope(original, 1000)
            assertEquals(ProbeAuthorizationRegistry.Rejection.SCOPE_MISMATCH, reason(registry.claim(cap, changed)))
            assertEquals(ProbeAuthorizationRegistry.Rejection.REVOKED, reason(registry.claim(cap, original)))
        }
    }

    @Test fun revocationRestartAndForgedIdentifierReject() {
        val registry = ProbeAuthorizationRegistry(); val scope = scope()
        val cap = registry.issueApprovedScope(scope, 1000)
        assertEquals(ProbeAuthorizationRegistry.Rejection.UNKNOWN,
            reason(registry.claim(ProbeAuthorizationRegistry.Capability(cap.id), scope)))
        assertEquals(ProbeAuthorizationRegistry.Rejection.UNKNOWN,
            reason(ProbeAuthorizationRegistry().claim(cap, scope)))
        assertTrue(registry.revoke(cap))
        assertEquals(ProbeAuthorizationRegistry.Rejection.REVOKED, reason(registry.claim(cap, scope)))
    }

    @Test fun invalidationClosesIssuanceAndRetainsRevocation() {
        val registry = ProbeAuthorizationRegistry(); val scope = scope()
        val cap = registry.issueApprovedScope(scope, 1000); registry.invalidateAll()
        assertEquals(ProbeAuthorizationRegistry.Rejection.REVOKED, reason(registry.claim(cap, scope)))
        try { registry.issueApprovedScope(scope("new"), 1000); fail("Closed registry issued") }
        catch (_: IllegalStateException) { }
    }

    @Test fun capacityAndNonceTombstonesFailClosed() {
        val registry = ProbeAuthorizationRegistry(maxEntries = 2); val scope = scope()
        val cap = registry.issueApprovedScope(scope, 1000); registry.claim(cap, scope)
        try { registry.issueApprovedScope(scope, 1000); fail("Nonce reused") }
        catch (_: IllegalStateException) { }
        registry.issueApprovedScope(scope("new"), 1000)
        try { registry.issueApprovedScope(scope("overflow"), 1000); fail("Capacity exceeded") }
        catch (_: IllegalStateException) { }
    }
}
