package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.example.MainActivity
import com.example.NotCadApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Play Store Compliant Accessibility Service for Digital Wellbeing & Intentional Friction.
 * 
 * Policy Compliance:
 * 1. Used strictly for user-requested distraction control.
 * 2. Monitors only packages explicitly added by the user to the protected apps list.
 * 3. Does not log keystrokes, personal communications, or inspect screen content.
 * 4. Provides non-destructive navigation (Home redirection & Screen lock on timer expiration).
 */
class NotCadAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName) return // Ignore our own app

        serviceScope.launch {
            checkAndInterceptApp(eventPackage)
        }
    }

    private suspend fun checkAndInterceptApp(foregroundPackage: String) {
        val app = application as? NotCadApp ?: return
        val isProtected = app.database.protectedAppDao().isAppProtected(foregroundPackage)
        if (!isProtected) return

        val prefs = app.preferencesRepository.userPreferencesFlow.first()
        val isSessionActive = FocusVaultService.isSessionActive.value
        val currentSessionPackage = FocusVaultService.currentPackage.value

        val isAuthorized = isSessionActive && currentSessionPackage == foregroundPackage

        if (!isAuthorized) {
            // App was opened directly without passing through NotCaD authentication vault
            // 1. Return to Home
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 2. Open NotCaD auth challenge
            val authIntent = Intent(this@NotCadAccessibilityService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_AUTHENTICATION)
                putExtra(MainActivity.EXTRA_TARGET_PACKAGE, foregroundPackage)
            }
            startActivity(authIntent)
        }
    }

    fun triggerDeviceLockOrHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locked = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (!locked) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        var instance: NotCadAccessibilityService? = null
            private set
    }
}
