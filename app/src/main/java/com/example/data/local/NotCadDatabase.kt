package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.AuditLogDao
import com.example.data.local.dao.FocusSessionDao
import com.example.data.local.dao.ProtectedAppDao
import com.example.data.local.entity.FocusSessionEntity
import com.example.data.local.entity.ProtectedAppEntity
import com.example.data.local.entity.SessionAuditLogEntity

@Database(
    entities = [
        ProtectedAppEntity::class,
        FocusSessionEntity::class,
        SessionAuditLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NotCadDatabase : RoomDatabase() {
    abstract fun protectedAppDao(): ProtectedAppDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: NotCadDatabase? = null

        fun getDatabase(context: Context): NotCadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotCadDatabase::class.java,
                    "notcad_vault.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
