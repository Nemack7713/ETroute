package com.example.etroute.transport

import android.os.Bundle
import com.example.etroute.ipc.ETROUTE_PROTOCOL_VERSION
import com.example.etroute.ipc.ETRouteErrorCodes
import com.example.etroute.ipc.ETRouteRequest
import com.example.etroute.ipc.ETRouteResponse
import com.example.etroute.orchestration.ETumaxTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.time.OffsetDateTime

interface ETumaxStarter {
    suspend fun ensureStarted(): Boolean
}

class ExistingProcessETumaxStarter : ETumaxStarter {
    override suspend fun ensureStarted(): Boolean = true
}

class ETumaxReadinessProbe(
    endpoint: String = "http://127.0.0.1:8765",
    private val connectTimeoutMs: Int = 1_000,
    private val readTimeoutMs: Int = 1_000
) {
    private val baseUri: URI = URI(endpoint).also(::requireLoopback)

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        val target = baseUri.resolve("/v1/health")
        requireLoopback(target)
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            if (connection.responseCode !in 200..299) return@withContext false
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val value = JSONObject(body)
            value.optString("service") == "etumax" &&
                value.optInt("protocol_version", -1) == ETROUTE_PROTOCOL_VERSION &&
                value.optBoolean("ready", false)
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

class LoopbackETumaxTransport(
    endpoint: String = "http://127.0.0.1:8765",
    private val starter: ETumaxStarter = ExistingProcessETumaxStarter(),
    private val readinessProbe: ETumaxReadinessProbe = ETumaxReadinessProbe(endpoint),
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 30_000,
    private val startupAttempts: Int = 6,
    private val startupRetryDelayMs: Long = 500
) : ETumaxTransport {
    private val baseUri: URI = URI(endpoint).also(::requireLoopback)

    init {
        require(startupAttempts > 0) { "startupAttempts must be positive" }
        require(startupRetryDelayMs >= 0) { "startupRetryDelayMs cannot be negative" }
    }

    override suspend fun transact(request: ETRouteRequest): ETRouteResponse {
        request.validate()
        if (!ensureReady()) {
            return failure(request, "ETumax loopback service is not ready")
        }
        return withContext(Dispatchers.IO) {
            postOnce("/v1/transact", requestJson(request), request)
        }
    }

    override suspend fun stopSession(sessionId: String) {
        require(sessionId.matches(ID_PATTERN)) { "Invalid sessionId" }
        if (!readinessProbe.isReady()) return
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
            val request = ETRouteRequest(
                action = "stop_session",
                requestId = "stop-$sessionId",
                sessionId = sessionId,
                createdAt = OffsetDateTime.now().toString()
            )
            postOnce("/v1/sessions/$encoded/stop", requestJson(request), request)
            Unit
        }
    }

    private suspend fun ensureReady(): Boolean {
        if (readinessProbe.isReady()) return true
        if (!starter.ensureStarted()) return false
        repeat(startupAttempts) { attempt ->
            if (readinessProbe.isReady()) return true
            if (attempt + 1 < startupAttempts) {
                delay(startupRetryDelayMs * (attempt + 1))
            }
        }
        return false
    }

    private fun postOnce(path: String, body: String, request: ETRouteRequest): ETRouteResponse {
        val target = baseUri.resolve(path)
        requireLoopback(target)
        val connection = target.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                return failure(request, "ETumax returned HTTP $status${if (responseBody.isBlank()) "" else ": $responseBody"}")
            }
            if (responseBody.isBlank()) return failure(request, "ETumax returned an empty response")
            parseResponse(responseBody, request)
        } catch (exception: IOException) {
            failure(request, "ETumax loopback I/O failure: ${exception.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(request: ETRouteRequest): String {
        val payload = JSONObject()
        for (key in request.payload.keySet()) {
            payload.put(key, request.payload.get(key))
        }
        return JSONObject()
            .put("version", request.version)
            .put("caller", request.caller)
            .put("action", request.action)
            .put("requestId", request.requestId)
            .put("sessionId", request.sessionId)
            .put("createdAt", request.createdAt)
            .put("payload", payload)
            .toString()
    }

    private fun parseResponse(raw: String, request: ETRouteRequest): ETRouteResponse {
        return try {
            val value = JSONObject(raw)
            val payloadJson = value.optJSONObject("payload") ?: JSONObject()
            val payload = Bundle()
            for (key in payloadJson.keys()) {
                when (val item = payloadJson.get(key)) {
                    JSONObject.NULL -> payload.putString(key, null)
                    is String -> payload.putString(key, item)
                    is Boolean -> payload.putBoolean(key, item)
                    is Int -> payload.putInt(key, item)
                    is Long -> payload.putLong(key, item)
                    is Double -> payload.putDouble(key, item)
                    else -> payload.putString(key, item.toString())
                }
            }
            ETRouteResponse(
                version = value.optInt("version", ETROUTE_PROTOCOL_VERSION),
                responder = value.optString("responder", ""),
                requestId = value.getString("requestId"),
                sessionId = value.getString("sessionId"),
                ok = value.getBoolean("ok"),
                createdAt = value.getString("createdAt"),
                payload = payload,
                errorCode = value.optString("errorCode").takeIf { it.isNotBlank() && it != "null" },
                errorMessage = value.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
            ).also {
                it.validate()
                require(it.requestId == request.requestId) { "ETumax requestId mismatch" }
                require(it.sessionId == request.sessionId) { "ETumax sessionId mismatch" }
            }
        } catch (exception: Exception) {
            failure(request, "Invalid ETumax response: ${exception.message}")
        }
    }

    private fun failure(request: ETRouteRequest, message: String): ETRouteResponse {
        return ETRouteResponse(
            requestId = request.requestId,
            sessionId = request.sessionId,
            ok = false,
            createdAt = OffsetDateTime.now().toString(),
            errorCode = ETRouteErrorCodes.RUNTIME_FAILURE,
            errorMessage = message
        )
    }
}

private fun requireLoopback(uri: URI) {
    require(uri.scheme == "http") { "ETumax transport currently requires HTTP loopback" }
    require(uri.host != null) { "ETumax endpoint requires a host" }
    require(uri.port in 1..65535) { "ETumax loopback port must be explicit" }
    val address = InetAddress.getByName(uri.host)
    require(address.isLoopbackAddress) { "ETumax endpoint must resolve to loopback" }
}

private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
