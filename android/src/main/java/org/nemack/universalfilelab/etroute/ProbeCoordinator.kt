package org.nemack.universalfilelab.etroute

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// Internal trusted composition root. No native runner, UI approval gate or Android adapter is installed.
@android.annotation.TargetApi(26)
internal class ProbeCoordinator(
    private val policy: Policy,
    private val preflight: (String) -> RuntimePackPreflightReport,
    private val approval: (ApprovalRequest) -> Boolean,
    private val runner: Runner? = null,
    private val registry: ProbeAuthorizationRegistry = ProbeAuthorizationRegistry(),
    private val maxTickets: Int = 256
) {
    internal data class Policy(
        val packId: String, val packVersion: String, val pythonVersion: String,
        val api: Int, val abi: String, val executable: String, val scriptSha256: String,
        val appBuildIdentity: String, val deviceBinding: String,
        val wallTimeLimitMs: Long = 5000, val outputLimitBytes: Long = 8192,
        val authorizationTtlMs: Long = 30_000
    )
    internal data class ApprovalRequest(val requestId: String, val scope: ProbeScope)
    internal data class Submission(val envelope: ByteArray, val payload: ByteArray,
        val observation: ProbeEvidenceValidator.Observation)
    // Implementations must enforce generation/disk/memory/time limits before constructing Submission.
    internal fun interface Runner {
        fun run(requestId: String, claim: ProbeAuthorizationRegistry.Claim.Consumed): Submission
    }
    internal class Ticket internal constructor(val requestId: String)
    internal enum class Reason {
        CLOSED, CAPACITY, UNKNOWN_TICKET, WRONG_STATE, PREFLIGHT, APPROVAL_DENIED,
        APPROVAL_ERROR, STALE_BINDING, REGISTRY_REJECTED, RUNNER_UNAVAILABLE, RUNNER_ERROR
    }
    internal sealed class Outcome {
        data class Inspected(val ticket: Ticket) : Outcome()
        object Authorized : Outcome()
        data class Rejected(val reason: Reason, val registryDetail: String? = null,
            val rejectionLocation: StackTraceElement? = null) : Outcome()
        data class Evidence(val validation: ProbeEvidenceValidator.Result) : Outcome()
    }
    private enum class State { INSPECTED, APPROVING, AUTHORIZED, DISPATCHING, FINISHED }
    private class Entry(val scope: ProbeScope) {
        val state = AtomicReference(State.INSPECTED)
        var capability: ProbeAuthorizationRegistry.Capability? = null
    }
    private val tickets = mutableMapOf<Ticket, Entry>()
    private val validator = ProbeEvidenceValidator(registry)
    private var closed = false

    init {
        require(maxTickets in 1..4096)
        require(policy.api >= 26 && policy.abi in setOf("arm64-v8a", "x86_64"))
        require(listOf(policy.packId, policy.packVersion, policy.pythonVersion).all { it.length in 1..128 })
        require(policy.authorizationTtlMs in 1..300_000)
        // No normalization of a caller's executable: trusted policy must already contain the canonical location.
        require(File(policy.executable).isAbsolute && File(policy.executable).canonicalPath == policy.executable)
        makeScope("policy-check", "0".repeat(64), "0".repeat(64))
    }

    fun inspect(): Outcome {
        synchronized(this) {
            if (closed) return Outcome.Rejected(Reason.CLOSED)
            if (tickets.size >= maxTickets) return Outcome.Rejected(Reason.CAPACITY)
        }
        val nonce = UUID.randomUUID().toString()
        val scope = inspectedScope(nonce) ?: return Outcome.Rejected(Reason.PREFLIGHT)
        synchronized(this) {
            if (closed) return Outcome.Rejected(Reason.CLOSED)
            if (tickets.size >= maxTickets) return Outcome.Rejected(Reason.CAPACITY)
            val ticket = Ticket(UUID.randomUUID().toString())
            tickets[ticket] = Entry(scope)
            return Outcome.Inspected(ticket)
        }
    }

    fun authorize(ticket: Ticket): Outcome {
        val entry = entry(ticket) ?: return unavailable(ticket)
        if (!entry.state.compareAndSet(State.INSPECTED, State.APPROVING)) return Outcome.Rejected(Reason.WRONG_STATE)
        if (runner == null) return finish(entry, Reason.RUNNER_UNAVAILABLE)
        val approved = try { approval(ApprovalRequest(ticket.requestId, entry.scope)) }
            catch (_: Exception) { return finish(entry, Reason.APPROVAL_ERROR) }
        if (!approved) return finish(entry, Reason.APPROVAL_DENIED)
        if (inspectedScope(entry.scope.requestNonce) != entry.scope) return finish(entry, Reason.STALE_BINDING)
        synchronized(this) {
            if (closed) return finish(entry, Reason.CLOSED)
            try { entry.capability = registry.issueApprovedScope(entry.scope, policy.authorizationTtlMs) }
            catch (error: IllegalStateException) {
                val detail = when (error.message) {
                    "Registry invalidated" -> "ISSUE_CLOSED"
                    "Authorization capacity exhausted" -> "ISSUE_CAPACITY"
                    "Request nonce already issued" -> "ISSUE_NONCE_REUSED"
                    else -> "ISSUE_REJECTED"
                }
                return rejectRegistry(entry, detail)
            }
            entry.state.set(State.AUTHORIZED)
            return Outcome.Authorized
        }
    }

    fun dispatch(ticket: Ticket): Outcome {
        val entry = entry(ticket) ?: return unavailable(ticket)
        if (!entry.state.compareAndSet(State.AUTHORIZED, State.DISPATCHING)) return Outcome.Rejected(Reason.WRONG_STATE)
        val boundRunner = runner ?: return finish(entry, Reason.RUNNER_UNAVAILABLE)
        val recheckStarted = System.nanoTime()
        if (inspectedScope(entry.scope.requestNonce) != entry.scope) return finish(entry, Reason.STALE_BINDING)
        val recheckMs = ((System.nanoTime() - recheckStarted) / 1_000_000).coerceAtLeast(0)
        val claim = synchronized(this) {
            if (closed) return finish(entry, Reason.CLOSED)
            val capability = entry.capability ?: return rejectRegistry(entry, "MISSING_CAPABILITY")
            registry.claim(capability, entry.scope)
        }
        if (claim is ProbeAuthorizationRegistry.Claim.Rejected) return rejectRegistry(entry,
            "CLAIM_${claim.reason}; dispatchPreflightMs=$recheckMs; authorizationTtlMs=${policy.authorizationTtlMs}")
        check(claim is ProbeAuthorizationRegistry.Claim.Consumed)
        // The claim is consumed before the trusted runner can see it. Exceptions never refund it.
        val submission = try { boundRunner.run(ticket.requestId, claim) }
            catch (_: Exception) { return finish(entry, Reason.RUNNER_ERROR) }
        entry.state.set(State.FINISHED)
        if (submission.observation.requestId != ticket.requestId) {
            registry.recordEvidenceAttempt(claim)
            return Outcome.Evidence(ProbeEvidenceValidator.Result.Rejected(ProbeEvidenceValidator.Reason.BINDING))
        }
        return Outcome.Evidence(validator.validate(submission.envelope, submission.payload, claim, submission.observation))
    }

    @Synchronized fun close() {
        closed = true
        registry.invalidateAll()
        // Closing blocks new claims. Already-dispatched work needs the future native Stop/lifecycle bridge.
    }

    @Synchronized private fun entry(ticket: Ticket): Entry? = if (closed) null else tickets[ticket]
    @Synchronized private fun unavailable(ticket: Ticket): Outcome =
        Outcome.Rejected(if (closed) Reason.CLOSED else if (ticket !in tickets) Reason.UNKNOWN_TICKET else Reason.WRONG_STATE)
    // Diagnostic capture identifies the rejecting gate, not an exception or proof of a defective line.
    private fun rejectRegistry(entry: Entry, detail: String): Outcome {
        val location = Throwable().stackTrace.getOrNull(1)
        finish(entry, Reason.REGISTRY_REJECTED)
        return Outcome.Rejected(Reason.REGISTRY_REJECTED, detail, location)
    }

    private fun finish(entry: Entry, reason: Reason): Outcome {
        entry.capability?.let { registry.revoke(it) }
        entry.state.set(State.FINISHED)
        return Outcome.Rejected(reason)
    }

    private fun inspectedScope(nonce: String): ProbeScope? = try {
        val report = preflight(policy.packId)
        val hashes = report.measuredSha256.toMap()
        if (report.status != RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE ||
            report.reason != RuntimePackCheckReason.DEVICE_PROBE_REQUIRED || report.packId != policy.packId ||
            report.packVersion != policy.packVersion || report.pythonVersion != policy.pythonVersion ||
            report.api != policy.api || report.processAbi != policy.abi || hashes.isEmpty() || hashes.size > 4096 ||
            hashes.any { (path, hash) -> path.length !in 1..512 || !hash.matches(Regex("[a-f0-9]{64}")) }) null
        else {
            val inventory = digest("etroute-probe-inventory-v1", hashes.toSortedMap().flatMap { listOf(it.key, it.value) })
            val inspection = digest("etroute-probe-inspection-v1", listOf(policy.packId, policy.packVersion,
                policy.pythonVersion, policy.api.toString(), policy.abi, inventory))
            makeScope(nonce, inspection, inventory)
        }
    } catch (_: Exception) { null }

    private fun makeScope(nonce: String, inspection: String, inventory: String): ProbeScope {
        val scopeDigest = digest("etroute-probe-scope-v1", listOf(nonce, inspection, inventory,
            policy.scriptSha256, policy.appBuildIdentity, policy.deviceBinding, policy.executable,
            policy.wallTimeLimitMs.toString(), policy.outputLimitBytes.toString()))
        return ProbeScope(nonce, inspection, inventory, policy.scriptSha256, scopeDigest,
            policy.appBuildIdentity, policy.deviceBinding, policy.executable, policy.wallTimeLimitMs, policy.outputLimitBytes)
    }

    // Domain + field count + UTF-8 byte-length-prefixed fields, all lengths signed int32 big-endian.
    private fun digest(domain: String, fields: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            val values = listOf(domain) + fields
            output.writeInt(values.size)
            values.forEach { value -> val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size); output.write(encoded) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
