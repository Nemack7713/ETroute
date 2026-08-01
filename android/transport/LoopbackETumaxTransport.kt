package com.example.etroute.transport

import android.os.Bundle
import com.example.etroute.ipc.ETRouteErrorCodes
import com.example.etroute.ipc.ETRouteRequest
import com.example.etroute.ipc.ETRouteResponse
import com.example.etroute.orchestration.ETumaxTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.time.OffsetDateTime

class LoopbackETumaxTransport(
    endpoint: String = "http://127.0.0.1:8765",
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 30_000
) : ETumaxTransport {
    private val baseUri: URI = URI(endpoint).also(::requireLoopback)

    override suspend fun transact(request: ETRouteRequest): ETRouteResponse = withContext(Dispatchers.IO) {
        post("/v1/transact", requestJson(request), request)
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        val request = ETRouteRequest(
            action = "stop_session",
            requestId = "stop-$sessionId",
            sessionId = sessionId,
            createdAt = OffsetDateTime.now().toString()
        )
        post("/v1/sessions/$sessionId/stop", requestJson(request), request)
        Unit
    }

    private fun post(path: String, body: String, request: ETRouteRequest): ETRouteResponse {
        val target = baseUri.resolve(path)
        requireLoopback(target)
        val connection = target.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseBody.isBlank()) {
                return failure(request, "ETumax returned HTTP $status with no response body")
            }
            parseResponse(responseBody, request)
        } catch (exception: IOException) {
            failure(request, exception.message ?: "ETumax loopback transport failed")
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
            .put("request_id", request.requestId)
            .put("session_id", request.sessionId)
            .put("created_at", request.createdAt)
            .put("payload", payload)
            .toString()
    }

    private fun parseResponse(raw: String, request: ETRouteRequest): ETRouteResponse {
        return try {
            val value = JSONObject(raw)
            val payloadJson = value.optJSONObject("payload") ?: JSONObject()
            val payload = Bundle()
            for (key in payloadJson.keys()) {
                payload.putString(key, payloadJson.opt(key)?.toString())
            }
            ETRouteResponse(
                version = value.optInt("version", 1),
                responder = value.optString("responder", "etumax"),
                requestId = value.optString("request_id", request.requestId),
                sessionId = value.optString("session_id", request.sessionId),
                ok = value.optBoolean("ok", false),
                createdAt = value.optString("created_at", OffsetDateTime.now().toString()),
                payload = payload,
                errorCode = value.optString("error_code").takeIf { it.isNotBlank() },
                errorMessage = value.optString("error_message").takeIf { it.isNotBlank() }
            ).also { it.validate() }
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

    private fun requireLoopback(uri: URI) {
        require(uri.scheme == "http") { "ETumax transport currently requires HTTP loopback" }
        require(uri.host != null) { "ETumax endpoint requires a host" }
        val address = InetAddress.getByName(uri.host)
        require(address.isLoopbackAddress) { "ETumax endpoint must resolve to loopback" }
    }
}
