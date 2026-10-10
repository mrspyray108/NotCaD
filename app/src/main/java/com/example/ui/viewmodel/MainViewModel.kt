package com.example.ui.viewmodel

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NotCadApp
import com.example.data.local.entity.FocusSessionEntity
import com.example.data.local.entity.ProtectedAppEntity
import com.example.data.local.entity.SessionAuditLogEntity
import com.example.data.preferences.UserPreferences
import com.example.domain.model.FocusSessionState
import com.example.domain.model.InstalledAppInfo
import com.example.receiver.NotCadDeviceAdminReceiver
import com.example.service.FocusVaultService
import com.example.service.NotCadAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Onboarding : Screen("onboarding")
    object PermissionSetup : Screen("permission_setup")
    object PinSetup : Screen("pin_setup")
    object Dashboard : Screen("dashboard")
    object ProtectedApps : Screen("protected_apps")
    object Authentication : Screen("authentication")
    object TimerSelection : Screen("timer_selection")
    object ActiveSession : Screen("active_session")
    object SessionFinished : Screen("session_finished")
    object Cooldown : Screen("cooldown")
    object Settings : Screen("settings")
    object UsageHistory : Screen("usage_history")
}

enum class AuthMode {
    UNLOCK_APP,
    ACCESS_SETTINGS,
    END_SESSION_EARLY,
    MODIFY_PROTECTION,
    EMERGENCY_OVERRIDE
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NotCadApp).focusRepository
    private val context = application.applicationContext

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Splash)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _userPreferences = MutableStateFlow<UserPreferences?>(null)
    val userPreferences: StateFlow<UserPreferences?> = _userPreferences.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    private val _appCategoryFilter = MutableStateFlow("ALL") // "ALL", "PROTECTED", "SUGGESTED"
    val appCategoryFilter: StateFlow<String> = _appCategoryFilter.asStateFlow()

    val filteredApps: StateFlow<List<InstalledAppInfo>> = combine(
        _installedApps,
        _appSearchQuery,
        _appCategoryFilter
    ) { apps, query, filter ->
        apps.filter { app ->
            val matchesQuery = app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                "PROTECTED" -> app.isProtected
                "SUGGESTED" -> isKnownDistractingApp(app.packageName, app.appName)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active session tracking
    private val _activeSessionState = MutableStateFlow(FocusSessionState())
    val activeSessionState: StateFlow<FocusSessionState> = _activeSessionState.asStateFlow()

    // Targeted App for Auth & Timer selection
    private val _targetApp = MutableStateFlow<InstalledAppInfo?>(null)
    val targetApp: StateFlow<InstalledAppInfo?> = _targetApp.asStateFlow()

    // Auth state
    private val _enteredPin = MutableStateFlow("")
    val enteredPin: StateFlow<String> = _enteredPin.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authMode = MutableStateFlow(AuthMode.UNLOCK_APP)
    val authMode: StateFlow<AuthMode> = _authMode.asStateFlow()

    private val _lockoutSeconds = MutableStateFlow(0)
    val lockoutSeconds: StateFlow<Int> = _lockoutSeconds.asStateFlow()

    // PIN Setup State
    private val _setupPinFirst = MutableStateFlow("")
    val setupPinFirst: StateFlow<String> = _setupPinFirst.asStateFlow()
    private val _isConfirmingPin = MutableStateFlow(false)
    val isConfirmingPin: StateFlow<Boolean> = _isConfirmingPin.asStateFlow()

    // Session History & Stats
    val allSessions: StateFlow<List<FocusSessionEntity>> = repository.allSessionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<SessionAuditLogEntity>> = repository.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _todayMinutes = MutableStateFlow(0)
    val todayMinutes: StateFlow<Int> = _todayMinutes.asStateFlow()

    private val _todaySessionCount = MutableStateFlow(0)
    val todaySessionCount: StateFlow<Int> = _todaySessionCount.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private var lockoutTickerJob: Job? = null

    init {
        observePreferences()
        observeActiveSessionService()
        loadTodayStats()
        loadApps()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            repository.userPreferences.collect { prefs ->
                _userPreferences.value = prefs
                updateLockoutState(prefs.lockoutUntilMillis)
            }
        }
    }

    private fun observeActiveSessionService() {
        viewModelScope.launch {
            combine(
                FocusVaultService.isSessionActive,
                FocusVaultService.remainingMillisState,
                FocusVaultService.currentAppName,
                FocusVaultService.currentPackage
            ) { isActive, remaining, appName, pkg ->
                val prefs = _userPreferences.value
                val totalDuration = prefs?.activeSessionDuration ?: 0
                val startTime = prefs?.activeSessionStart ?: 0L
                val expiration = prefs?.activeSessionExpiration ?: 0L

                FocusSessionState(
                    sessionId = prefs?.activeSessionId ?: 0L,
                    packageName = pkg,
                    appName = appName,
                    startTimeMillis = startTime,
                    expirationTimeMillis = expiration,
                    totalDurationMinutes = totalDuration,
                    remainingMillis = remaining,
                    isRunning = isActive
                )
            }.collect { sessionState ->
                _activeSessionState.value = sessionState
            }
        }
    }

    private fun loadTodayStats() {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis

            repository.getTodayTotalMinutes(startOfDay).collect { minutes ->
                _todayMinutes.value = minutes ?: 0
            }
        }

        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis

            repository.getTodaySessionCount(startOfDay).collect { count ->
                _todaySessionCount.value = count
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                val list = repository.getInstalledApplications()
                _installedApps.value = list
            } catch (e: Exception) {
                _toastEvent.emit("Unable to load installed apps: ${e.message}")
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setSearchQuery(query: String) {
        _appSearchQuery.value = query
    }

    fun setCategoryFilter(filter: String) {
        _appCategoryFilter.value = filter
    }

    fun toggleAppProtection(app: InstalledAppInfo) {
        viewModelScope.launch {
            val prefs = _userPreferences.value
            // Strict mode check: cannot modify protected list during active session
            if (prefs?.strictModeEnabled == true && _activeSessionState.value.isRunning) {
                _toastEvent.emit("Strict Mode: Modifying protected apps is locked during an active session!")
                return@launch
            }

            val newStatus = !app.isProtected
            repository.setAppProtected(app.packageName, app.appName, newStatus)
            loadApps()
            _toastEvent.emit(if (newStatus) "${app.appName} added to FocusVault" else "${app.appName} removed from protection")
        }
    }

    fun initiateAppLaunch(app: InstalledAppInfo) {
        _targetApp.value = app
        _enteredPin.value = ""
        _authError.value = null
        _authMode.value = AuthMode.UNLOCK_APP
        _currentScreen.value = Screen.Authentication
    }

    fun requestSettingsAuth() {
        _enteredPin.value = ""
        _authError.value = null
        _authMode.value = AuthMode.ACCESS_SETTINGS
        _currentScreen.value = Screen.Authentication
    }

    fun requestStopSessionAuth() {
        _enteredPin.value = ""
        _authError.value = null
        _authMode.value = AuthMode.END_SESSION_EARLY
        _currentScreen.value = Screen.Authentication
    }

    // Keypad PIN handling
    fun onPinDigitEntered(digit: String) {
        if (_lockoutSeconds.value > 0) return
        if (_enteredPin.value.length < 6) {
            _enteredPin.value += digit
            _authError.value = null
            if (_enteredPin.value.length >= 4) {
                // Auto-verify if 4 digits
                checkEnteredPin()
            }
        }
    }

    fun onPinBackspace() {
        if (_enteredPin.value.isNotEmpty()) {
            _enteredPin.value = _enteredPin.value.dropLast(1)
            _authError.value = null
        }
    }

    fun onPinClear() {
        _enteredPin.value = ""
        _authError.value = null
    }

    private fun checkEnteredPin() {
        viewModelScope.launch {
            val pin = _enteredPin.value
            val isValid = repository.verifyPin(pin)

            if (isValid) {
                _enteredPin.value = ""
                _authError.value = null
                handleSuccessfulAuth()
            } else {
                val isEmergency = repository.verifyEmergencyPin(pin)
                if (isEmergency) {
                    _enteredPin.value = ""
                    _authError.value = null
                    _toastEvent.emit("Emergency override accepted")
                    handleSuccessfulAuth()
                } else {
                    if (pin.length >= 4) {
                        _authError.value = "Incorrect PIN. Please try again."
                    }
                }
            }
        }
    }

    private fun handleSuccessfulAuth() {
        when (_authMode.value) {
            AuthMode.UNLOCK_APP -> {
                _currentScreen.value = Screen.TimerSelection
            }
            AuthMode.ACCESS_SETTINGS -> {
                _currentScreen.value = Screen.Settings
            }
            AuthMode.END_SESSION_EARLY -> {
                val prefs = _userPreferences.value
                if (prefs?.strictModeEnabled == true) {
                    viewModelScope.launch {
                        _toastEvent.emit("Strict Mode: Early termination is restricted until timer finishes.")
                    }
                } else {
                    FocusVaultService.stopSession(context)
                    _currentScreen.value = Screen.Dashboard
                }
            }
            AuthMode.MODIFY_PROTECTION -> {
                _currentScreen.value = Screen.ProtectedApps
            }
            AuthMode.EMERGENCY_OVERRIDE -> {
                FocusVaultService.stopSession(context)
                _currentScreen.value = Screen.Dashboard
            }
        }
    }

    fun startSessionForTargetApp(durationMinutes: Int) {
        val app = _targetApp.value ?: return
        viewModelScope.launch {
            val session = repository.startSession(
                packageName = app.packageName,
                appName = app.appName,
                durationMinutes = durationMinutes
            )

            FocusVaultService.startSession(
                context = context,
                sessionId = session.id,
                packageName = app.packageName,
                appName = app.appName,
                expirationTimeMillis = session.expirationTimeMillis,
                durationMinutes = durationMinutes
            )

            // Launch target application
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (launchIntent != null) {
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    _toastEvent.emit("Unable to open ${app.appName}: ${e.message}")
                }
            } else {
                _toastEvent.emit("App cannot be launched directly")
            }

            _currentScreen.value = Screen.ActiveSession
        }
    }

    // PIN Setup Methods
    fun onSetupPinDigitEntered(digit: String) {
        if (!_isConfirmingPin.value) {
            if (_setupPinFirst.value.length < 6) {
                _setupPinFirst.value += digit
                if (_setupPinFirst.value.length == 4) {
                    _isConfirmingPin.value = true
                    _enteredPin.value = ""
                }
            }
        } else {
            if (_enteredPin.value.length < 6) {
                _enteredPin.value += digit
                if (_enteredPin.value.length == 4) {
                    if (_enteredPin.value == _setupPinFirst.value) {
                        // Success
                        viewModelScope.launch {
                            repository.setupMasterPin(_enteredPin.value)
                            _toastEvent.emit("Master PIN created successfully!")
                            _currentScreen.value = Screen.PermissionSetup
                        }
                    } else {
                        _authError.value = "PINs do not match. Try again."
                        _enteredPin.value = ""
                        _setupPinFirst.value = ""
                        _isConfirmingPin.value = false
                    }
                }
            }
        }
    }

    fun onSetupPinBackspace() {
        if (_isConfirmingPin.value) {
            if (_enteredPin.value.isNotEmpty()) {
                _enteredPin.value = _enteredPin.value.dropLast(1)
            } else {
                _isConfirmingPin.value = false
            }
        } else {
            if (_setupPinFirst.value.isNotEmpty()) {
                _setupPinFirst.value = _setupPinFirst.value.dropLast(1)
            }
        }
    }

    // Settings actions
    fun toggleStrictMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setStrictMode(enabled)
            _toastEvent.emit(if (enabled) "Strict Focus Mode enabled" else "Strict Mode disabled")
        }
    }

    fun toggleBiometric(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBiometric(enabled)
        }
    }

    fun setDeviceLockPreference(pref: String) {
        viewModelScope.launch {
            repository.setDeviceLockPreference(pref)
        }
    }

    fun setupEmergencyPin(pin: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setupEmergencyPin(pin, enabled)
            _toastEvent.emit(if (enabled) "Emergency PIN armed" else "Emergency PIN disabled")
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.completeOnboarding()
            _currentScreen.value = Screen.PinSetup
        }
    }

    fun onSessionExpiredAcknowledged() {
        // Enforce lock or return to home
        val prefs = _userPreferences.value
        val pref = prefs?.deviceLockPreference ?: "ACCESSIBILITY_LOCK"

        if (pref == "DEVICE_ADMIN") {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val comp = ComponentName(context, NotCadDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(comp)) {
                dpm.lockNow()
            } else {
                NotCadAccessibilityService.instance?.triggerDeviceLockOrHome()
            }
        } else {
            NotCadAccessibilityService.instance?.triggerDeviceLockOrHome()
        }

        _currentScreen.value = Screen.Dashboard
    }

    private fun updateLockoutState(lockoutUntilMillis: Long) {
        val now = System.currentTimeMillis()
        if (lockoutUntilMillis > now) {
            val diffSecs = ((lockoutUntilMillis - now) / 1000).toInt()
            _lockoutSeconds.value = diffSecs

            lockoutTickerJob?.cancel()
            lockoutTickerJob = viewModelScope.launch {
                var remaining = diffSecs
                while (remaining > 0) {
                    delay(1000L)
                    remaining--
                    _lockoutSeconds.value = remaining
                }
            }
        } else {
            _lockoutSeconds.value = 0
            lockoutTickerJob?.cancel()
        }
    }

    private fun isKnownDistractingApp(packageName: String, appName: String): Boolean {
        val lowerPkg = packageName.lowercase()
        val lowerName = appName.lowercase()
        val keywords = listOf(
            "facebook", "instagram", "tiktok", "youtube", "twitter", "x.com",
            "reddit", "snapchat", "netflix", "twitch", "game", "clash",
            "candy", "roblox", "discord", "pinterest", "bilibili"
        )
        return keywords.any { lowerPkg.contains(it) || lowerName.contains(it) }
    }
}
