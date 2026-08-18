package com.etroute.app.device

/**
 * Android-facing device boundary. Wireless ADB is intentionally not implemented
 * in v0.1; later milestones can replace NoOpDeviceManager without changing UI or
 * ETumax bridge contracts.
 */
interface DeviceManager {
    suspend fun status(): DeviceStatus
}

data class DeviceStatus(
    val connected: Boolean,
    val displayName: String? = null,
    val transport: String? = null
)

object NoOpDeviceManager : DeviceManager {
    override suspend fun status() = DeviceStatus(connected = false)
}
