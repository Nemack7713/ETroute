package com.etroute.app.update

/**
 * v0.1 update contract. Network release discovery, signature verification and
 * PackageInstaller integration are intentionally deferred to the next milestone.
 */
interface UpdateManager {
    suspend fun status(): UpdateStatus
}

data class UpdateStatus(
    val state: State,
    val currentVersion: String,
    val availableVersion: String? = null
) {
    enum class State { NOT_CHECKED, UP_TO_DATE, AVAILABLE, ERROR }
}

class FoundationUpdateManager(
    private val currentVersion: String
) : UpdateManager {
    override suspend fun status() = UpdateStatus(
        state = UpdateStatus.State.NOT_CHECKED,
        currentVersion = currentVersion
    )
}
