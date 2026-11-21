package org.nemack.universalfilelab.etroute

import java.io.File
import java.util.UUID

// Trusted caller supplies a verified inspection binding and an explicitly approved fixed scope.
// This value and the registry do not verify inspection evidence or grant approval themselves.
internal data class ProbeScope(
    val requestNonce: String,
    val inspectionSha256: String,
    val inventorySha256: String,
    val scriptSha256: String,
    val scopeSha256: String,
    val appBuildIdentity: String,
    val deviceBinding: String,
    val executable: String,
    val wallTimeLimitMs: Long,
    val outputLimitBytes: Long
) {
    init {
        require(listOf(requestNonce, appBuildIdentity, deviceBinding).all { it.length in 1..128 })
        require(listOf(inspectionSha256, inventorySha256, scriptSha256, scopeSha256)
            .all { it.matches(Regex("[a-f0-9]{64}")) })
        require(executable.length in 1..4096 && File(executable).isAbsolute && '\u0000' !in executable)
        require(wallTimeLimitMs in 1..300_000 && outputLimitBytes in 0..16_777_216)
    }
}

// Process-local only. One authoritative instance must be owned by the trusted probe coordinator.
// No persistence, public token parser, native execution permit or Python launch is provided.
internal class ProbeAuthorizationRegistry(
    private val maxEntries: Int = 256,
    private val nanoTime: () -> Long = System::nanoTime
) {
    internal class Capability internal constructor(val id: String)
    internal enum class Rejection { UNKNOWN, CONSUMED, EXPIRED, REVOKED, SCOPE_MISMATCH }
    internal sealed class Claim {
        // A receipt records consumption only; it cannot be passed to NativeSupervisor to launch.
        data class Consumed(val capabilityId: String, val scope: ProbeScope) : Claim()
        data class Rejected(val reason: Rejection) : Claim()
    }
    private enum class State { ISSUED, CONSUMED, EXPIRED, REVOKED }
    private data class Entry(val scope: ProbeScope, val issued: Long, val ttlNanos: Long, var state: State)
    private val entries = mutableMapOf<Capability, Entry>()
    private val nonces = mutableSetOf<String>()
    private var closed = false

    init { require(maxEntries in 1..4096) }

    // Call only after trusted inspection validation and explicit scope approval.
    // No overload accepts caller-controlled evidence JSON or a consumed boolean.
    @Synchronized fun issueApprovedScope(scope: ProbeScope, ttlMs: Long): Capability {
        check(!closed) { "Registry invalidated" }
        require(ttlMs in 1..300_000)
        check(entries.size < maxEntries) { "Authorization capacity exhausted" }
        check(scope.requestNonce !in nonces) { "Request nonce already issued" }
        val capability = Capability(UUID.randomUUID().toString())
        entries[capability] = Entry(scope, nanoTime(), ttlMs * 1_000_000, State.ISSUED)
        nonces.add(scope.requestNonce)
        return capability
    }

    @Synchronized fun claim(capability: Capability, currentScope: ProbeScope): Claim {
        val entry = entries[capability] ?: return Claim.Rejected(Rejection.UNKNOWN)
        if (entry.state == State.ISSUED) {
            val elapsed = nanoTime() - entry.issued
            if (elapsed < 0 || elapsed >= entry.ttlNanos) entry.state = State.EXPIRED
        }
        val rejection = when (entry.state) {
            State.CONSUMED -> Rejection.CONSUMED
            State.EXPIRED -> Rejection.EXPIRED
            State.REVOKED -> Rejection.REVOKED
            State.ISSUED -> null
        }
        if (rejection != null) return Claim.Rejected(rejection)
        if (entry.scope != currentScope) {
            entry.state = State.REVOKED
            return Claim.Rejected(Rejection.SCOPE_MISMATCH)
        }
        entry.state = State.CONSUMED
        return Claim.Consumed(capability.id, entry.scope)
    }

    @Synchronized fun revoke(capability: Capability): Boolean {
        val entry = entries[capability] ?: return false
        if (entry.state != State.ISSUED) return false
        entry.state = State.REVOKED
        return true
    }

    // Retain bounded tombstones; never delete an entry to make a nonce reusable.
    // A replacement instance rejects every old capability by object identity.
    @Synchronized fun invalidateAll() {
        closed = true
        entries.values.filter { it.state == State.ISSUED }.forEach { it.state = State.REVOKED }
    }
}
