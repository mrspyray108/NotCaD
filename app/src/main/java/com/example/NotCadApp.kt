package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.local.NotCadDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.FocusRepositoryImpl
import com.example.domain.repository.FocusRepository

class NotCadApp : Application() {

    lateinit var database: NotCadDatabase
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var focusRepository: FocusRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = NotCadDatabase.getDatabase(this)
        preferencesRepository = UserPreferencesRepository(this)
        focusRepository = FocusRepositoryImpl(this, database, preferencesRepository)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val sessionChannel = NotificationChannel(
                CHANNEL_ACTIVE_SESSION,
                "Active Focus Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time countdown timer during active application sessions"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_EXPIRATION_ALERT,
                "Session Expiration Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alert when usage time expires to return to productive work"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager?.createNotificationChannel(sessionChannel)
            notificationManager?.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_ACTIVE_SESSION = "notcad_active_session_channel"
        const val CHANNEL_EXPIRATION_ALERT = "notcad_expiration_alert_channel"

        lateinit var instance: NotCadApp
            private set
    }
}
