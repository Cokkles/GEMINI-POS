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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.platform.notifications.DeepLinkRouter
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.GoogleSignInCoordinator
import com.cokkles.gpos.ui.parity.ParityApp
import com.cokkles.gpos.ui.theme.GposTheme
import com.cokkles.gpos.ui.theme.ThemePreferences
import kotlinx.coroutines.launch

class ParityActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()
    private val parityViewModel: ParityRuntimeViewModel by viewModels()
    private val taskQueueViewModel: TaskQueueViewModel by viewModels()
    private val captureViewModel: CaptureViewModel by viewModels()
    private val calendarCommandViewModel: CalendarCommandViewModel by viewModels()
    private val notificationCommandViewModel: NotificationCommandViewModel by viewModels()

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

        setContent {
            val themePreferences = ThemePreferences(applicationContext)
            var selectedTheme by mutableStateOf(themePreferences.load())
            val runtimeState by runtimeViewModel.uiState.collectAsStateWithLifecycle()
            val parityState by parityViewModel.state.collectAsStateWithLifecycle()
            val taskQueueState by taskQueueViewModel.state.collectAsStateWithLifecycle()
            val captureState by captureViewModel.state.collectAsStateWithLifecycle()
            val calendarCommandState by calendarCommandViewModel.state.collectAsStateWithLifecycle()
            val notificationCommandState by notificationCommandViewModel.state.collectAsStateWithLifecycle()

            GposTheme(selectedTheme) {
                ParityApp(
                    runtimeState = runtimeState,
                    parityState = parityState,
                    taskQueueState = taskQueueState,
                    captureState = captureState,
                    calendarCommandState = calendarCommandState,
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
                            googleSignInCoordinator.clearProviderState()
                            taskQueueViewModel.clearProtectedLedger()
                            captureViewModel.clearProtectedLedger()
                            runtimeViewModel.signOut()
                        }
                    },
                    onNotificationPermission = ::requestNotificationPermission,
                    onRefreshCanonical = {
                        runtimeViewModel.refreshCanonicalReads()
                        parityViewModel.refreshAll()
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
                    onCaptureSubmit = captureViewModel::submit,
                    onLocalAlertAck = captureViewModel::acknowledgeLocalAlert,
                    onCalendarResolve = calendarCommandViewModel::resolve,
                    onCalendarCancel = calendarCommandViewModel::cancelProposal,
                    onCalendarCreate = {
                        calendarCommandViewModel.createResolved {
                            runtimeViewModel.refreshDashboard()
                            parityViewModel.refreshCalendar()
                        }
                    },
                    onServerNotificationAck = { id ->
                        notificationCommandViewModel.acknowledge(
                            id,
                            runtimeViewModel::refreshNotifications,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskQueueViewModel.refresh()
        captureViewModel.refreshLedger()
        if (runtimeViewModel.uiState.value.auth is AuthState.Authenticated) {
            parityViewModel.refreshAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinkRouter.resolve(intent.data)
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
