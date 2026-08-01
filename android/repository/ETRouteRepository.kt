package com.example.etroute.repository

import com.example.etroute.room.AuditEventDao
import com.example.etroute.room.AuditEventEntity
import com.example.etroute.room.CapabilityGrantDao
import com.example.etroute.room.CapabilityGrantEntity
import com.example.etroute.room.SessionDao
import com.example.etroute.room.SessionEntity
import com.example.etroute.room.WorkspaceJournalDao
import com.example.etroute.room.WorkspaceJournalEntity

class ETRouteRepository(
    private val sessionDao: SessionDao,
    private val capabilityGrantDao: CapabilityGrantDao,
    private val workspaceJournalDao: WorkspaceJournalDao,
    private val auditEventDao: AuditEventDao
) {
    suspend fun createSession(session: SessionEntity) {
        sessionDao.insert(session)
    }

    suspend fun upsertSession(session: SessionEntity) {
        sessionDao.upsert(session)
    }

    suspend fun findSession(sessionId: String): SessionEntity? {
        return sessionDao.find(sessionId)
    }

    suspend fun listSessions(includeTerminal: Boolean = true): List<SessionEntity> {
        return if (includeTerminal) sessionDao.listAll() else sessionDao.listActive()
    }

    suspend fun findExpiredSessions(now: String): List<SessionEntity> {
        return sessionDao.findExpired(now)
    }

    suspend fun grantCapability(grant: CapabilityGrantEntity) {
        capabilityGrantDao.insert(grant)
    }

    suspend fun activeCapabilities(sessionId: String): List<CapabilityGrantEntity> {
        return capabilityGrantDao.listActiveForSession(sessionId)
    }

    suspend fun capabilityHistory(sessionId: String): List<CapabilityGrantEntity> {
        return capabilityGrantDao.listForSession(sessionId)
    }

    suspend fun revokeCapability(
        sessionId: String,
        capability: String,
        revokedAt: String,
        reason: String
    ): Int {
        return capabilityGrantDao.revoke(sessionId, capability, revokedAt, reason)
    }

    suspend fun revokeAllCapabilities(
        sessionId: String,
        revokedAt: String,
        reason: String
    ): Int {
        return capabilityGrantDao.revokeAll(sessionId, revokedAt, reason)
    }

    suspend fun appendWorkspaceJournal(entry: WorkspaceJournalEntity) {
        workspaceJournalDao.insert(entry)
    }

    suspend fun workspaceTransaction(transactionId: String): List<WorkspaceJournalEntity> {
        return workspaceJournalDao.listTransaction(transactionId)
    }

    suspend fun markWorkspaceCommitted(transactionId: String, committedAt: String): Int {
        return workspaceJournalDao.markCommitted(transactionId, committedAt)
    }

    suspend fun markWorkspaceRolledBack(transactionId: String, rolledBackAt: String): Int {
        return workspaceJournalDao.markRolledBack(transactionId, rolledBackAt)
    }

    suspend fun appendAuditEvent(event: AuditEventEntity) {
        auditEventDao.insert(event)
    }

    suspend fun auditForSession(sessionId: String): List<AuditEventEntity> {
        return auditEventDao.listForSession(sessionId)
    }

    suspend fun latestAuditEvents(limit: Int = 100): List<AuditEventEntity> {
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        return auditEventDao.latest(limit)
    }

    suspend fun closeSession(
        session: SessionEntity,
        revokedAt: String,
        reason: String,
        auditEvent: AuditEventEntity? = null
    ) {
        sessionDao.upsert(session)
        capabilityGrantDao.revokeAll(session.sessionId, revokedAt, reason)
        if (auditEvent != null) {
            auditEventDao.insert(auditEvent)
        }
    }
}
