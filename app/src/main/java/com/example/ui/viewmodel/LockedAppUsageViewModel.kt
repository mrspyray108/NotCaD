package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.NotCadApp
import com.example.domain.repository.FocusRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Represents the lifecycle stages of an application usage lock and cooldown.
 */
enum class LockedAppSessionStatus {
    IDLE,
    ACTIVE_COUNTDOWN,
    COOLDOWN,
    LOCKED
}

/**
 * Observable events emitted by [LockedAppUsageViewModel].
 */
sealed interface LockedAppEvent {
    data class SessionStarted(
        val packageName: String,
        val appName: String,
        val durationMillis: Long
    ) : LockedAppEvent

    data class UsageTick(
        val remainingMillis: Long,
        val formattedTime: String
    ) : LockedAppEvent

    data class CooldownTriggered(
        val packageName: String,
        val appName: String,
        val cooldownDurationMillis: Long,
        val reason: String = "Usage countdown reached zero"
    ) : LockedAppEvent

    data class CooldownTick(
        val remainingCooldownMillis: Long,
        val formattedTime: String
    ) : LockedAppEvent

    data class CooldownFinished(
        val packageName: String,
        val appName: String
    ) : LockedAppEvent

    data class WarningTimeRemaining(
        val remainingSeconds: Int
    ) : LockedAppEvent

    data class SessionPaused(
        val remainingMillis: Long
    ) : LockedAppEvent

    data class SessionResumed(
        val remainingMillis: Long
    ) : LockedAppEvent

    data class SessionTerminated(
        val packageName: String,
        val reason: String
    ) : LockedAppEvent

    data class CooldownBypassed(
        val packageName: String,
        val reason: String
    ) : LockedAppEvent

    data class Error(
        val message: String
    ) : LockedAppEvent
}

/**
 * Immutable UI State representing current countdown and cooldown status.
 */
data class LockedAppUsageUiState(
    val packageName: String = "",
    val appName: String = "",
    val status: LockedAppSessionStatus = LockedAppSessionStatus.IDLE,
    val totalUsageMillis: Long = 0L,
    val remainingUsageMillis: Long = 0L,
    val formattedRemainingUsage: String = "00:00",
    val usageProgress: Float = 1.0f,
    val isCooldownActive: Boolean = false,
    val totalCooldownMillis: Long = 0L,
    val remainingCooldownMillis: Long = 0L,
    val formattedCooldownRemaining: String = "00:00",
    val cooldownProgress: Float = 0.0f,
    val cooldownReason: String = "Usage limit reached. App is resting in cooldown.",
    val isPaused: Boolean = false,
    val sessionStartTimeMillis: Long = 0L,
    val sessionExpirationMillis: Long = 0L,
    val cooldownExpirationMillis: Long = 0L,
    val canOverrideCooldown: Boolean = true,
    val strictModeEnabled: Boolean = false,
    val warningThirtySecondsTriggered: Boolean = false
) {
    val isInActiveSession: Boolean
        get() = status == LockedAppSessionStatus.ACTIVE_COUNTDOWN

    val isInCooldown: Boolean
        get() = status == LockedAppSessionStatus.COOLDOWN

    val isLocked: Boolean
        get() = status == LockedAppSessionStatus.LOCKED
}

/**
 * ViewModel that tracks remaining usage time for a locked application
 * and automatically triggers a 'cooldown' state once the countdown hits zero.
 */
