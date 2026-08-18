package com.etroute.app.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String? = null,
    val pairingPort: Int? = null,
    val connectionPort: Int? = null,
    val fingerprint: String? = null,
    val trusted: Boolean = false,
    val transportType: String = "NONE",
    val lastConnectedAt: Long? = null
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val type: String,
    val targetDeviceId: String? = null,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val exitCode: Int? = null
)

@Entity(tableName = "releases")
data class ReleaseEntity(
    @PrimaryKey val versionCode: Long,
    val versionName: String,
    val releaseTag: String,
    val apkSha256: String,
    val verified: Boolean = false,
    val installedAt: Long? = null
)

@Entity(tableName = "audit_events")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val operation: String,
    val target: String? = null,
    val result: String,
    val details: String? = null
)
