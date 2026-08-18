package com.etroute.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ETrouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY name")
    suspend fun devices(): List<DeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: JobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelease(release: ReleaseEntity)

    @Insert
    suspend fun insertAudit(event: AuditEntity)

    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentAudit(limit: Int = 50): List<AuditEntity>
}
