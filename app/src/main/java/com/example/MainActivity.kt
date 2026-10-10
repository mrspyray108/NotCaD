package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.ui.screens.ActiveSessionScreen
import com.example.ui.screens.AuthenticationScreen
import com.example.ui.screens.CooldownScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PermissionSetupScreen
import com.example.ui.screens.PinSetupScreen
import com.example.ui.screens.ProtectedAppsScreen
import com.example.ui.screens.SessionFinishedScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TimerSelectionScreen
import com.example.ui.screens.UsageHistoryScreen
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.LockedAppUsageViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.Screen
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val lockedAppUsageViewModel: LockedAppUsageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val currentScreen by viewModel.currentScreen.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.toastEvent.collectLatest { message ->
                        snackbarHostState.showSnackbar(message)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = BackgroundDark
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(BackgroundDark)
                    ) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "screen_navigation"
                        ) { screen ->
                            when (screen) {
                                is Screen.Splash -> SplashScreen(viewModel = viewModel)
                                is Screen.Onboarding -> OnboardingScreen(viewModel = viewModel)
                                is Screen.PermissionSetup -> PermissionSetupScreen(viewModel = viewModel)
                                is Screen.PinSetup -> PinSetupScreen(viewModel = viewModel)
                                is Screen.Dashboard -> DashboardScreen(viewModel = viewModel)
                                is Screen.ProtectedApps -> ProtectedAppsScreen(viewModel = viewModel)
                                is Screen.Authentication -> AuthenticationScreen(viewModel = viewModel)
                                is Screen.TimerSelection -> TimerSelectionScreen(viewModel = viewModel)
                                is Screen.ActiveSession -> ActiveSessionScreen(viewModel = viewModel)
                                is Screen.SessionFinished -> SessionFinishedScreen(
                                    viewModel = viewModel,
                                    expiredAppName = intent?.getStringExtra(EXTRA_EXPIRED_APP_NAME) ?: "Application"
                                )
                                is Screen.Cooldown -> CooldownScreen(
                                    viewModel = lockedAppUsageViewModel,
                                    onNavigateDashboard = { viewModel.navigateTo(Screen.Dashboard) }
                                )
                                is Screen.Settings -> SettingsScreen(viewModel = viewModel)
                                is Screen.UsageHistory -> UsageHistoryScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_ROUTE)) {
            ROUTE_COOLDOWN -> {
                viewModel.navigateTo(Screen.Cooldown)
            }
            ROUTE_SESSION_FINISHED -> {
                viewModel.navigateTo(Screen.SessionFinished)
            }
            ROUTE_ACTIVE_SESSION -> {
                viewModel.navigateTo(Screen.ActiveSession)
            }
            ROUTE_AUTHENTICATION -> {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                if (!targetPkg.isNullOrEmpty()) {
                    val appInfo = viewModel.installedApps.value.find { it.packageName == targetPkg }
                    if (appInfo != null) {
                        viewModel.initiateAppLaunch(appInfo)
                    } else {
                        viewModel.navigateTo(Screen.Authentication)
                    }
                } else {
                    viewModel.navigateTo(Screen.Authentication)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "extra_route"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_EXPIRED_APP_NAME = "extra_expired_app_name"

        const val ROUTE_COOLDOWN = "route_cooldown"
        const val ROUTE_SESSION_FINISHED = "route_session_finished"
        const val ROUTE_ACTIVE_SESSION = "route_active_session"
        const val ROUTE_AUTHENTICATION = "route_authentication"
    }
}
