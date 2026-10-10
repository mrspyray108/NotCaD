package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.local.NotCadDatabase
import com.example.data.local.entity.FocusSessionEntity
import com.example.data.local.entity.ProtectedAppEntity
import com.example.data.local.entity.SessionAuditLogEntity
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.model.InstalledAppInfo
import com.example.domain.repository.FocusRepository
import com.example.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class FocusRepositoryImpl(
    private val context: Context,
    private val database: NotCadDatabase,
    private val preferencesRepository: UserPreferencesRepository
) : FocusRepository {

    override val userPreferences: Flow<UserPreferences> = preferencesRepository.userPreferencesFlow
    override val protectedAppsFlow: Flow<List<ProtectedAppEntity>> = database.protectedAppDao().getAllProtectedApps()
    override val activeSessionFlow: Flow<FocusSessionEntity?> = database.focusSessionDao().getActiveSessionFlow()
    override val allSessionsFlow: Flow<List<FocusSessionEntity>> = database.focusSessionDao().getAllSessions()
    override val auditLogsFlow: Flow<List<SessionAuditLogEntity>> = database.auditLogDao().getRecentAuditLogs()

    override suspend fun getInstalledApplications(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val protectedList = database.protectedAppDao().getActiveProtectedApps().first()
        val protectedPackages = protectedList.associateBy { it.packageName }

        val apps = resolveInfos.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            if (packageName == context.packageName) return@mapNotNull null // Don't block self
            val appName = resolveInfo.loadLabel(pm).toString()
            val icon = resolveInfo.loadIcon(pm)
            val isProtected = protectedPackages.containsKey(packageName)
            val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            InstalledAppInfo(
                packageName = packageName,
                appName = appName,
                isProtected = isProtected,
                icon = icon,
                isSystemApp = isSystem
            )
        }.distinctBy { it.packageName }.sortedWith(
            compareByDescending<InstalledAppInfo> { it.isProtected }
                .thenBy { it.appName.lowercase() }
        )

        apps
    }

    override suspend fun setAppProtected(packageName: String, appName: String, isProtected: Boolean) {
        withContext(Dispatchers.IO) {
            val entity = ProtectedAppEntity(
                packageName = packageName,
                appName = appName,
                isProtected = isProtected
            )
            database.protectedAppDao().insertOrUpdateApp(entity)
            logAudit(
                eventType = if (isProtected) "APP_PROTECTED" else "APP_UNPROTECTED",
                packageName = packageName,
                details = "$appName is now ${if (isProtected) "locked in FocusVault" else "unlocked"}"
            )
        }
    }

    override suspend fun removeProtectedApp(packageName: String) {
        withContext(Dispatchers.IO) {
            database.protectedAppDao().deleteByPackage(packageName)
            logAudit("APP_REMOVED", packageName, "App removed from vault")
        }
    }

    override suspend fun startSession(
        packageName: String,
        appName: String,
        durationMinutes: Int
    ): FocusSessionEntity = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val expirationTime = startTime + (durationMinutes * 60 * 1000L)
        val entity = FocusSessionEntity(
            packageName = packageName,
            appName = appName,
            startTimeMillis = startTime,
            expirationTimeMillis = expirationTime,
            durationMinutes = durationMinutes,
            status = "ACTIVE"
        )
        val id = database.focusSessionDao().insertSession(entity)
        val savedEntity = entity.copy(id = id)

        // Save active session in DataStore for fast recovery across reboots/services
        preferencesRepository.saveActiveSession(
            sessionId = id,
            packageName = packageName,
            appName = appName,
            startTimeMillis = startTime,
            expirationTimeMillis = expirationTime,
            durationMinutes = durationMinutes
        )

        logAudit("SESSION_START", packageName, "Started $durationMinutes min session for $appName")
        savedEntity
    }

    override suspend fun completeActiveSession(sessionId: Long, earlyTerminated: Boolean) {
        withContext(Dispatchers.IO) {
            val status = if (earlyTerminated) "EARLY_TERMINATED" else "EXPIRED"
            database.focusSessionDao().updateSessionStatus(sessionId, status)
            preferencesRepository.clearActiveSession()
            logAudit(
                eventType = if (earlyTerminated) "SESSION_CANCELLED" else "SESSION_EXPIRED",
                details = "Session $sessionId completed ($status)"
            )
        }
    }

    override suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.userPreferencesFlow.first()
        if (prefs.pinHash.isEmpty() || prefs.pinSalt.isEmpty()) return@withContext false

        // Check lockout
        if (prefs.lockoutUntilMillis > System.currentTimeMillis()) {
            logAudit("AUTH_LOCKED_OUT", details = "Attempt blocked due to rate-limiting lockout")
            return@withContext false
        }

        val isValid = CryptoManager.verifyPin(pin, prefs.pinHash, prefs.pinSalt)
        if (isValid) {
            preferencesRepository.resetFailedAttempts()
            logAudit("AUTH_SUCCESS", details = "Master PIN verified successfully")
        } else {
            preferencesRepository.recordFailedAttempt()
            logAudit("AUTH_FAIL", details = "Incorrect PIN entered")
        }
        isValid
    }

    override suspend fun setupMasterPin(pin: String) {
        withContext(Dispatchers.IO) {
            val salt = CryptoManager.generateSalt()
            val hash = CryptoManager.hashPin(pin, salt)
            preferencesRepository.setPinCredentials(hash, salt)
            logAudit("PIN_CONFIGURED", details = "Master PIN set/updated")
        }
    }

    override suspend fun verifyEmergencyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.userPreferencesFlow.first()
        if (!prefs.emergencyUnlockEnabled || prefs.emergencyPinHash.isEmpty()) return@withContext false
        val isValid = CryptoManager.verifyPin(pin, prefs.emergencyPinHash, prefs.emergencyPinSalt)
        if (isValid) {
            logAudit("EMERGENCY_OVERRIDE", details = "Emergency unlock code validated")
        }
        isValid
    }

    override suspend fun setupEmergencyPin(pin: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            if (pin.isNotBlank()) {
                val salt = CryptoManager.generateSalt()
                val hash = CryptoManager.hashPin(pin, salt)
                preferencesRepository.setEmergencyPinCredentials(hash, salt, enabled)
            } else {
                preferencesRepository.setEmergencyPinCredentials("", "", false)
            }
            logAudit("EMERGENCY_CONFIG", details = "Emergency PIN ${if (enabled) "enabled" else "disabled"}")
        }
    }

    override suspend fun setStrictMode(enabled: Boolean) {
        preferencesRepository.setStrictModeEnabled(enabled)
        logAudit("STRICT_MODE_TOGGLE", details = "Strict focus mode set to $enabled")
    }

    override suspend fun setBiometric(enabled: Boolean) {
        preferencesRepository.setBiometricEnabled(enabled)
        logAudit("BIOMETRIC_TOGGLE", details = "Biometric unlock set to $enabled")
    }

    override suspend fun setDeviceLockPreference(preference: String) {
        preferencesRepository.setDeviceLockPreference(preference)
    }

    override suspend fun completeOnboarding() {
        preferencesRepository.setFirstLaunchCompleted(true)
    }

    override suspend fun logAudit(eventType: String, packageName: String?, details: String) {
        withContext(Dispatchers.IO) {
            database.auditLogDao().insertLog(
                SessionAuditLogEntity(
                    eventType = eventType,
                    packageName = packageName,
                    details = details
                )
            )
        }
    }

    override fun getTodayTotalMinutes(startOfDayMillis: Long): Flow<Int?> =
        database.focusSessionDao().getTodayTotalMinutes(startOfDayMillis)

    override fun getTodaySessionCount(startOfDayMillis: Long): Flow<Int> =
        database.focusSessionDao().getTodaySessionCount(startOfDayMillis)
}
