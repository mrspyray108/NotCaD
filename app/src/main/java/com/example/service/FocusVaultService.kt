package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.NotCadApp
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FocusVaultService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    private var sessionId: Long = 0L
    private var packageName: String = ""
    private var appName: String = ""
    private var expirationTimeMillis: Long = 0L
    private var totalDurationMinutes: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Protected App"
                expirationTimeMillis = intent.getLongExtra(EXTRA_EXPIRATION_TIME, 0L)
                totalDurationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 0)

                startForegroundServiceWithNotification()
                startCountdown()
            }
            ACTION_STOP_SESSION -> {
                stopSession(early = true)
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val initialNotification = buildNotification("Starting session for $appName...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun startCountdown() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            _isSessionActive.value = true
            _currentAppName.value = appName
            _currentPackage.value = packageName

            while (isActive) {
                val now = System.currentTimeMillis()
                val remainingMillis = (expirationTimeMillis - now).coerceAtLeast(0L)
                _remainingMillisState.value = remainingMillis

                val totalSeconds = (remainingMillis / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val formattedTime = "%02d:%02d".format(minutes, seconds)

                updateNotification("$appName Session: $formattedTime remaining")

                if (remainingMillis <= 0L) {
                    onSessionExpired()
                    break
                }

                delay(1000L)
            }
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_ACTIVE_SESSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotCadApp.CHANNEL_ACTIVE_SESSION)
            .setContentTitle("FocusVault Guard Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun onSessionExpired() {
        serviceScope.launch {
            val repository = (application as NotCadApp).focusRepository
            repository.completeActiveSession(sessionId, earlyTerminated = false)

            _isSessionActive.value = false
            _remainingMillisState.value = 0L

            // Trigger expiration notification & open full-screen alert
            showExpirationAlert()

            // Broadcast to accessibility service for action
            val expiredIntent = Intent(ACTION_SESSION_EXPIRED).apply {
                setPackage(this@FocusVaultService.packageName)
            }
            sendBroadcast(expiredIntent)

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showExpirationAlert() {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_SESSION_FINISHED)
            putExtra(MainActivity.EXTRA_EXPIRED_APP_NAME, appName)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, NotCadApp.CHANNEL_EXPIRATION_ALERT)
            .setContentTitle("Session Over: $appName")
            .setContentText("Your usage timer expired. Now put your phone away and get back to work.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIFICATION_ALERT_ID, alertNotification)

        // Attempt launching activity
        try {
            startActivity(fullScreenIntent)
        } catch (_: Exception) {
            // Background start restriction fallback handled via notification
        }
    }

    private fun stopSession(early: Boolean) {
        timerJob?.cancel()
        serviceScope.launch {
            val repository = (application as NotCadApp).focusRepository
            repository.completeActiveSession(sessionId, earlyTerminated = early)
            _isSessionActive.value = false
            _remainingMillisState.value = 0L
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        _isSessionActive.value = false
    }

    companion object {
        const val ACTION_START_SESSION = "com.example.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.example.action.STOP_SESSION"
        const val ACTION_SESSION_EXPIRED = "com.example.action.SESSION_EXPIRED"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_EXPIRATION_TIME = "extra_expiration_time"
        const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"

        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_ALERT_ID = 2002

        private val _remainingMillisState = MutableStateFlow(0L)
        val remainingMillisState = _remainingMillisState.asStateFlow()

        private val _isSessionActive = MutableStateFlow(false)
        val isSessionActive = _isSessionActive.asStateFlow()

        private val _currentAppName = MutableStateFlow("")
        val currentAppName = _currentAppName.asStateFlow()

        private val _currentPackage = MutableStateFlow("")
        val currentPackage = _currentPackage.asStateFlow()

        fun startSession(
            context: Context,
            sessionId: Long,
            packageName: String,
            appName: String,
            expirationTimeMillis: Long,
            durationMinutes: Int
        ) {
            val intent = Intent(context, FocusVaultService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_EXPIRATION_TIME, expirationTimeMillis)
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSession(context: Context) {
            val intent = Intent(context, FocusVaultService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            context.startService(intent)
        }
    }
}
