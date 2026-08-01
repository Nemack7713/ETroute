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
            ETRouteActions.PING -> success(request, Bundle().apply {
                putString("status", "ready")
            })

            ETRouteActions.GET_STATUS -> getStatus(request.sessionId)
            ETRouteActions.START_SESSION -> startSession(request)
            ETRouteActions.STOP_SESSION -> stopSession(request.sessionId)
            ETRouteActions.RUN_PROJECT -> runProject(request)
            else -> failure(
                request,
                ETRouteErrorCodes.INVALID_MESSAGE,
                "Unsupported action: ${request.action}"
            )
        }
    }

    override suspend fun getStatus(sessionId: String): ETRouteResponse {
        val session = repository.findSession(sessionId)
            ?: return failure(
                requestId = "status-$sessionId",
                sessionId = sessionId,
                errorCode = ETRouteErrorCodes.NOT_FOUND,
                message = "Unknown session: $sessionId"
            )
        return success(
            requestId = "status-$sessionId",
            sessionId = sessionId,
            payload = Bundle().apply {
                putString("taskId", session.taskId)
                putString("state", session.state)
                putString("updatedAt", session.updatedAt)
                putString("expiresAt", session.expiresAt)
            }
        )
    }

    override suspend fun stopSession(sessionId: String): ETRouteResponse {
        val session = repository.findSession(sessionId)
            ?: return failure(
                requestId = "stop-$sessionId",
                sessionId = sessionId,
                errorCode = ETRouteErrorCodes.NOT_FOUND,
                message = "Unknown session: $sessionId"
            )
        if (session.state in TERMINAL_STATES) {
            return success(
                requestId = "stop-$sessionId",
                sessionId = sessionId,
                payload = Bundle().apply { putString("state", session.state) }
            )
        }

        val now = OffsetDateTime.now().toString()
        val stopped = session.copy(
            state = "stopped",
            updatedAt = now,
            stoppedAt = now
        )
        repository.closeSession(
            session = stopped,
            revokedAt = now,
            reason = "session stopped",
            auditEvent = audit(
                sessionId = sessionId,
                eventType = "session_stopped",
                subject = session.taskId,
                decision = "ALLOW",
                reason = "Session stopped by ETroute"
            )
        )
        transport.stopSession(sessionId)
        return success(
            requestId = "stop-$sessionId",
            sessionId = sessionId,
            payload = Bundle().apply { putString("state", "stopped") }
        )
    }

    private suspend fun startSession(request: ETRouteRequest): ETRouteResponse {
        val taskId = request.payload.getString("taskId")?.trim().orEmpty()
        if (taskId.isEmpty()) {
            return failure(
                request,
                ETRouteErrorCodes.INVALID_MESSAGE,
                "START_SESSION requires payload.taskId"
            )
        }
        if (repository.findSession(request.sessionId) != null) {
            return failure(
                request,
                ETRouteErrorCodes.CONFLICT,
                "Session already exists: ${request.sessionId}"
            )
        }

        val now = OffsetDateTime.now().toString()
        val session = SessionEntity(
            sessionId = request.sessionId,
            taskId = taskId,
            state = "active",
            createdAt = now,
            updatedAt = now,
            expiresAt = request.payload.getString("expiresAt"),
            stoppedAt = null,
            failureReason = null,
            metadataJson = request.payload.getString("metadataJson") ?: "{}"
        )
        repository.createSession(session)
        repository.appendAuditEvent(
            audit(
                sessionId = request.sessionId,
                eventType = "session_started",
                subject = taskId,
                decision = "ALLOW",
                reason = "Session accepted by ETroute"
            )
        )
        return success(
            request,
            Bundle().apply {
                putString("state", "active")
                putString("taskId", taskId)
            }
        )
    }

    private suspend fun runProject(request: ETRouteRequest): ETRouteResponse {
        val session = repository.findSession(request.sessionId)
            ?: return failure(
                request,
                ETRouteErrorCodes.NOT_FOUND,
                "Unknown session: ${request.sessionId}"
            )
        if (session.state != "active") {
            return failure(
                request,
                ETRouteErrorCodes.CONFLICT,
                "Session is not active: ${session.state}"
            )
        }

        repository.appendAuditEvent(
            audit(
                sessionId = request.sessionId,
                eventType = "execution_forwarded",
                subject = session.taskId,
                decision = "ALLOW",
                reason = "Approved request forwarded to ETumax transport"
            )
        )

        return try {
            transport.transact(request).also { response -> response.validate() }
        } catch (exception: SecurityException) {
            failure(
                request,
                ETRouteErrorCodes.POLICY_DENIED,
                exception.message ?: "ETumax transport denied the request"
            )
        } catch (exception: Exception) {
            failure(
                request,
                ETRouteErrorCodes.RUNTIME_FAILURE,
                exception.message ?: "ETumax transport failed"
            )
        }
    }

    private fun audit(
        sessionId: String?,
        eventType: String,
        subject: String,
        decision: String?,
        reason: String?
    ): AuditEventEntity {
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

    private fun success(request: ETRouteRequest, payload: Bundle): ETRouteResponse {
        return success(request.requestId, request.sessionId, payload)
    }

    private fun success(
        requestId: String,
        sessionId: String,
        payload: Bundle
    ): ETRouteResponse {
        return ETRouteResponse(
            requestId = requestId,
            sessionId = sessionId,
            ok = true,
            createdAt = OffsetDateTime.now().toString(),
            payload = payload
        )
    }

    private fun failure(
        request: ETRouteRequest,
        errorCode: String,
        message: String
    ): ETRouteResponse {
        return failure(request.requestId, request.sessionId, errorCode, message)
    }

    private fun failure(
        requestId: String,
        sessionId: String,
        errorCode: String,
        message: String
    ): ETRouteResponse {
        return ETRouteResponse(
            requestId = requestId,
            sessionId = sessionId,
            ok = false,
            createdAt = OffsetDateTime.now().toString(),
            errorCode = errorCode,
            errorMessage = message
        )
    }

    companion object {
        private val TERMINAL_STATES = setOf("stopped", "expired", "failed")
    }
}

interface ETumaxTransport {
    suspend fun transact(request: ETRouteRequest): ETRouteResponse
    suspend fun stopSession(sessionId: String)
}
