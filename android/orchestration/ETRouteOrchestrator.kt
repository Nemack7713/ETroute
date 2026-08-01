package com.example.etroute.orchestration

import android.os.Bundle
import com.example.etroute.ipc.ETRouteActions
import com.example.etroute.ipc.ETRouteErrorCodes
import com.example.etroute.ipc.ETRouteRequest
import com.example.etroute.ipc.ETRouteResponse
import com.example.etroute.repository.ETRouteRepository
import com.example.etroute.room.AuditEventEntity
import com.example.etroute.room.SessionEntity
import com.example.etroute.service.ETRouteRequestDelegate
import java.time.OffsetDateTime
import java.util.UUID

class ETRouteOrchestrator(
    private val repository: ETRouteRepository,
    private val transport: ETumaxTransport
) : ETRouteRequestDelegate {

    override suspend fun transact(request: ETRouteRequest): ETRouteResponse {
        request.validate()
        return when (request.action) {
            ETRouteActions.PING -> success(request, Bundle().apply { putString("status", "ready") })
            ETRouteActions.GET_STATUS -> getStatus(request.sessionId)
            ETRouteActions.START_SESSION -> startSession(request)
            ETRouteActions.STOP_SESSION -> stopSession(request.sessionId)
            ETRouteActions.RUN_PROJECT -> runProject(request)
            else -> failure(request, ETRouteErrorCodes.INVALID_MESSAGE, "Unsupported action: ${request.action}")
        }
    }

    override suspend fun getStatus(sessionId: String): ETRouteResponse {
        val session = repository.findSession(sessionId)
            ?: return failure("status-$sessionId", sessionId, ETRouteErrorCodes.NOT_FOUND, "Unknown session: $sessionId")

        if (session.state in RECONCILABLE_STATES) {
            val remote = transport.getSession(sessionId)
            if (remote.ok) {
                val remoteStatus = remote.payload.getString("status")?.lowercase()
                val mapped = mapRemoteStatus(remoteStatus)
                if (mapped != null && mapped != session.state) {
                    val now = OffsetDateTime.now().toString()
                    val updated = session.copy(
                        state = mapped,
                        updatedAt = now,
                        stoppedAt = if (mapped in TERMINAL_STATES) now else session.stoppedAt,
                        failureReason = if (mapped == "failed") remote.payload.getString("error") else session.failureReason
                    )
                    repository.upsertSession(updated)
                    repository.appendAuditEvent(
                        audit(
                            sessionId,
                            "execution_reconciled",
                            session.taskId,
                            "ALLOW",
                            "ETumax status reconciled to $mapped"
                        )
                    )
                    return statusResponse(updated)
                }
            }
        }
        return statusResponse(session)
    }

    override suspend fun stopSession(sessionId: String): ETRouteResponse {
        val session = repository.findSession(sessionId)
            ?: return failure("stop-$sessionId", sessionId, ETRouteErrorCodes.NOT_FOUND, "Unknown session: $sessionId")
        if (session.state in TERMINAL_STATES) {
            return statusResponse(session, "stop-$sessionId")
        }

        val now = OffsetDateTime.now().toString()
        repository.upsertSession(session.copy(state = "cancelling", updatedAt = now))
        transport.stopSession(sessionId)
        repository.appendAuditEvent(
            audit(sessionId, "cancellation_requested", session.taskId, "ALLOW", "Cancellation forwarded to ETumax")
        )
        return success("stop-$sessionId", sessionId, Bundle().apply { putString("state", "cancelling") })
    }

    private suspend fun startSession(request: ETRouteRequest): ETRouteResponse {
        val taskId = request.payload.getString("taskId")?.trim().orEmpty()
        if (taskId.isEmpty()) {
            return failure(request, ETRouteErrorCodes.INVALID_MESSAGE, "START_SESSION requires payload.taskId")
        }
        if (repository.findSession(request.sessionId) != null) {
            return failure(request, ETRouteErrorCodes.CONFLICT, "Session already exists: ${request.sessionId}")
        }

        val now = OffsetDateTime.now().toString()
        val session = SessionEntity(
            sessionId = request.sessionId,
            taskId = taskId,
            state = "authorized",
            createdAt = now,
            updatedAt = now,
            expiresAt = request.payload.getString("expiresAt"),
            stoppedAt = null,
            failureReason = null,
            metadataJson = request.payload.getString("metadataJson") ?: "{}"
        )
        repository.createSession(session)
        repository.appendAuditEvent(
            audit(request.sessionId, "session_started", taskId, "ALLOW", "Session authorized by ETroute")
        )
        return success(request, Bundle().apply {
            putString("state", "authorized")
            putString("taskId", taskId)
        })
    }

    private suspend fun runProject(request: ETRouteRequest): ETRouteResponse {
        val session = repository.findSession(request.sessionId)
            ?: return failure(request, ETRouteErrorCodes.NOT_FOUND, "Unknown session: ${request.sessionId}")
        if (session.state !in setOf("authorized", "created")) {
            return failure(request, ETRouteErrorCodes.CONFLICT, "Session cannot dispatch from state: ${session.state}")
        }

        val dispatchingAt = OffsetDateTime.now().toString()
        repository.upsertSession(session.copy(state = "dispatching", updatedAt = dispatchingAt))
        repository.appendAuditEvent(
            audit(request.sessionId, "execution_dispatching", session.taskId, "ALLOW", "Approved request forwarding to ETumax")
        )

        return try {
            val response = transport.transact(request).also { it.validate() }
            if (!response.ok) {
                repository.upsertSession(
                    session.copy(
                        state = "failed",
                        updatedAt = OffsetDateTime.now().toString(),
                        failureReason = response.errorMessage
                    )
                )
                return response
            }

            val remoteStatus = response.payload.getString("status")?.lowercase()
            val nextState = when (remoteStatus) {
                "accepted", "pending" -> "dispatched"
                "running" -> "running"
                else -> "dispatched"
            }
            repository.upsertSession(session.copy(state = nextState, updatedAt = OffsetDateTime.now().toString()))
            repository.appendAuditEvent(
                audit(request.sessionId, "execution_accepted", session.taskId, "ALLOW", "ETumax accepted asynchronous execution")
            )
            response
        } catch (exception: SecurityException) {
            repository.upsertSession(session.copy(state = "failed", updatedAt = OffsetDateTime.now().toString(), failureReason = exception.message))
            failure(request, ETRouteErrorCodes.POLICY_DENIED, exception.message ?: "ETumax transport denied the request")
        } catch (exception: Exception) {
            repository.upsertSession(session.copy(state = "failed", updatedAt = OffsetDateTime.now().toString(), failureReason = exception.message))
            failure(request, ETRouteErrorCodes.RUNTIME_FAILURE, exception.message ?: "ETumax transport failed")
        }
    }

    private fun mapRemoteStatus(status: String?): String? = when (status) {
        "accepted", "pending" -> "dispatched"
        "running" -> "running"
        "completed" -> "completed"
        "failed" -> "failed"
        "cancelling", "stopping" -> "cancelling"
        "cancelled", "stopped" -> "cancelled"
        "timed_out", "timeout" -> "timed_out"
        else -> null
    }

    private fun statusResponse(session: SessionEntity, requestId: String = "status-${session.sessionId}"): ETRouteResponse {
        return success(requestId, session.sessionId, Bundle().apply {
            putString("taskId", session.taskId)
            putString("state", session.state)
            putString("updatedAt", session.updatedAt)
            putString("expiresAt", session.expiresAt)
            putString("failureReason", session.failureReason)
        })
    }

    private fun audit(sessionId: String?, eventType: String, subject: String, decision: String?, reason: String?): AuditEventEntity {
        return AuditEventEntity(
            eventId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            eventType = eventType,
            subject = subject,
            decision = decision,
            reason = reason,
            payloadJson = "{}",
            createdAt = OffsetDateTime.now().toString()
        )
    }

    private fun success(request: ETRouteRequest, payload: Bundle): ETRouteResponse = success(request.requestId, request.sessionId, payload)

    private fun success(requestId: String, sessionId: String, payload: Bundle): ETRouteResponse = ETRouteResponse(
        requestId = requestId,
        sessionId = sessionId,
        ok = true,
        createdAt = OffsetDateTime.now().toString(),
        payload = payload
    )

    private fun failure(request: ETRouteRequest, errorCode: String, message: String): ETRouteResponse =
        failure(request.requestId, request.sessionId, errorCode, message)

    private fun failure(requestId: String, sessionId: String, errorCode: String, message: String): ETRouteResponse = ETRouteResponse(
        requestId = requestId,
        sessionId = sessionId,
        ok = false,
        createdAt = OffsetDateTime.now().toString(),
        errorCode = errorCode,
        errorMessage = message
    )

    companion object {
        private val TERMINAL_STATES = setOf("completed", "failed", "cancelled", "timed_out", "expired")
        private val RECONCILABLE_STATES = setOf("dispatched", "running", "cancelling")
    }
}

interface ETumaxTransport {
    suspend fun transact(request: ETRouteRequest): ETRouteResponse
    suspend fun getSession(sessionId: String): ETRouteResponse
    suspend fun stopSession(sessionId: String)
}
