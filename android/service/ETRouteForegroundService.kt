package com.example.etroute.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.example.etroute.ipc.ETRouteBinderApi
import com.example.etroute.ipc.ETRouteErrorCodes
import com.example.etroute.ipc.ETRouteRequest
import com.example.etroute.ipc.ETRouteResponse
import com.example.etroute.orchestration.ETRouteOrchestrator
import com.example.etroute.orchestration.ETumaxTransport
import com.example.etroute.repository.ETRouteRepository
import com.example.etroute.room.ETRouteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.OffsetDateTime

class ETRouteForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localBinder = LocalBinder()

    private lateinit var database: ETRouteDatabase
    private lateinit var repository: ETRouteRepository
    private lateinit var orchestrator: ETRouteOrchestrator

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            applicationContext,
            ETRouteDatabase::class.java,
            DATABASE_NAME
        ).build()

        repository = ETRouteRepository(
            sessionDao = database.sessionDao(),
            capabilityGrantDao = database.capabilityGrantDao(),
            workspaceJournalDao = database.workspaceJournalDao(),
            auditEventDao = database.auditEventDao()
        )

        orchestrator = ETRouteOrchestrator(
            repository = repository,
            transport = UnavailableETumaxTransport()
        )

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("ETroute is ready"))
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        serviceScope.cancel()
        if (::database.isInitialized) {
            database.close()
        }
        super.onDestroy()
    }

    inner class LocalBinder : Binder(), ETRouteBinderApi {
        override suspend fun transact(request: ETRouteRequest): ETRouteResponse {
            return try {
                request.validate()
                orchestrator.transact(request)
            } catch (exception: IllegalArgumentException) {
                failureResponse(
                    request = request,
                    errorCode = ETRouteErrorCodes.INVALID_MESSAGE,
                    message = exception.message ?: "Invalid request"
                )
            } catch (exception: SecurityException) {
                failureResponse(
                    request = request,
                    errorCode = ETRouteErrorCodes.POLICY_DENIED,
                    message = exception.message ?: "Request denied"
                )
            }
        }

        override suspend fun getStatus(sessionId: String): ETRouteResponse {
            return orchestrator.getStatus(sessionId)
        }

        override suspend fun stopSession(sessionId: String): ETRouteResponse {
            return orchestrator.stopSession(sessionId)
        }
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ETroute")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ETroute orchestration",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows ETroute orchestration and session status"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun failureResponse(
        request: ETRouteRequest,
        errorCode: String,
        message: String
    ): ETRouteResponse {
        return ETRouteResponse(
            requestId = request.requestId,
            sessionId = request.sessionId,
            ok = false,
            createdAt = OffsetDateTime.now().toString(),
            errorCode = errorCode,
            errorMessage = message
        )
    }

    companion object {
        const val CHANNEL_ID = "etroute_orchestration"
        const val NOTIFICATION_ID = 4101
        const val DATABASE_NAME = "etroute.db"
    }
}

interface ETRouteRequestDelegate {
    suspend fun transact(request: ETRouteRequest): ETRouteResponse
    suspend fun getStatus(sessionId: String): ETRouteResponse
    suspend fun stopSession(sessionId: String): ETRouteResponse
}

private class UnavailableETumaxTransport : ETumaxTransport {
    override suspend fun transact(request: ETRouteRequest): ETRouteResponse {
        return ETRouteResponse(
            requestId = request.requestId,
            sessionId = request.sessionId,
            ok = false,
            createdAt = OffsetDateTime.now().toString(),
            errorCode = ETRouteErrorCodes.RUNTIME_FAILURE,
            errorMessage = "ETumax transport is not configured"
        )
    }

    override suspend fun stopSession(sessionId: String) {
        // ETroute owns session state; no ETumax transport is configured yet.
    }
}
