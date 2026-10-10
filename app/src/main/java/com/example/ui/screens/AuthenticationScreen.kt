package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIconImage
import com.example.ui.components.CustomKeypad
import com.example.ui.components.PinDotsDisplay
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedAlert
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AuthMode
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.Screen

@Composable
fun AuthenticationScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val targetApp by viewModel.targetApp.collectAsState()
    val enteredPin by viewModel.enteredPin.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val authMode by viewModel.authMode.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val userPrefs by viewModel.userPreferences.collectAsState()

    val headerTitle = when (authMode) {
        AuthMode.UNLOCK_APP -> "Protected Application"
        AuthMode.ACCESS_SETTINGS -> "Security Verification"
        AuthMode.END_SESSION_EARLY -> "End Session Early"
        AuthMode.MODIFY_PROTECTION -> "Modify Vault"
        AuthMode.EMERGENCY_OVERRIDE -> "Emergency Unlock"
    }

    val headerSubtitle = when (authMode) {
        AuthMode.UNLOCK_APP -> "Intentional Friction Checkpoint"
        AuthMode.ACCESS_SETTINGS -> "Enter PIN to access security configurations"
        AuthMode.END_SESSION_EARLY -> "Enter PIN to confirm intentional early exit"
        AuthMode.MODIFY_PROTECTION -> "Enter PIN to modify protected apps"
        AuthMode.EMERGENCY_OVERRIDE -> "Enter emergency PIN code"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(24.dp)
            .testTag("authentication_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Cancel / Back Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(
                    onClick = { viewModel.navigateTo(Screen.Dashboard) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceVariantDark)
                        .testTag("auth_cancel_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancel",
                        tint = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Icon or Shield Icon
            if (authMode == AuthMode.UNLOCK_APP && targetApp != null) {
                AppIconImage(
                    drawable = targetApp?.icon,
                    contentDescription = targetApp?.appName ?: "Target App",
                    modifier = Modifier.size(64.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (authMode == AuthMode.UNLOCK_APP && targetApp != null) targetApp!!.appName else headerTitle,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = headerSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (lockoutSeconds > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(RedAlert.copy(alpha = 0.2f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = RedAlert, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Too many attempts. Lockout: ${lockoutSeconds}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = RedAlert,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                PinDotsDisplay(
                    pinLength = enteredPin.length,
                    maxDigits = 4,
                    hasError = authError != null
                )
            }

            if (authError != null && lockoutSeconds == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = authError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = RedAlert,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            CustomKeypad(
                onDigitClick = { digit -> viewModel.onPinDigitEntered(digit) },
                onBackspaceClick = { viewModel.onPinBackspace() },
                onBiometricClick = if (userPrefs?.biometricEnabled == true) {
                    { /* Biometric handled if available */ }
                } else null,
                enabled = lockoutSeconds == 0,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (userPrefs?.emergencyUnlockEnabled == true && authMode != AuthMode.EMERGENCY_OVERRIDE) {
                TextButton(
                    onClick = {
                        // Switch to emergency unlock
                    }
                ) {
                    Text(
                        text = "Emergency Unlock Enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
