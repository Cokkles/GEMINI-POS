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
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.cokkles.gpos.platform.security.SessionContinuityPolicy
import kotlinx.coroutines.launch

class ParityActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()
    private val parityViewModel: ParityRuntimeViewModel by viewModels()
    private val taskQueueViewModel: TaskQueueViewModel by viewModels()
    private val captureViewModel: CaptureViewModel by viewModels()
    private val notificationCommandViewModel: NotificationCommandViewModel by viewModels()
    private val interactionViewModel: AegisInteractionViewModel by viewModels()
    private val workspaceViewModel: TaskWorkspaceViewModel by viewModels()
    private val notesViewModel: RunningNotesViewModel by viewModels()

    private lateinit var googleSignInCoordinator: GoogleSignInCoordinator
    private lateinit var authContinuity: AuthContinuityPreferences
    private var continuityJob: Job? = null
    private var lastContinuityAttemptAt = 0L

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

        googleSignInCoordinator = GoogleSignInCoordinator(this)
        authContinuity = AuthContinuityPreferences(applicationContext)

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
                workspaceViewModel.activate(runtimeState.auth)
                notesViewModel.activate(runtimeState.auth)
                if (runtimeState.auth is AuthState.Authenticated) {
                    authContinuity.markAuthenticated()
                    attemptAuthorizedSessionContinuity()
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
                        continuityJob?.cancel()
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
                        workspaceViewModel.detach()
                        notesViewModel.detach()
                        authContinuity.clear()
                        continuityJob?.cancel()
                        runtimeViewModel.signOut()
                        lifecycleScope.launch {
                            googleSignInCoordinator.clearProviderState()
                            taskQueueViewModel.clearProtectedLedger()
                            captureViewModel.clearProtectedLedger()
                            interactionViewModel.clearAiChat()
                            interactionViewModel.clearCalendarProposal()
                        }
                    },
                    onNotificationPermission = ::requestNotificationPermission,
                    onRefreshCanonical = {
                        workspaceViewModel.refresh()
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
                            workspaceViewModel.refresh(force = true)
                            runtimeViewModel.refreshDashboard()
                            workspaceViewModel.refresh(force = true)
                            parityViewModel.refreshAll()
                        }
                    },
                    onTaskCreate = { title, notes ->
                        interactionViewModel.createTask(title, notes) {
                            runtimeViewModel.refreshDashboard()
                            workspaceViewModel.refresh(force = true)
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
                            workspaceViewModel.refresh(force = true)
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

    }

    override fun onResume() {
        super.onResume()
        attemptAuthorizedSessionContinuity()
        taskQueueViewModel.refresh()
        workspaceViewModel.activate(runtimeViewModel.uiState.value.auth)
        notesViewModel.activate(runtimeViewModel.uiState.value.auth)
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

    private fun attemptAuthorizedSessionContinuity() {
        if (!authContinuity.wasAuthenticated() || continuityJob?.isActive == true) return
        continuityJob = lifecycleScope.launch {
            // Discovery and AUTH-1 validation each have a 20-second transport deadline.
            // Wait for their result instead of abandoning renewal after three seconds.
            val settled = withTimeoutOrNull(45_000L) {
                runtimeViewModel.uiState.first {
                    it.auth != AuthState.Restoring && it.auth != AuthState.Authenticating
                }
            } ?: return@launch
            val expiry = (settled.auth as? AuthState.Authenticated)?.expiresAtEpochMs
            if (expiry != null) {
                delay((expiry - System.currentTimeMillis() - SessionContinuityPolicy.RENEW_BEFORE_MS).coerceAtLeast(0L))
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val now = System.currentTimeMillis()
            if (now - lastContinuityAttemptAt < 60_000L) return@launch
            if (!SessionContinuityPolicy.shouldRenew(runtimeViewModel.uiState.value.auth, authContinuity.wasAuthenticated(), now)) return@launch
            lastContinuityAttemptAt = now
            runCatching {
                googleSignInCoordinator.requestAuthorizedIdToken(BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID)
            }.onSuccess { token ->
                if (authContinuity.wasAuthenticated()) runtimeViewModel.authenticateWithIdToken(token)
            }
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
