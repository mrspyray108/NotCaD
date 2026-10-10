package com.example

import com.example.ui.viewmodel.LockedAppEvent
import com.example.ui.viewmodel.LockedAppSessionStatus
import com.example.ui.viewmodel.LockedAppUsageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedAppUsageViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private var simulatedTime = 1000000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        simulatedTime = 1000000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(tickerIntervalMillis: Long = 100L): LockedAppUsageViewModel {
        return LockedAppUsageViewModel(
            repository = null,
            currentTimeProvider = { simulatedTime },
            tickerIntervalMillis = tickerIntervalMillis,
            dispatcher = testDispatcher
        )
    }

    @Test
    fun testInitialStateIsIdle() {
        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals(LockedAppSessionStatus.IDLE, state.status)
        assertFalse(state.isCooldownActive)
        assertEquals(0L, state.remainingUsageMillis)
        assertEquals(0L, state.remainingCooldownMillis)
        assertFalse(state.isInActiveSession)
        assertFalse(state.isInCooldown)
    }

    @Test
    fun testStartUsageCountdownInitializesActiveSession() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val durationMillis = 60000L // 1 minute
        val cooldownMillis = 30000L // 30 seconds

        viewModel.startUsageCountdown(
            packageName = "com.example.distracting",
            appName = "DistractingApp",
            durationMillis = durationMillis,
            cooldownMillis = cooldownMillis
        )

        val state = viewModel.uiState.value
        assertEquals("com.example.distracting", state.packageName)
        assertEquals("DistractingApp", state.appName)
        assertEquals(LockedAppSessionStatus.ACTIVE_COUNTDOWN, state.status)
        assertTrue(state.isInActiveSession)
        assertFalse(state.isCooldownActive)
        assertEquals(durationMillis, state.remainingUsageMillis)
        assertEquals(durationMillis, state.totalUsageMillis)
        assertEquals(1.0f, state.usageProgress, 0.01f)
        assertEquals(cooldownMillis, state.totalCooldownMillis)
    }

    @Test
    fun testCountdownTicksAndDecrementsRemainingUsage() = runTest(testDispatcher) {
        val tickerInterval = 100L
        val viewModel = createViewModel(tickerIntervalMillis = tickerInterval)
        val duration = 1000L

        viewModel.startUsageCountdown(
            packageName = "com.example.social",
            appName = "SocialApp",
            durationMillis = duration,
            cooldownMillis = 500L
        )

        // Advance by 3 ticks (300ms)
        simulatedTime += 300L
        advanceTimeBy(300L)

        val state = viewModel.uiState.value
        assertEquals(700L, state.remainingUsageMillis)
        assertEquals(LockedAppSessionStatus.ACTIVE_COUNTDOWN, state.status)
        assertTrue(state.usageProgress < 1.0f)
    }

    @Test
    fun testCountdownHitsZeroTriggersCooldownStateAutomatically() = runTest(testDispatcher) {
        val tickerInterval = 100L
        val viewModel = createViewModel(tickerIntervalMillis = tickerInterval)
        val duration = 1000L
        val cooldown = 2000L

        val eventsList = mutableListOf<LockedAppEvent>()
        val job = launch {
            viewModel.events.toList(eventsList)
        }

        viewModel.startUsageCountdown(
            packageName = "com.example.game",
            appName = "AddictiveGame",
            durationMillis = duration,
            cooldownMillis = cooldown
        )

        // Advance past expiration duration (1000ms + margin)
        simulatedTime += 1000L
        advanceTimeBy(1000L)

        val stateAfterZero = viewModel.uiState.value
        // Verify state is now COOLDOWN!
        assertEquals(LockedAppSessionStatus.COOLDOWN, stateAfterZero.status)
        assertTrue(stateAfterZero.isCooldownActive)
        assertTrue(stateAfterZero.isInCooldown)
        assertFalse(stateAfterZero.isInActiveSession)
        assertEquals(0L, stateAfterZero.remainingUsageMillis)
        assertEquals(0.0f, stateAfterZero.usageProgress, 0.01f)
        assertEquals(cooldown, stateAfterZero.totalCooldownMillis)
        assertEquals(cooldown, stateAfterZero.remainingCooldownMillis)

        // Verify CooldownTriggered event was emitted
        val cooldownEvent = eventsList.filterIsInstance<LockedAppEvent.CooldownTriggered>().firstOrNull()
        assertNotNull(cooldownEvent)
        assertEquals("com.example.game", cooldownEvent?.packageName)
        assertEquals("AddictiveGame", cooldownEvent?.appName)
        assertEquals(cooldown, cooldownEvent?.cooldownDurationMillis)

        job.cancel()
    }

    @Test
    fun testCooldownTicksAndCompletesTransitioningToLocked() = runTest(testDispatcher) {
        val tickerInterval = 100L
        val viewModel = createViewModel(tickerIntervalMillis = tickerInterval)
        val duration = 500L
        val cooldown = 1000L

        val eventsList = mutableListOf<LockedAppEvent>()
        val job = launch {
            viewModel.events.toList(eventsList)
        }

        viewModel.startUsageCountdown(
            packageName = "com.example.video",
            appName = "VideoHub",
            durationMillis = duration,
            cooldownMillis = cooldown
        )

        // 1. Advance to trigger cooldown
        simulatedTime += 500L
        advanceTimeBy(500L)
        assertEquals(LockedAppSessionStatus.COOLDOWN, viewModel.uiState.value.status)

        // 2. Advance halfway through cooldown (500ms into cooldown)
        simulatedTime += 500L
        advanceTimeBy(500L)
        val midCooldownState = viewModel.uiState.value
        assertEquals(LockedAppSessionStatus.COOLDOWN, midCooldownState.status)
        assertEquals(500L, midCooldownState.remainingCooldownMillis)
        assertEquals(0.5f, midCooldownState.cooldownProgress, 0.05f)

        // 3. Advance to cooldown completion (another 500ms)
        simulatedTime += 500L
        advanceTimeBy(500L)
        val finalState = viewModel.uiState.value

        assertEquals(LockedAppSessionStatus.LOCKED, finalState.status)
        assertFalse(finalState.isCooldownActive)
        assertEquals(0L, finalState.remainingCooldownMillis)
        assertEquals(1.0f, finalState.cooldownProgress, 0.01f)

        // Verify CooldownFinished event was emitted
        val finishedEvent = eventsList.filterIsInstance<LockedAppEvent.CooldownFinished>().firstOrNull()
        assertNotNull(finishedEvent)
        assertEquals("com.example.video", finishedEvent?.packageName)

        job.cancel()
    }

    @Test
    fun testPauseAndResumeCountdown() = runTest(testDispatcher) {
        val tickerInterval = 100L
        val viewModel = createViewModel(tickerIntervalMillis = tickerInterval)

        viewModel.startUsageCountdown(
            packageName = "com.example.news",
            appName = "NewsApp",
            durationMillis = 2000L,
            cooldownMillis = 1000L
        )

        // Run for 500ms
        simulatedTime += 500L
        advanceTimeBy(500L)
        assertEquals(1500L, viewModel.uiState.value.remainingUsageMillis)

        // Pause
        viewModel.pauseCountdown()
        assertTrue(viewModel.uiState.value.isPaused)

        // Advance time while paused: remaining time should NOT decrement
        simulatedTime += 1000L
        advanceTimeBy(1000L)
        assertEquals(1500L, viewModel.uiState.value.remainingUsageMillis)

        // Resume
        viewModel.resumeCountdown()
        assertFalse(viewModel.uiState.value.isPaused)

        // Advance 500ms after resume
        simulatedTime += 500L
        advanceTimeBy(500L)
        assertEquals(1000L, viewModel.uiState.value.remainingUsageMillis)
    }

    @Test
    fun testStopSessionEarlyLocksAppImmediately() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.startUsageCountdown(
            packageName = "com.example.chat",
            appName = "ChatApp",
            durationMillis = 5000L,
            cooldownMillis = 2000L
        )

        viewModel.stopSession("User requested stop early")
        val state = viewModel.uiState.value

        assertEquals(LockedAppSessionStatus.LOCKED, state.status)
        assertEquals(0L, state.remainingUsageMillis)
        assertFalse(state.isCooldownActive)
    }

    @Test
    fun testCooldownOverrideStrictVsNonStrict() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        // 1. Strict mode active: override rejected
        viewModel.startUsageCountdown(
            packageName = "com.example.strict",
            appName = "StrictApp",
            durationMillis = 500L,
            cooldownMillis = 1000L,
            strictMode = true
        )
        simulatedTime += 500L
        advanceTimeBy(500L)
        assertEquals(LockedAppSessionStatus.COOLDOWN, viewModel.uiState.value.status)

        val overrideStrictResult = viewModel.overrideCooldown(reason = "Emergency")
        assertFalse(overrideStrictResult)
        assertEquals(LockedAppSessionStatus.COOLDOWN, viewModel.uiState.value.status)

        // 2. Non-strict mode: override allowed
        viewModel.setStrictMode(false)
        val overrideAllowedResult = viewModel.overrideCooldown(reason = "Emergency allowed")
        assertTrue(overrideAllowedResult)
        assertEquals(LockedAppSessionStatus.IDLE, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.isCooldownActive)
    }

    @Test
    fun testFormatTime() {
        val viewModel = createViewModel()
        assertEquals("00:00", viewModel.formatTime(0L))
        assertEquals("00:45", viewModel.formatTime(45000L))
        assertEquals("05:30", viewModel.formatTime(330000L))
        assertEquals("01:05:00", viewModel.formatTime(3900000L))
    }
}
