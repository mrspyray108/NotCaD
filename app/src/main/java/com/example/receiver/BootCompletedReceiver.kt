package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.NotCadApp
import com.example.service.FocusVaultService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? NotCadApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = app.preferencesRepository.userPreferencesFlow.first()
            val now = System.currentTimeMillis()

            if (prefs.activeSessionExpiration > now && !prefs.activeSessionPackage.isNullOrEmpty()) {
                // Resume remaining session
                FocusVaultService.startSession(
                    context = context,
                    sessionId = prefs.activeSessionId,
                    packageName = prefs.activeSessionPackage,
                    appName = prefs.activeSessionAppName ?: "Protected App",
                    expirationTimeMillis = prefs.activeSessionExpiration,
                    durationMinutes = prefs.activeSessionDuration
                )
            } else if (prefs.activeSessionExpiration in 1..now) {
                // Session expired while device was powered down
                app.focusRepository.completeActiveSession(prefs.activeSessionId, earlyTerminated = false)
            }
        }
    }
}
