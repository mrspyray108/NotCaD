package com.example.domain.model

import android.graphics.drawable.Drawable

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val category: String = "Productivity & Media",
    val isProtected: Boolean = false,
    val icon: Drawable? = null,
    val dailyLimitMinutes: Int = 0,
    val isSystemApp: Boolean = false
)

enum class FocusState {
    IDLE,
    AUTHENTICATING,
    TIMER_SELECT,
    SESSION_ACTIVE,
    SESSION_EXPIRED
}

data class FocusSessionState(
    val sessionId: Long = 0L,
    val packageName: String = "",
    val appName: String = "",
    val startTimeMillis: Long = 0L,
    val expirationTimeMillis: Long = 0L,
    val totalDurationMinutes: Int = 0,
    val remainingMillis: Long = 0L,
    val isRunning: Boolean = false
) {
    val progress: Float
        get() {
            if (totalDurationMinutes <= 0) return 0f
            val totalMillis = totalDurationMinutes * 60 * 1000L
            val elapsed = (totalMillis - remainingMillis).coerceAtLeast(0L)
            return (elapsed.toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
        }

    val formattedRemainingTime: String
        get() {
            val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
}
