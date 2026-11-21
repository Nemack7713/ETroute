package org.nemack.universalfilelab.etroute

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime

// API 26+ like RuntimePack preflight. No file resolution, execution, persistence or authorization.
@android.annotation.TargetApi(26)
internal class ProbeEvidenceValidator(
    private val registry: ProbeAuthorizationRegistry,
    private val nanoTime: () -> Long = System::nanoTime
) {
    internal enum class Reason { REPLAY_OR_UNTRUSTED_CLAIM, BOUNDS, PARSE, SCHEMA, BINDING, HASH, OUTCOME }
    internal sealed class Result {
        data class Rejected(val reason: Reason) : Result()
        // This result is eligible for later storage only, never a runtime admission/launch token.
        data class Validated(val successfulProbe: Boolean, val retainSession: Boolean) : Result()
    }
    // Trusted coordinator supplies these from its actual probe observation, not the envelope.
    internal data class Observation(
        val requestId: String, val processAbi: String, val api: Int, val deviceBinding: String,
        val timestamp: String,
        val result: String, val durationMs: Long, val retainedOutputBytes: Long,
        val droppedOutputBytes: Long, val exitCode: Long?, val signal: Long?, val nativeStage: Long?,
        val termination: String, val artifacts: String
    )
    private class Invalid(val reason: Reason) : Exception()
    private fun reject(reason: Reason): Nothing = throw Invalid(reason)

    fun validate(raw: ByteArray, payload: ByteArray, claim: ProbeAuthorizationRegistry.Claim.Consumed,
        observed: Observation): Result {
        // Reserve before bounds/parse: even malformed submissions cannot reuse the same claim.
        if (!registry.recordEvidenceAttempt(claim)) return Result.Rejected(Reason.REPLAY_OR_UNTRUSTED_CLAIM)
        return try {
            val scope = claim.scope
            if (raw.isEmpty() || raw.size > MAX_ENVELOPE_BYTES || payload.size > scope.outputLimitBytes)
                reject(Reason.BOUNDS)
            val started = nanoTime()
            fun budget() {
                val elapsed = nanoTime() - started
                if (elapsed < 0 || elapsed > MAX_PARSE_NANOS) reject(Reason.BOUNDS)
            }
            val bytes = raw.copyOf(); val actualPayload = payload.copyOf()
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: Exception) { reject(Reason.PARSE) }
            val root = try {
                JsonReader(StringReader(text)).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    val parsed = readObject(reader, 1, ::budget)
                    if (reader.peek() != JsonToken.END_DOCUMENT) reject(Reason.PARSE)
                    parsed
                }
            } catch (error: Invalid) { throw error }
              catch (_: Exception) { reject(Reason.PARSE) }
            budget()
            fields(root, "schemaVersion", "probeStage", "requestId", "authorization", "binding", "execution",
                "evidence", "cleanup", "userScriptLaunchAuthorized", "timestamp", "attestation")
            if (number(root, "schemaVersion") != 1L || string(root, "probeStage") != "authorized-execution" ||
                root["userScriptLaunchAuthorized"] != false || string(root, "attestation") != "unsigned") reject(Reason.SCHEMA)
            val request = identifier(root, "requestId")
            val authorization = objectAt(root, "authorization")
            fields(authorization, "capabilityId", "nonce", "scopeSha256", "state")
            val capability = identifier(authorization, "capabilityId")
            val nonce = identifier(authorization, "nonce")
            val scopeHash = digest(authorization, "scopeSha256")
            if (string(authorization, "state") != "consumed") reject(Reason.SCHEMA)
            val binding = objectAt(root, "binding")
            fields(binding, "inspectionSha256", "inventorySha256", "probeScriptSha256", "appBuildIdentity", "processAbi", "api")
            val inspectionHash = digest(binding, "inspectionSha256")
            val inventoryHash = digest(binding, "inventorySha256")
            val scriptHash = digest(binding, "probeScriptSha256")
            val build = identifier(binding, "appBuildIdentity")
            val abi = string(binding, "processAbi")
            val api = number(binding, "api")
            if (abi !in setOf("arm64-v8a", "x86_64") || api !in 26L..10000L) reject(Reason.SCHEMA)
            val execution = objectAt(root, "execution")
            fields(execution, "result", "durationMs", "retainedOutputBytes", "droppedOutputBytes", "wallTimeLimitMs",
                "outputLimitBytes", "exitCode", "signal", "nativeStage")
            val outcome = string(execution, "result")
            if (outcome !in OUTCOMES) reject(Reason.SCHEMA)
            val duration = number(execution, "durationMs")
            val retained = number(execution, "retainedOutputBytes")
            val dropped = number(execution, "droppedOutputBytes")
            val wallLimit = number(execution, "wallTimeLimitMs")
            val outputLimit = number(execution, "outputLimitBytes")
            if (wallLimit == 0L) reject(Reason.SCHEMA)
            val exit = nullableNumber(execution, "exitCode")
            val signal = nullableNumber(execution, "signal")
            val stage = nullableNumber(execution, "nativeStage")
            val evidence = objectAt(root, "evidence")
            fields(evidence, "payloadSha256", "payloadRef")
            val payloadHash = digest(evidence, "payloadSha256")
            // v1 accepts supplied bytes only. Never dereference an untrusted path.
            if (evidence["payloadRef"] != null) reject(Reason.SCHEMA)
            val cleanup = objectAt(root, "cleanup")
            fields(cleanup, "termination", "artifacts")
            val termination = string(cleanup, "termination")
            val artifacts = string(cleanup, "artifacts")
            if (termination !in setOf("confirmed", "not-started", "unconfirmed") ||
                artifacts !in setOf("removed", "retained", "none")) reject(Reason.SCHEMA)
            val timestamp = string(root, "timestamp")
            if (timestamp.length > 40 || !timestamp.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")))
                reject(Reason.SCHEMA)
            try { OffsetDateTime.parse(timestamp) } catch (_: Exception) { reject(Reason.SCHEMA) }
            if (timestamp != observed.timestamp || scope.deviceBinding != observed.deviceBinding ||
                request != observed.requestId || capability != claim.capabilityId || nonce != scope.requestNonce ||
                scopeHash != scope.scopeSha256 || inspectionHash != scope.inspectionSha256 ||
                inventoryHash != scope.inventorySha256 || scriptHash != scope.scriptSha256 ||
                build != scope.appBuildIdentity || abi != observed.processAbi || api != observed.api.toLong() ||
                wallLimit != scope.wallTimeLimitMs || outputLimit != scope.outputLimitBytes) reject(Reason.BINDING)
            if (duration != observed.durationMs || retained != observed.retainedOutputBytes ||
                dropped != observed.droppedOutputBytes || outcome != observed.result || exit != observed.exitCode ||
                signal != observed.signal || stage != observed.nativeStage || termination != observed.termination ||
                artifacts != observed.artifacts) reject(Reason.BINDING)
            if (retained != actualPayload.size.toLong() || retained > scope.outputLimitBytes) reject(Reason.BOUNDS)
            val hash = MessageDigest.getInstance("SHA-256").digest(actualPayload)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash != payloadHash) reject(Reason.HASH)
            budget()
            val success = outcome == "completed" && exit == 0L && signal == null &&
                stage == 0L && termination == "confirmed" && artifacts in setOf("removed", "none", "retained") && duration <= scope.wallTimeLimitMs
            if (outcome == "completed" && !success) reject(Reason.OUTCOME)
            // Bound overruns and cleanup uncertainty remain recordable as unsuccessful observations.
            Result.Validated(success, termination == "unconfirmed" || artifacts == "retained")
        } catch (error: Invalid) { Result.Rejected(error.reason) }
    }

    private fun readObject(reader: JsonReader, depth: Int, budget: () -> Unit): Map<String, Any?> {
        budget()
        if (depth > MAX_DEPTH) reject(Reason.BOUNDS)
        reader.beginObject()
        val result = linkedMapOf<String, Any?>()
        while (reader.hasNext()) {
            budget()
            if (result.size >= MAX_FIELDS) reject(Reason.BOUNDS)
            val key = reader.nextName()
            if (key.length > MAX_STRING_CHARS || result.containsKey(key)) reject(Reason.PARSE)
            result[key] = when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> readObject(reader, depth + 1, budget)
                JsonToken.STRING -> reader.nextString().also { if (it.length > MAX_STRING_CHARS) reject(Reason.BOUNDS) }
                JsonToken.NUMBER -> {
                    val token = reader.nextString()
                    if (!token.matches(Regex("-?(0|[1-9][0-9]*)"))) reject(Reason.SCHEMA)
                    token.toLongOrNull() ?: reject(Reason.SCHEMA)
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> reject(Reason.PARSE)
            }
        }
        reader.endObject()
        return result
    }
    private fun fields(value: Map<String, Any?>, vararg keys: String) {
        if (value.keys != keys.toSet()) reject(Reason.SCHEMA)
    }
    @Suppress("UNCHECKED_CAST")
    private fun objectAt(value: Map<String, Any?>, key: String): Map<String, Any?> =
        value[key] as? Map<String, Any?> ?: reject(Reason.SCHEMA)
    private fun string(value: Map<String, Any?>, key: String): String = value[key] as? String ?: reject(Reason.SCHEMA)
    private fun identifier(value: Map<String, Any?>, key: String): String = string(value, key).also {
        if (it.length !in 1..128) reject(Reason.SCHEMA)
    }
    private fun digest(value: Map<String, Any?>, key: String): String = string(value, key).also {
        if (!it.matches(Regex("[a-f0-9]{64}"))) reject(Reason.SCHEMA)
    }
    private fun number(value: Map<String, Any?>, key: String): Long =
        (value[key] as? Long)?.also { if (it !in 0..2147483647L) reject(Reason.SCHEMA) } ?: reject(Reason.SCHEMA)
    private fun nullableNumber(value: Map<String, Any?>, key: String): Long? =
        if (value[key] == null) null else (value[key] as? Long) ?: reject(Reason.SCHEMA)

    companion object {
        const val MAX_ENVELOPE_BYTES = 16 * 1024
        const val MAX_DEPTH = 4
        const val MAX_FIELDS = 16
        const val MAX_STRING_CHARS = 256
        const val MAX_PARSE_NANOS = 100_000_000L
        private val OUTCOMES = setOf("completed", "failed", "cancelled", "timed-out", "not-started", "unknown")
    }
}
