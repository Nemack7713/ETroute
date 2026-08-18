package com.etroute.app

import android.app.Application
import com.etroute.app.device.DeviceManager
import com.etroute.app.device.NoOpDeviceManager
import com.etroute.app.runtime.DisconnectedRuntimeBridge
import com.etroute.app.runtime.RuntimeBridge
import com.etroute.app.security.SecureConfigStore
import com.etroute.app.storage.DocumentGateway
import com.etroute.app.storage.ETrouteDatabase
import com.etroute.app.update.FoundationUpdateManager
import com.etroute.app.update.UpdateManager

class ETrouteApplication : Application() {
    lateinit var database: ETrouteDatabase
        private set
    lateinit var secureConfig: SecureConfigStore
        private set
    lateinit var documents: DocumentGateway
        private set

    val deviceManager: DeviceManager = NoOpDeviceManager
    val runtimeBridge: RuntimeBridge = DisconnectedRuntimeBridge
    val updateManager: UpdateManager = FoundationUpdateManager(BuildConfig.VERSION_NAME)

    override fun onCreate() {
        super.onCreate()
        database = ETrouteDatabase.get(this)
        secureConfig = SecureConfigStore(this)
        documents = DocumentGateway(this)
    }
}
