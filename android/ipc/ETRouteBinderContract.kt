package com.example.etroute.ipc

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val ETROUTE_PROTOCOL_VERSION: Int = 1

object ETRouteActions {
    const val PING = "ping"
    const val GET_STATUS = "get_status"
    const val START_SESSION = "start_session"
    const val STOP_SESSION = "stop_session"
    const val RUN_PROJECT = "run_project"
}

object ETRouteErrorCodes {
    const val INVALID_MESSAGE = "invalid_message"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val UNAUTHORIZED = "unauthorized"
    const val POLICY_DENIED = "policy_denied"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val TIMEOUT = "timeout"
    const val RUNTIME_FAILURE = "runtime_failure"
}

@Parcelize
data class ETRouteRequest(
    val version: Int = ETROUTE_PROTOCOL_VERSION,
    val caller: String = "etroute",
    val action: String,
    val requestId: String,
    val sessionId: String,
    val createdAt: String,
    val payload: Bundle = Bundle()
) : Parcelable {
    fun validate() {
        require(version == ETROUTE_PROTOCOL_VERSION) {
            "Unsupported protocol version: $version"
        }
        require(caller == "etroute") {
            "Request caller must be etroute"
        }
        require(action in setOf(
            ETRouteActions.PING,
            ETRouteActions.GET_STATUS,
            ETRouteActions.START_SESSION,
            ETRouteActions.STOP_SESSION,
            ETRouteActions.RUN_PROJECT
        )) {
            "Unsupported action: $action"
        }
        require(requestId.matches(ID_PATTERN)) {
            "Invalid requestId"
        }
        require(sessionId.matches(ID_PATTERN)) {
            "Invalid sessionId"
        }
    }
}

@Parcelize
data class ETRouteResponse(
    val version: Int = ETROUTE_PROTOCOL_VERSION,
    val responder: String = "etumax",
    val requestId: String,
    val sessionId: String,
    val ok: Boolean,
    val createdAt: String,
    val payload: Bundle = Bundle(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) : Parcelable {
    fun validate() {
        require(version == ETROUTE_PROTOCOL_VERSION) {
            "Unsupported protocol version: $version"
        }
        require(responder == "etumax") {
            "Response responder must be etumax"
        }
        require(requestId.matches(ID_PATTERN)) {
            "Invalid requestId"
        }
        require(sessionId.matches(ID_PATTERN)) {
            "Invalid sessionId"
        }
        if (ok) {
            require(errorCode == null && errorMessage == null) {
                "Successful response cannot include an error"
            }
        } else {
            require(errorCode in SUPPORTED_ERROR_CODES) {
                "Unsupported errorCode: $errorCode"
            }
            require(!errorMessage.isNullOrBlank()) {
                "Failed response requires errorMessage"
            }
        }
    }
}

interface ETRouteBinderApi {
    suspend fun transact(request: ETRouteRequest): ETRouteResponse
    suspend fun getStatus(sessionId: String): ETRouteResponse
    suspend fun stopSession(sessionId: String): ETRouteResponse
}

private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

private val SUPPORTED_ERROR_CODES = setOf(
    ETRouteErrorCodes.INVALID_MESSAGE,
    ETRouteErrorCodes.UNSUPPORTED_VERSION,
    ETRouteErrorCodes.UNAUTHORIZED,
    ETRouteErrorCodes.POLICY_DENIED,
    ETRouteErrorCodes.NOT_FOUND,
    ETRouteErrorCodes.CONFLICT,
    ETRouteErrorCodes.TIMEOUT,
    ETRouteErrorCodes.RUNTIME_FAILURE
)