class LockedAppUsageViewModel @JvmOverloads constructor(
    private val repository: FocusRepository? = null,
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val tickerIntervalMillis: Long = 1000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // Secondary constructor for Android ViewModelProvider compatibility
    constructor(application: Application) : this(
        repository = (application as? NotCadApp)?.focusRepository
    )

    private val _uiState = MutableStateFlow(LockedAppUsageUiState())
    val uiState: StateFlow<LockedAppUsageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LockedAppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LockedAppEvent> = _events.asSharedFlow()

    private var usageTickerJob: Job? = null
    private var cooldownTickerJob: Job? = null

    /**
     * Starts tracking usage time for the given locked application.
     * When the countdown hits zero, a cooldown state is automatically triggered.
     *
     * @param packageName Package identifier of the locked application.
     * @param appName Human-readable name of the application.
     * @param durationMillis Total permitted usage time in milliseconds.
     * @param cooldownMillis Cooldown time in milliseconds to enforce after usage expires.
     * @param strictMode If true, emergency override of cooldown is forbidden.
     */
    fun startUsageCountdown(
        packageName: String,
        appName: String,
        durationMillis: Long,
        cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
        strictMode: Boolean = false
    ) {
        stopAllJobs()

        val now = currentTimeProvider()
        val expirationTime = now + durationMillis

        _uiState.update {
            it.copy(
                packageName = packageName,
                appName = appName,
                status = LockedAppSessionStatus.ACTIVE_COUNTDOWN,
                totalUsageMillis = durationMillis,
                remainingUsageMillis = durationMillis,
                formattedRemainingUsage = formatTime(durationMillis),
                usageProgress = 1.0f,
                isCooldownActive = false,
                totalCooldownMillis = cooldownMillis,
                remainingCooldownMillis = cooldownMillis,
                formattedCooldownRemaining = formatTime(cooldownMillis),
                cooldownProgress = 0.0f,
                isPaused = false,
                sessionStartTimeMillis = now,
                sessionExpirationMillis = expirationTime,
                cooldownExpirationMillis = 0L,
                strictModeEnabled = strictMode,
                canOverrideCooldown = !strictMode,
                warningThirtySecondsTriggered = false
            )
        }

        viewModelScope.launch(dispatcher) {
            _events.emit(
                LockedAppEvent.SessionStarted(
                    packageName = packageName,
                    appName = appName,
                    durationMillis = durationMillis
                )
            )
            repository?.logAudit(
                eventType = "USAGE_COUNTDOWN_STARTED",
                packageName = packageName,
                details = "Duration: ${durationMillis}ms, Planned Cooldown: ${cooldownMillis}ms"
            )
        }

        startUsageTicker(expirationTime, cooldownMillis)
    }

    /**
     * Convenience method to start countdown using minutes.
     */
    fun startUsageCountdownMinutes(
        packageName: String,
        appName: String,
        durationMinutes: Int,
        cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
        strictMode: Boolean = false
    ) {
        val durationMillis = durationMinutes * 60 * 1000L
        val cooldownMillis = cooldownMinutes * 60 * 1000L
        startUsageCountdown(
            packageName = packageName,
            appName = appName,
            durationMillis = durationMillis,
            cooldownMillis = cooldownMillis,
            strictMode = strictMode
        )
    }

    /**
     * Coroutine loop that decrements the remaining usage countdown.
     * Once remaining time reaches zero, the cooldown state is triggered.
     */
    private fun startUsageTicker(expirationTime: Long, cooldownMillis: Long) {
        usageTickerJob = viewModelScope.launch(dispatcher) {
            while (isActive) {
                delay(tickerIntervalMillis)
                val now = currentTimeProvider()
                val remaining = (expirationTime - now).coerceAtLeast(0L)
                val total = _uiState.value.totalUsageMillis
                val progress = if (total > 0L) (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                val formattedTime = formatTime(remaining)

                val warningAlreadyTriggered = _uiState.value.warningThirtySecondsTriggered
                val shouldTriggerWarning = !warningAlreadyTriggered && remaining in 1L..30000L

                _uiState.update { current ->
                    current.copy(
                        remainingUsageMillis = remaining,
                        formattedRemainingUsage = formattedTime,
                        usageProgress = progress,
                        warningThirtySecondsTriggered = if (shouldTriggerWarning) true else current.warningThirtySecondsTriggered
                    )
                }

                _events.emit(LockedAppEvent.UsageTick(remaining, formattedTime))

                if (shouldTriggerWarning) {
                    _events.emit(LockedAppEvent.WarningTimeRemaining(remainingSeconds = (remaining / 1000).toInt()))
                }

                if (remaining <= 0L) {
                    // Countdown hit zero -> Trigger cooldown state!
                    triggerCooldown(
                        cooldownDurationMillis = cooldownMillis,
                        reason = "Usage time expired for ${_uiState.value.appName}. Entering cooldown state."
                    )
                    break
                }
            }
        }
    }

    /**
     * Transitions the application to the COOLDOWN state.
     * Can be invoked automatically upon countdown hitting zero or explicitly.
     */
    fun triggerCooldown(
        cooldownDurationMillis: Long = _uiState.value.totalCooldownMillis.takeIf { it > 0L } ?: DEFAULT_COOLDOWN_MILLIS,
        reason: String = "Usage countdown reached zero. Cooldown initiated."
    ) {
        usageTickerJob?.cancel()
        cooldownTickerJob?.cancel()

        val now = currentTimeProvider()
        val cooldownEnd = now + cooldownDurationMillis
        val currentPackage = _uiState.value.packageName
        val currentAppName = _uiState.value.appName
        val isStrict = _uiState.value.strictModeEnabled

        _uiState.update { current ->
            current.copy(
                status = LockedAppSessionStatus.COOLDOWN,
                remainingUsageMillis = 0L,
                formattedRemainingUsage = "00:00",
                usageProgress = 0.0f,
                isCooldownActive = true,
                totalCooldownMillis = cooldownDurationMillis,
                remainingCooldownMillis = cooldownDurationMillis,
                formattedCooldownRemaining = formatTime(cooldownDurationMillis),
                cooldownProgress = 0.0f,
                cooldownExpirationMillis = cooldownEnd,
                cooldownReason = reason,
                isPaused = false,
                canOverrideCooldown = !isStrict
            )
        }

        viewModelScope.launch(dispatcher) {
            _events.emit(
                LockedAppEvent.CooldownTriggered(
                    packageName = currentPackage,
                    appName = currentAppName,
                    cooldownDurationMillis = cooldownDurationMillis,
                    reason = reason
                )
            )
            repository?.logAudit(
                eventType = "COOLDOWN_TRIGGERED",
                packageName = currentPackage,
                details = "Duration: ${cooldownDurationMillis}ms, Reason: $reason"
            )
        }

        startCooldownTicker(cooldownEnd)
    }

    /**
     * Coroutine loop that decrements the remaining cooldown timer.
     * When cooldown reaches zero, transitions the application to LOCKED state.
     */
    private fun startCooldownTicker(cooldownEnd: Long) {
        cooldownTickerJob = viewModelScope.launch(dispatcher) {
            while (isActive) {
                delay(tickerIntervalMillis)
                val now = currentTimeProvider()
                val remaining = (cooldownEnd - now).coerceAtLeast(0L)
                val total = _uiState.value.totalCooldownMillis
                val elapsed = total - remaining
                val progress = if (total > 0L) (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 1f
                val formattedTime = formatTime(remaining)

                _uiState.update { current ->
                    current.copy(
                        remainingCooldownMillis = remaining,
                        formattedCooldownRemaining = formattedTime,
                        cooldownProgress = progress
                    )
                }

                _events.emit(LockedAppEvent.CooldownTick(remaining, formattedTime))

                if (remaining <= 0L) {
                    onCooldownFinished()
                    break
                }
            }
        }
    }

    /**
     * Called when the cooldown timer reaches zero.
     * Locks the app firmly and emits the finished event.
     */
    private fun onCooldownFinished() {
        cooldownTickerJob?.cancel()
        val currentPackage = _uiState.value.packageName
        val currentAppName = _uiState.value.appName

        _uiState.update { current ->
            current.copy(
                status = LockedAppSessionStatus.LOCKED,
                isCooldownActive = false,
                remainingCooldownMillis = 0L,
                formattedCooldownRemaining = "00:00",
                cooldownProgress = 1.0f
            )
        }

        viewModelScope.launch(dispatcher) {
            _events.emit(
                LockedAppEvent.CooldownFinished(
                    packageName = currentPackage,
                    appName = currentAppName
                )
            )
            repository?.logAudit(
                eventType = "COOLDOWN_COMPLETED",
                packageName = currentPackage,
                details = "Cooldown finished successfully. App locked."
            )
        }
    }

    /**
     * Pauses the active usage countdown.
     */
    fun pauseCountdown() {
        if (_uiState.value.status != LockedAppSessionStatus.ACTIVE_COUNTDOWN || _uiState.value.isPaused) return
        usageTickerJob?.cancel()
        _uiState.update { it.copy(isPaused = true) }
        viewModelScope.launch(dispatcher) {
            _events.emit(LockedAppEvent.SessionPaused(_uiState.value.remainingUsageMillis))
        }
    }

    /**
     * Resumes the paused usage countdown.
     */
    fun resumeCountdown() {
        if (_uiState.value.status != LockedAppSessionStatus.ACTIVE_COUNTDOWN || !_uiState.value.isPaused) return
        val remaining = _uiState.value.remainingUsageMillis
        if (remaining <= 0L) {
            triggerCooldown()
            return
        }
        val now = currentTimeProvider()
        val newExpiration = now + remaining
        _uiState.update {
            it.copy(
                isPaused = false,
                sessionExpirationMillis = newExpiration
            )
        }
        viewModelScope.launch(dispatcher) {
            _events.emit(LockedAppEvent.SessionResumed(remaining))
        }
        startUsageTicker(newExpiration, _uiState.value.totalCooldownMillis)
    }

    /**
     * Prematurely ends the active session and locks the application.
     */
    fun stopSession(reason: String = "User ended session early") {
        val currentPackage = _uiState.value.packageName
        stopAllJobs()
        _uiState.update {
            it.copy(
                status = LockedAppSessionStatus.LOCKED,
                remainingUsageMillis = 0L,
                formattedRemainingUsage = "00:00",
                usageProgress = 0f,
                isCooldownActive = false,
                isPaused = false
            )
        }
        viewModelScope.launch(dispatcher) {
            _events.emit(LockedAppEvent.SessionTerminated(currentPackage, reason))
            repository?.logAudit(
                eventType = "SESSION_TERMINATED",
                packageName = currentPackage,
                details = reason
            )
        }
    }

    /**
     * Allows an authorized override of cooldown if strict mode is not active.
     */
    fun overrideCooldown(pin: String? = null, reason: String = "Emergency override"): Boolean {
        if (_uiState.value.strictModeEnabled) {
            viewModelScope.launch(dispatcher) {
                _events.emit(LockedAppEvent.Error("Strict mode enabled: Cooldown cannot be bypassed!"))
            }
            return false
        }
        stopAllJobs()
        val currentPackage = _uiState.value.packageName
        _uiState.update {
            it.copy(
                status = LockedAppSessionStatus.IDLE,
                isCooldownActive = false,
                remainingCooldownMillis = 0L,
                formattedCooldownRemaining = "00:00",
                cooldownProgress = 1.0f
            )
        }
        viewModelScope.launch(dispatcher) {
            _events.emit(LockedAppEvent.CooldownBypassed(currentPackage, reason))
            repository?.logAudit(
                eventType = "COOLDOWN_BYPASSED",
                packageName = currentPackage,
                details = reason
            )
        }
        return true
    }

    /**
     * Acknowledges that cooldown is finished and returns to idle state.
     */
    fun acknowledgeCooldownFinished() {
        if (_uiState.value.status == LockedAppSessionStatus.LOCKED) {
            _uiState.update { it.copy(status = LockedAppSessionStatus.IDLE) }
        }
    }

    /**
     * Extends or adds time to the active usage countdown.
     */
    fun addUsageTime(additionalMillis: Long) {
        if (_uiState.value.status != LockedAppSessionStatus.ACTIVE_COUNTDOWN) return
        val currentRemaining = _uiState.value.remainingUsageMillis
        val newRemaining = currentRemaining + additionalMillis
        val newTotal = _uiState.value.totalUsageMillis + additionalMillis
        val now = currentTimeProvider()
        val newExpiration = now + newRemaining

        usageTickerJob?.cancel()
        _uiState.update {
            it.copy(
                remainingUsageMillis = newRemaining,
                totalUsageMillis = newTotal,
                formattedRemainingUsage = formatTime(newRemaining),
                sessionExpirationMillis = newExpiration,
                usageProgress = (newRemaining.toFloat() / newTotal.toFloat()).coerceIn(0f, 1f)
            )
        }
        startUsageTicker(newExpiration, _uiState.value.totalCooldownMillis)
    }

    /**
     * Toggles or sets strict mode on the current session.
     */
    fun setStrictMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                strictModeEnabled = enabled,
                canOverrideCooldown = !enabled
            )
        }
    }

    /**
     * Resets state back to initial IDLE.
     */
    fun reset() {
        stopAllJobs()
        _uiState.value = LockedAppUsageUiState()
    }

    private fun stopAllJobs() {
        usageTickerJob?.cancel()
        usageTickerJob = null
        cooldownTickerJob?.cancel()
        cooldownTickerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAllJobs()
    }

    /**
     * Formats milliseconds into standard digital clock format (HH:MM:SS or MM:SS).
     */
    fun formatTime(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MINUTES = 15
        const val DEFAULT_COOLDOWN_MILLIS = DEFAULT_COOLDOWN_MINUTES * 60 * 1000L
    }
}
