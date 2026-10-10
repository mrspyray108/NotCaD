package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "notcad_user_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val IS_FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("is_first_launch_completed")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val STRICT_MODE_ENABLED = booleanPreferencesKey("strict_mode_enabled")
        val EMERGENCY_UNLOCK_ENABLED = booleanPreferencesKey("emergency_unlock_enabled")
        val EMERGENCY_PIN_HASH = stringPreferencesKey("emergency_pin_hash")
        val EMERGENCY_PIN_SALT = stringPreferencesKey("emergency_pin_salt")
        val ACTIVE_SESSION_PACKAGE = stringPreferencesKey("active_session_package")
        val ACTIVE_SESSION_APP_NAME = stringPreferencesKey("active_session_app_name")
        val ACTIVE_SESSION_EXPIRATION = longPreferencesKey("active_session_expiration")
        val ACTIVE_SESSION_START = longPreferencesKey("active_session_start")
        val ACTIVE_SESSION_DURATION = intPreferencesKey("active_session_duration")
        val ACTIVE_SESSION_ID = longPreferencesKey("active_session_id")
        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val LOCKOUT_UNTIL_MILLIS = longPreferencesKey("lockout_until_millis")
        val DEVICE_LOCK_PREFERENCE = stringPreferencesKey("device_lock_preference") // "ACCESSIBILITY_LOCK", "DEVICE_ADMIN", "HOME_RETURN"
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        UserPreferences(
            isFirstLaunchCompleted = preferences[PreferencesKeys.IS_FIRST_LAUNCH_COMPLETED] ?: false,
            pinHash = preferences[PreferencesKeys.PIN_HASH] ?: "",
            pinSalt = preferences[PreferencesKeys.PIN_SALT] ?: "",
            biometricEnabled = preferences[PreferencesKeys.BIOMETRIC_ENABLED] ?: false,
            strictModeEnabled = preferences[PreferencesKeys.STRICT_MODE_ENABLED] ?: true,
            emergencyUnlockEnabled = preferences[PreferencesKeys.EMERGENCY_UNLOCK_ENABLED] ?: false,
            emergencyPinHash = preferences[PreferencesKeys.EMERGENCY_PIN_HASH] ?: "",
            emergencyPinSalt = preferences[PreferencesKeys.EMERGENCY_PIN_SALT] ?: "",
            activeSessionPackage = preferences[PreferencesKeys.ACTIVE_SESSION_PACKAGE],
            activeSessionAppName = preferences[PreferencesKeys.ACTIVE_SESSION_APP_NAME],
            activeSessionExpiration = preferences[PreferencesKeys.ACTIVE_SESSION_EXPIRATION] ?: 0L,
            activeSessionStart = preferences[PreferencesKeys.ACTIVE_SESSION_START] ?: 0L,
            activeSessionDuration = preferences[PreferencesKeys.ACTIVE_SESSION_DURATION] ?: 0,
            activeSessionId = preferences[PreferencesKeys.ACTIVE_SESSION_ID] ?: 0L,
            failedAttempts = preferences[PreferencesKeys.FAILED_ATTEMPTS] ?: 0,
            lockoutUntilMillis = preferences[PreferencesKeys.LOCKOUT_UNTIL_MILLIS] ?: 0L,
            deviceLockPreference = preferences[PreferencesKeys.DEVICE_LOCK_PREFERENCE] ?: "ACCESSIBILITY_LOCK"
        )
    }

    suspend fun setFirstLaunchCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH_COMPLETED] = completed
        }
    }

    suspend fun setPinCredentials(hash: String, salt: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PIN_HASH] = hash
            preferences[PreferencesKeys.PIN_SALT] = salt
        }
    }

    suspend fun setEmergencyPinCredentials(hash: String, salt: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.EMERGENCY_PIN_HASH] = hash
            preferences[PreferencesKeys.EMERGENCY_PIN_SALT] = salt
            preferences[PreferencesKeys.EMERGENCY_UNLOCK_ENABLED] = enabled
        }
    }

    suspend fun setStrictModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STRICT_MODE_ENABLED] = enabled
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BIOMETRIC_ENABLED] = enabled
        }
    }

    suspend fun setDeviceLockPreference(pref: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEVICE_LOCK_PREFERENCE] = pref
        }
    }

    suspend fun saveActiveSession(
        sessionId: Long,
        packageName: String,
        appName: String,
        startTimeMillis: Long,
        expirationTimeMillis: Long,
        durationMinutes: Int
    ) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ACTIVE_SESSION_ID] = sessionId
            preferences[PreferencesKeys.ACTIVE_SESSION_PACKAGE] = packageName
            preferences[PreferencesKeys.ACTIVE_SESSION_APP_NAME] = appName
            preferences[PreferencesKeys.ACTIVE_SESSION_START] = startTimeMillis
            preferences[PreferencesKeys.ACTIVE_SESSION_EXPIRATION] = expirationTimeMillis
            preferences[PreferencesKeys.ACTIVE_SESSION_DURATION] = durationMinutes
        }
    }

    suspend fun clearActiveSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_ID)
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_PACKAGE)
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_APP_NAME)
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_START)
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_EXPIRATION)
            preferences.remove(PreferencesKeys.ACTIVE_SESSION_DURATION)
        }
    }

    suspend fun recordFailedAttempt() {
        context.dataStore.edit { preferences ->
            val currentAttempts = (preferences[PreferencesKeys.FAILED_ATTEMPTS] ?: 0) + 1
            preferences[PreferencesKeys.FAILED_ATTEMPTS] = currentAttempts
            if (currentAttempts >= 5) {
                // Lockout for 30 seconds
                preferences[PreferencesKeys.LOCKOUT_UNTIL_MILLIS] = System.currentTimeMillis() + 30_000L
            }
        }
    }

    suspend fun resetFailedAttempts() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FAILED_ATTEMPTS] = 0
            preferences[PreferencesKeys.LOCKOUT_UNTIL_MILLIS] = 0L
        }
    }
}

data class UserPreferences(
    val isFirstLaunchCompleted: Boolean,
    val pinHash: String,
    val pinSalt: String,
    val biometricEnabled: Boolean,
    val strictModeEnabled: Boolean,
    val emergencyUnlockEnabled: Boolean,
    val emergencyPinHash: String,
    val emergencyPinSalt: String,
    val activeSessionPackage: String?,
    val activeSessionAppName: String?,
    val activeSessionExpiration: Long,
    val activeSessionStart: Long,
    val activeSessionDuration: Int,
    val activeSessionId: Long,
    val failedAttempts: Int,
    val lockoutUntilMillis: Long,
    val deviceLockPreference: String
)
