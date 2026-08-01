package com.example.etroute.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = "sessions",
    indices = [Index("state"), Index("expiresAt")]
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val taskId: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String?,
    val stoppedAt: String?,
    val failureReason: String?,
    val metadataJson: String
)

@Entity(
    tableName = "capability_grants",
    primaryKeys = ["sessionId", "capability", "grantedAt"],
    indices = [Index("sessionId"), Index("revokedAt")]
)
data class CapabilityGrantEntity(
    val sessionId: String,
    val capability: String,
    val grantedBy: String,
    val grantedAt: String,
    val revokedAt: String?,
    val reason: String?
)

@Entity(
    tableName = "workspace_journal",
    indices = [Index("sessionId"), Index("transactionId"), Index("targetPath")]
)
data class WorkspaceJournalEntity(
    @PrimaryKey val journalId: String,
    val sessionId: String,
    val transactionId: String,
    val operation: String,
    val targetPath: String,
    val backupPath: String?,
    val sha256Before: String?,
    val sha256After: String?,
    val createdAt: String,
    val committedAt: String?,
    val rolledBackAt: String?
)

@Entity(
    tableName = "audit_events",
    indices = [Index("sessionId"), Index("eventType"), Index("createdAt")]
)
data class AuditEventEntity(
    @PrimaryKey val eventId: String,
    val sessionId: String?,
    val eventType: String,
    val subject: String,
    val decision: String?,
    val reason: String?,
    val payloadJson: String,
    val createdAt: String
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun find(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun listAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE state NOT IN ('stopped', 'expired', 'failed') ORDER BY createdAt DESC")
    suspend fun listActive(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND state IN ('created', 'active')")
    suspend fun findExpired(now: String): List<SessionEntity>
}

@Dao
interface CapabilityGrantDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(grant: CapabilityGrantEntity)

    @Query("SELECT * FROM capability_grants WHERE sessionId = :sessionId ORDER BY grantedAt ASC")
    suspend fun listForSession(sessionId: String): List<CapabilityGrantEntity>

    @Query("SELECT * FROM capability_grants WHERE sessionId = :sessionId AND revokedAt IS NULL")
    suspend fun listActiveForSession(sessionId: String): List<CapabilityGrantEntity>

    @Query("UPDATE capability_grants SET revokedAt = :revokedAt, reason = :reason WHERE sessionId = :sessionId AND revokedAt IS NULL")
    suspend fun revokeAll(sessionId: String, revokedAt: String, reason: String): Int

    @Query("UPDATE capability_grants SET revokedAt = :revokedAt, reason = :reason WHERE sessionId = :sessionId AND capability = :capability AND revokedAt IS NULL")
    suspend fun revoke(sessionId: String, capability: String, revokedAt: String, reason: String): Int
}

@Dao
interface WorkspaceJournalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: WorkspaceJournalEntity)

    @Query("SELECT * FROM workspace_journal WHERE transactionId = :transactionId ORDER BY createdAt ASC")
    suspend fun listTransaction(transactionId: String): List<WorkspaceJournalEntity>

    @Query("UPDATE workspace_journal SET committedAt = :committedAt WHERE transactionId = :transactionId AND committedAt IS NULL")
    suspend fun markCommitted(transactionId: String, committedAt: String): Int

    @Query("UPDATE workspace_journal SET rolledBackAt = :rolledBackAt WHERE transactionId = :transactionId AND rolledBackAt IS NULL")
    suspend fun markRolledBack(transactionId: String, rolledBackAt: String): Int
}

@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun listForSession(sessionId: String): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AuditEventEntity>
}

@Dao
interface ETRouteTransactionDao {
    @Transaction
    suspend fun closeSession(
        sessionDao: SessionDao,
        capabilityDao: CapabilityGrantDao,
        session: SessionEntity,
        revokedAt: String,
        reason: String
    ) {
        sessionDao.upsert(session)
        capabilityDao.revokeAll(session.sessionId, revokedAt, reason)
    }
}

@Database(
    entities = [
        SessionEntity::class,
        CapabilityGrantEntity::class,
        WorkspaceJournalEntity::class,
        AuditEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ETRouteDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun capabilityGrantDao(): CapabilityGrantDao
    abstract fun workspaceJournalDao(): WorkspaceJournalDao
    abstract fun auditEventDao(): AuditEventDao
}
