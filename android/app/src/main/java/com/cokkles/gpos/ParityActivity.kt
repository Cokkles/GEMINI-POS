package com.cokkles.gpos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.platform.notifications.DeepLinkRouter
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AuthContinuityPreferences
import com.cokkles.gpos.platform.security.GoogleSignInCoordinator
import com.cokkles.gpos.ui.daily.DailyUxApp
import com.cokkles.gpos.ui.theme.GposTheme
import com.cokkles.gpos.ui.theme.ThemePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ParityActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()
    private val parityViewModel: ParityRuntimeViewModel by viewModels()
    private val taskQueueViewModel: TaskQueueViewModel by viewModels()
    private val captureViewModel: CaptureViewModel by viewModels()
    private val notificationCommandViewModel: NotificationCommandViewModel by viewModels()
    private val interactionViewModel: AegisInteractionViewModel by viewModels()

    private var pendingDeepLink by mutableStateOf<GposDeepLinkTarget?>(null)
    private var notificationPermissionGranted by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        if (granted) GposNotificationPublisher(this).ensureChannels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink = DeepLinkRouter.resolve(intent?.data)
        notificationPermissionGranted = hasNotificationPermission()
        GposNotificationPublisher(this).ensureChannels()

        val googleSignInCoordinator = GoogleSignInCoordinator(this)
        val authContinuity = AuthContinuityPreferences(applicationContext)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            var selectedTheme by remember { mutableStateOf(themePreferences.load()) }
            val runtimeState by runtimeViewModel.uiState.collectAsStateWithLifecycle()
            val parityState by parityViewModel.state.collectAsStateWithLifecycle()
            val taskQueueState by taskQueueViewModel.state.collectAsStateWithLifecycle()
            val captureState by captureViewModel.state.collectAsStateWithLifecycle()
            val notificationCommandState by notificationCommandViewModel.state.collectAsStateWithLifecycle()
            val interactionState by interactionViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(runtimeState.auth) {
                if (runtimeState.auth is AuthState.Authenticated) {
                    authContinuity.markAuthenticated()
                }
            }

            GposTheme(selectedTheme) {
                DailyUxApp(
                    runtimeState = runtimeState,
                    parityState = parityState,
                    taskQueueState = taskQueueState,
                    captureState = captureState,
                    interactionState = interactionState,
                    notificationCommandState = notificationCommandState,
                    selectedTheme = selectedTheme,
                    deepLinkTarget = pendingDeepLink,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                    onThemeSelected = { option ->
                        selectedTheme = option
                        themePreferences.save(option)
                    },
                    onSignIn = {
                        lifecycleScope.launch {
                            runCatching {
                                googleSignInCoordinator.requestIdToken(BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID)
                            }.onSuccess(runtimeViewModel::authenticateWithIdToken)
                                .onFailure { error ->
                                    runtimeViewModel.reportAuthFailure(
                                        error.message ?: "Google Sign-In could not be completed.",
                                    )
                                }
                        }
                    },
                    onSignOut = {
                        lifecycleScope.launch {
                            authContinuity.clear()
                            googleSignInCoordinator.clearProviderState()
                            taskQueueViewModel.clearProtectedLedger()
                            captureViewModel.clearProtectedLedger()
                            interactionViewModel.clearAiChat()
                            interactionViewModel.clearCalendarProposal()
                            runtimeViewModel.signOut()
                        }
                    },
                    onNotificationPermission = ::requestNotificationPermission,
                    onRefreshCanonical = {
                        runtimeViewModel.refreshCanonicalReads()
                        parityViewModel.refreshAll()
                        interactionViewModel.refreshCapabilitiesAndFollowups()
                        taskQueueViewModel.refresh()
                        captureViewModel.refreshLedger()
                    },
                    onRefreshBackend = runtimeViewModel::refreshBackendAndRestoreSession,
                    onParityRefresh = parityViewModel::refreshAll,
                    onShiftMonth = parityViewModel::shiftMonth,
                    onTodayMonth = parityViewModel::goToToday,
                    onSelectDate = parityViewModel::selectDate,
                    onRefreshCalendar = parityViewModel::refreshCalendar,
                    onRefreshIntelligence = parityViewModel::refreshIntelligence,
                    onNutritionDays = parityViewModel::setNutritionDays,
                    onTaskHistoryDays = parityViewModel::setTaskHistoryDays,
                    onTaskStage = taskQueueViewModel::stage,
                    onTaskUndo = taskQueueViewModel::undo,
                    onTaskSyncNow = {
                        taskQueueViewModel.syncNow {
                            runtimeViewModel.refreshDashboard()
                            parityViewModel.refreshAll()
                        }
                    },
                    onTaskCreate = { title, notes ->
                        interactionViewModel.createTask(title, notes) {
                            runtimeViewModel.refreshDashboard()
                            parityViewModel.refreshAll()
                        }
                    },
                    onCaptureSubmit = captureViewModel::submit,
                    onLocalAlertAck = captureViewModel::acknowledgeLocalAlert,
                    onServerNotificationAck = { id ->
                        notificationCommandViewModel.acknowledge(
                            id,
                            runtimeViewModel::refreshNotifications,
                        )
                    },
                    onInteractionRefresh = interactionViewModel::refreshCapabilitiesAndFollowups,
                    onFollowupResolve = interactionViewModel::resolveFollowup,
                    onFollowupDismiss = interactionViewModel::dismissFollowup,
                    onFollowupPromote = { followup ->
                        interactionViewModel.promoteFollowup(followup) {
                            runtimeViewModel.refreshDashboard()
                            parityViewModel.refreshAll()
                        }
                    },
                    onAiMode = interactionViewModel::setAiMode,
                    onAskAegis = interactionViewModel::askAegis,
                    onClearAiChat = interactionViewModel::clearAiChat,
                    onCalendarAsk = interactionViewModel::prepareCalendar,
                    onCalendarConfirm = {
                        interactionViewModel.confirmCalendar {
                            runtimeViewModel.refreshDashboard()
                            parityViewModel.refreshCalendar()
                        }
                    },
                    onCalendarCancel = interactionViewModel::clearCalendarProposal,
                )
            }
        }

        attemptAuthorizedSessionContinuity(googleSignInCoordinator, authContinuity)
    }

    override fun onResume() {
        super.onResume()
        taskQueueViewModel.refresh()
        captureViewModel.refreshLedger()
        if (runtimeViewModel.uiState.value.auth is AuthState.Authenticated) {
            parityViewModel.refreshAll()
            interactionViewModel.refreshCapabilitiesAndFollowups()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinkRouter.resolve(intent.data)
    }

    private fun attemptAuthorizedSessionContinuity(
        googleSignInCoordinator: GoogleSignInCoordinator,
        authContinuity: AuthContinuityPreferences,
    ) {
        if (!authContinuity.wasAuthenticated()) return
        lifecycleScope.launch {
            // Give the encrypted stored-token restoration path first opportunity to validate.
            var attempts = 0
            while (attempts < 12) {
                val auth = runtimeViewModel.uiState.value.auth
                if (auth != AuthState.Restoring && auth != AuthState.Authenticating) break
                delay(250)
                attempts++
            }
            if (runtimeViewModel.uiState.value.auth !is AuthState.SignedOut) return@launch

            // Best effort only. Failure leaves the ordinary Sign in control available and must not
            // turn a previously valid session into a noisy authentication error.
            runCatching {
                googleSignInCoordinator.requestAuthorizedIdToken(BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID)
            }.onSuccess(runtimeViewModel::authenticateWithIdToken)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted = true
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
}
