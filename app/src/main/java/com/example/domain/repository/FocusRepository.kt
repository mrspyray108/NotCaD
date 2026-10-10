package com.example.domain.repository

import com.example.data.local.entity.FocusSessionEntity
import com.example.data.local.entity.ProtectedAppEntity
import com.example.data.local.entity.SessionAuditLogEntity
import com.example.data.preferences.UserPreferences
import com.example.domain.model.InstalledAppInfo
import kotlinx.coroutines.flow.Flow

interface FocusRepository {
    val userPreferences: Flow<UserPreferences>
    val protectedAppsFlow: Flow<List<ProtectedAppEntity>>
    val activeSessionFlow: Flow<FocusSessionEntity?>
    val allSessionsFlow: Flow<List<FocusSessionEntity>>
    val auditLogsFlow: Flow<List<SessionAuditLogEntity>>

    suspend fun getInstalledApplications(): List<InstalledAppInfo>
    suspend fun setAppProtected(packageName: String, appName: String, isProtected: Boolean)
    suspend fun removeProtectedApp(packageName: String)
    suspend fun startSession(packageName: String, appName: String, durationMinutes: Int): FocusSessionEntity
    suspend fun completeActiveSession(sessionId: Long, earlyTerminated: Boolean = false)
    suspend fun verifyPin(pin: String): Boolean
    suspend fun setupMasterPin(pin: String)
    suspend fun verifyEmergencyPin(pin: String): Boolean
    suspend fun setupEmergencyPin(pin: String, enabled: Boolean)
    suspend fun setStrictMode(enabled: Boolean)
    suspend fun setBiometric(enabled: Boolean)
    suspend fun setDeviceLockPreference(preference: String)
    suspend fun completeOnboarding()
    suspend fun logAudit(eventType: String, packageName: String? = null, details: String = "")
    fun getTodayTotalMinutes(startOfDayMillis: Long): Flow<Int?>
    fun getTodaySessionCount(startOfDayMillis: Long): Flow<Int>
}
