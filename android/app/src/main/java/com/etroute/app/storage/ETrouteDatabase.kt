package com.etroute.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeviceEntity::class, JobEntity::class, ReleaseEntity::class, AuditEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ETrouteDatabase : RoomDatabase() {
    abstract fun dao(): ETrouteDao

    companion object {
        @Volatile private var instance: ETrouteDatabase? = null

        fun get(context: Context): ETrouteDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ETrouteDatabase::class.java,
                    "etroute.db"
                ).build().also { instance = it }
            }
    }
}
