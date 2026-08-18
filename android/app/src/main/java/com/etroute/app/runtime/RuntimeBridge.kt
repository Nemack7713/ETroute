package com.etroute.app.runtime

/**
 * Stable boundary for a future ETumax runtime connection.
 * ETroute v0.1 intentionally ships without a Python implementation.
 */
interface RuntimeBridge {
    suspend fun capabilities(): RuntimeCapabilities
    suspend fun submit(request: RuntimeRequest): RuntimeResult
}

data class RuntimeCapabilities(
    val connected: Boolean,
    val runtimeName: String? = null,
    val operations: Set<String> = emptySet()
)

data class RuntimeRequest(
    val requestId: String,
    val operation: String,
    val payload: String? = null
)

data class RuntimeResult(
    val requestId: String,
    val success: Boolean,
    val payload: String? = null,
    val error: String? = null
)

object DisconnectedRuntimeBridge : RuntimeBridge {
    override suspend fun capabilities() = RuntimeCapabilities(connected = false)

    override suspend fun submit(request: RuntimeRequest) = RuntimeResult(
        requestId = request.requestId,
        success = false,
        error = "ETumax runtime is not connected"
    )
}
