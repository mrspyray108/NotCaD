package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CustomKeypad
import com.example.ui.components.PinDotsDisplay
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.RedAlert
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PinSetupScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val setupPinFirst by viewModel.setupPinFirst.collectAsState()
    val enteredPin by viewModel.enteredPin.collectAsState()
    val isConfirming by viewModel.isConfirmingPin.collectAsState()
    val authError by viewModel.authError.collectAsState()

    val currentLength = if (isConfirming) enteredPin.length else setupPinFirst.length

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(24.dp)
            .testTag("pin_setup_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Text(
                text = if (isConfirming) "Confirm Security PIN" else "Create Master PIN",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isConfirming) "Re-enter your 4-digit PIN to confirm" else "This PIN secures access to your distracting applications",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            PinDotsDisplay(
                pinLength = currentLength,
                maxDigits = 4,
                hasError = authError != null
            )

            if (authError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = authError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = RedAlert,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        CustomKeypad(
            onDigitClick = { digit -> viewModel.onSetupPinDigitEntered(digit) },
            onBackspaceClick = { viewModel.onSetupPinBackspace() },
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}
