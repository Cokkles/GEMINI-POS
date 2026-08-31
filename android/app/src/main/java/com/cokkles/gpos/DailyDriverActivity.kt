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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cokkles.gpos.data.command.CalendarCommandProgress
import com.cokkles.gpos.data.command.CalendarCommandRuntimeState
import com.cokkles.gpos.data.command.CommandProgress
import com.cokkles.gpos.data.command.NotificationCommandRuntimeState
import com.cokkles.gpos.data.command.TaskCommandRuntimeState
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.DashboardEvent
import com.cokkles.gpos.data.remote.DashboardRuntimeState
import com.cokkles.gpos.data.remote.DashboardTask
import com.cokkles.gpos.data.remote.FinanceRuntimeState
import com.cokkles.gpos.data.remote.FinanceTransaction
import com.cokkles.gpos.data.remote.NotificationSeverity
import com.cokkles.gpos.data.remote.NotificationsRuntimeState
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.data.remote.ServerNotification
import com.cokkles.gpos.platform.connectivity.AndroidConnectivityObserver
import com.cokkles.gpos.platform.connectivity.ConnectivityState
import com.cokkles.gpos.platform.notifications.DeepLinkRouter
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.GoogleSignInCoordinator
import com.cokkles.gpos.platform.sync.CanonicalSyncScheduler
import com.cokkles.gpos.ui.briefing.HorizonDocumentParser
import com.cokkles.gpos.ui.home.quoteFor
import com.cokkles.gpos.ui.theme.GposTheme
import com.cokkles.gpos.ui.theme.GposThemeOption
import com.cokkles.gpos.ui.theme.ThemePreferences
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class DailyDriverActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()
    private val taskCommandViewModel: TaskCommandViewModel by viewModels()
    private val calendarCommandViewModel: CalendarCommandViewModel by viewModels()
    private val notificationCommandViewModel: NotificationCommandViewModel by viewModels()
    private val pendingDeepLink = mutableStateOf<GposDeepLinkTarget?>(null)
    private val notificationPermissionGranted = mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted.value = granted
        if (granted) GposNotificationPublisher(this).ensureChannels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink.value = DeepLinkRouter.resolve(intent?.data)
        notificationPermissionGranted.value = hasNotificationPermission()
        GposNotificationPublisher(this).ensureChannels()
        val googleSignInCoordinator = GoogleSignInCoordinator(this)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            var selectedTheme by remember { mutableStateOf(themePreferences.load()) }
            val runtimeState by runtimeViewModel.uiState.collectAsStateWithLifecycle()
            val taskCommandState by taskCommandViewModel.state.collectAsStateWithLifecycle()
            val calendarCommandState by calendarCommandViewModel.state.collectAsStateWithLifecycle()
            val notificationCommandState by notificationCommandViewModel.state.collectAsStateWithLifecycle()

            GposTheme(selectedTheme) {
                DailyDriverApp(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { option ->
                        selectedTheme = option
                        themePreferences.save(option)
                    },
                    runtimeState = runtimeState,
                    taskCommandState = taskCommandState,
                    calendarCommandState = calendarCommandState,
                    notificationCommandState = notificationCommandState,
                    deepLinkTarget = pendingDeepLink.value,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
                    notificationPermissionGranted = notificationPermissionGranted.value,
                    onNotificationPermissionRequested = ::requestNotificationPermission,
                    onSignInRequested = {
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
                    onSignOutRequested = {
                        lifecycleScope.launch {
                            googleSignInCoordinator.clearProviderState()
                            runtimeViewModel.signOut()
                            taskCommandViewModel.clearStaged()
                            calendarCommandViewModel.cancelProposal()
                            notificationCommandViewModel.clearMessage()
                        }
                    },
                    onRefreshBackend = runtimeViewModel::refreshBackendAndRestoreSession,
                    onRefreshCanonical = runtimeViewModel::refreshCanonicalReads,
                    onTaskStaged = taskCommandViewModel::setStaged,
                    onTaskClear = taskCommandViewModel::clearStaged,
                    onTaskApply = {
                        taskCommandViewModel.completeStaged(runtimeViewModel::refreshCanonicalReads)
                    },
                    onCalendarResolve = calendarCommandViewModel::resolve,
                    onCalendarCancel = calendarCommandViewModel::cancelProposal,
                    onCalendarCreate = {
                        calendarCommandViewModel.createResolved(runtimeViewModel::refreshCanonicalReads)
                    },
                    onNotificationAcknowledge = { id ->
                        notificationCommandViewModel.acknowledge(
                            id,
                            runtimeViewModel::refreshCanonicalReads,
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = DeepLinkRouter.resolve(intent.data)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted.value = true
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

private data class DailyDestination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val todayDestination = DailyDestination("home", "Today", "Run your day from one screen", Icons.Outlined.Home)
private val briefingDestination = DailyDestination("briefing", "Briefing", "Canonical HORIZON", Icons.Outlined.Description)
private val calendarDestination = DailyDestination("calendar", "Calendar", "Agenda + event creation", Icons.Outlined.Event)
private val tasksDestination = DailyDestination("tasks", "Tasks", "Review + complete tasks", Icons.Outlined.CheckCircle)
private val moreDestination = DailyDestination("more", "More", "Search and GPOS areas", Icons.Outlined.MoreHoriz)
private val searchDestination = DailyDestination("search", "Search", "Search locally synced GPOS data", Icons.Outlined.Search)
private val notificationsDestination = DailyDestination("notifications", "Notifications", "Server alerts", Icons.Outlined.Notifications)
private val financesDestination = DailyDestination("finances", "Finances", "Recent SENTINEL-FIN activity", Icons.Outlined.AccountBalanceWallet)
private val insightsDestination = DailyDestination("insights", "Insights", "HORIZON news and strategy", Icons.Outlined.Lightbulb)
private val followupsDestination = DailyDestination("followups", "Follow-ups", "Canonical contract pending", Icons.Outlined.Notifications)
private val aegisDestination = DailyDestination("aegis", "Ask AEGIS", "Conversation contract pending", Icons.Outlined.Forum)
private val systemDestination = DailyDestination("system", "System", "Sync, auth and appearance", Icons.Outlined.Settings)

private val bottomDestinations = listOf(
    todayDestination,
    briefingDestination,
    calendarDestination,
    tasksDestination,
    moreDestination,
)
private val moreDestinations = listOf(
    searchDestination,
    notificationsDestination,
    financesDestination,
    insightsDestination,
    followupsDestination,
    aegisDestination,
    systemDestination,
)
private val allDailyDestinations = bottomDestinations + moreDestinations

private val dailyDateTimeFormatter = DateTimeFormatter.ofPattern("MMM d • h:mm a")
    .withZone(ZoneId.systemDefault())
private val dailyCurrencyFormatter: NumberFormat = NumberFormat.getCurrencyInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyDriverApp(
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    runtimeState: RuntimeUiState,
    taskCommandState: TaskCommandRuntimeState,
    calendarCommandState: CalendarCommandRuntimeState,
    notificationCommandState: NotificationCommandRuntimeState,
    deepLinkTarget: GposDeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    notificationPermissionGranted: Boolean,
    onNotificationPermissionRequested: () -> Unit,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshCanonical: () -> Unit,
    onTaskStaged: (String?, Boolean) -> Unit,
    onTaskClear: () -> Unit,
    onTaskApply: () -> Unit,
    onCalendarResolve: (String) -> Unit,
    onCalendarCancel: () -> Unit,
    onCalendarCreate: () -> Unit,
    onNotificationAcknowledge: (String) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: todayDestination.route
    val currentDestination = allDailyDestinations.firstOrNull { it.route == currentRoute } ?: todayDestination
    val selectedBottomRoute = if (moreDestinations.any { it.route == currentRoute }) moreDestination.route else currentRoute
    val canMutate = runtimeState.auth is AuthState.Authenticated

    fun navigate(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(deepLinkTarget) {
        deepLinkTarget?.let { target ->
            navigate(target.route)
            onDeepLinkConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPOS")
                        Text(currentDestination.title, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { navigate(searchDestination.route) }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search GPOS")
                    }
                    IconButton(onClick = onRefreshCanonical) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh canonical data")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedBottomRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.title) },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = todayDestination.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(todayDestination.route) {
                TodayScreen(
                    state = runtimeState,
                    onNavigate = ::navigate,
                    onRefresh = onRefreshCanonical,
                )
            }
            composable(briefingDestination.route) {
                DailyBriefingScreen(runtimeState, onRefreshCanonical)
            }
            composable(calendarDestination.route) {
                DailyCalendarScreen(
                    dashboard = runtimeState.dashboard,
                    commandState = calendarCommandState,
                    canMutate = canMutate,
                    onResolve = onCalendarResolve,
                    onCancel = onCalendarCancel,
                    onCreate = onCalendarCreate,
                    onRefresh = onRefreshCanonical,
                )
            }
            composable(tasksDestination.route) {
                DailyTasksScreen(
                    dashboard = runtimeState.dashboard,
                    commandState = taskCommandState,
                    canMutate = canMutate,
                    onTaskStaged = onTaskStaged,
                    onClear = onTaskClear,
                    onApply = onTaskApply,
                    onRefresh = onRefreshCanonical,
                )
            }
            composable(moreDestination.route) {
                DailyMoreScreen(onNavigate = ::navigate)
            }
            composable(searchDestination.route) {
                GlobalSearchScreen(runtimeState, onNavigate = ::navigate)
            }
            composable(notificationsDestination.route) {
                DailyNotificationsScreen(
                    state = runtimeState.notifications,
                    commandState = notificationCommandState,
                    canMutate = canMutate,
                    onAcknowledge = onNotificationAcknowledge,
                    onRefresh = onRefreshCanonical,
                )
            }
            composable(financesDestination.route) {
                DailyFinanceScreen(runtimeState.finance, onRefreshCanonical)
            }
            composable(insightsDestination.route) {
                DailyInsightsScreen(runtimeState)
            }
            composable(followupsDestination.route) {
                GatedDailyScreen(
                    title = "Follow-ups",
                    detail = "The Android client is ready for a bounded Follow-ups contract, but no dedicated canonical contract has been proven yet. This phase does not manufacture follow-ups from prose or Gmail heuristics.",
                )
            }
            composable(aegisDestination.route) {
                GatedDailyScreen(
                    title = "Ask AEGIS",
                    detail = "The conversation transport remains gated until mobile cost, retention, authentication and mutation semantics are reviewed. Existing deterministic GPOS actions remain available elsewhere.",
                )
            }
            composable(systemDestination.route) {
                DailySystemScreen(
                    state = runtimeState,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onNotificationPermissionRequested = onNotificationPermissionRequested,
                    onSignInRequested = onSignInRequested,
                    onSignOutRequested = onSignOutRequested,
                    onRefreshBackend = onRefreshBackend,
                    onRefreshCanonical = onRefreshCanonical,
                )
            }
        }
    }
}

@Composable
private fun TodayScreen(
    state: RuntimeUiState,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val dashboard = state.dashboard
    val quote = remember { quoteFor(LocalDate.now()) }
    val activeAlerts = state.notifications?.snapshot?.active.orEmpty()
    val finance = state.finance?.snapshot
    val briefingText = state.briefing?.plainText ?: dashboard?.snapshot?.briefingPlainText
    val nextEvent = dashboard?.snapshot?.todayEvents?.firstOrNull()
        ?: dashboard?.snapshot?.tomorrowEvents?.firstOrNull()

    DailyList {
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                when (val auth = state.auth) {
                    is AuthState.Authenticated -> "Connected as ${auth.user.name ?: auth.user.email}"
                    is AuthState.OfflineRestored -> "Offline session • cached data available"
                    else -> "GPOS Android ${BuildConfig.VERSION_NAME}"
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Daily inspiration", style = MaterialTheme.typography.labelLarge)
                    Text("“${quote.text}”", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                    quote.attribution?.let { Text("— $it", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DailyStatusPill("Dashboard", state.dashboard?.source)
                DailyStatusPill("HORIZON", state.briefing?.source)
                DailyStatusPill("Finance", state.finance?.source)
            }
        }
        item {
            Card(onClick = { onNavigate(calendarDestination.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Next up", style = MaterialTheme.typography.labelLarge)
                    Text(nextEvent?.title ?: "Calendar clear", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge)
                    Text(
                        nextEvent?.let { "${if (it.day.name == "TODAY") "Today" else "Tomorrow"} • ${it.timeLabel}${it.note?.let { note -> " • $note" }.orEmpty()}" }
                            ?: "No today/tomorrow events were returned.",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Text("Today’s agenda", style = MaterialTheme.typography.titleLarge)
        }
        if (dashboard?.snapshot?.todayEvents.isNullOrEmpty()) {
            item { DailySummaryCard("No events today", "Your canonical dashboard returned a clear schedule.") }
        } else {
            items(dashboard!!.snapshot.todayEvents.take(4)) { event ->
                DailyEventCard(event)
            }
        }
        item {
            Card(onClick = { onNavigate(tasksDestination.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    val tasks = dashboard?.snapshot?.tasks.orEmpty()
                    Text("Tasks • ${tasks.size} active", style = MaterialTheme.typography.titleMedium)
                    if (tasks.isEmpty()) {
                        Text("No active Google Tasks returned.", modifier = Modifier.padding(top = 6.dp))
                    } else {
                        tasks.take(4).forEach { task ->
                            Text("• ${task.title}${task.timeLabel?.let { " • $it" }.orEmpty()}", modifier = Modifier.padding(top = 7.dp))
                        }
                        if (tasks.size > 4) Text("+ ${tasks.size - 4} more", modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
        item {
            Card(onClick = { onNavigate(briefingDestination.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("HORIZON", style = MaterialTheme.typography.titleMedium)
                    Text(
                        briefingText?.lineSequence()?.map(String::trim)?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                            ?: "No canonical briefing loaded yet.",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    dashboard?.snapshot?.horizonLastSuccessAtEpochMs?.let {
                        Text("Generated ${dailyFormatTime(it)}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card(onClick = { onNavigate(notificationsDestination.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Attention", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            activeAlerts.any { it.severity == NotificationSeverity.CRITICAL } -> "${activeAlerts.size} active alert(s) • critical attention present"
                            activeAlerts.isNotEmpty() -> "${activeAlerts.size} active server alert(s)"
                            else -> "No active server alerts"
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Card(onClick = { onNavigate(financesDestination.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Finances • last ${finance?.hours ?: 72}h", style = MaterialTheme.typography.titleMedium)
                    Text(
                        finance?.summary?.purchaseTotal?.let { "Purchases ${dailyCurrencyFormatter.format(it)}" }
                            ?: "Recent finance data not loaded",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    finance?.summary?.creditTotal?.let { Text("Credits ${dailyCurrencyFormatter.format(it)}", modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
        }
    }
}

@Composable
private fun DailyBriefingScreen(state: RuntimeUiState, onRefresh: () -> Unit) {
    val briefing = state.briefing
    val raw = briefing?.plainText ?: state.dashboard?.snapshot?.briefingPlainText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }
    var collapsed by remember(raw) { mutableStateOf(setOf<String>()) }

    DailyList {
        item { DailySourceCard("HORIZON", briefing?.source ?: state.dashboard?.source, briefing?.fetchedAtEpochMs ?: state.dashboard?.fetchedAtEpochMs, briefing?.error) }
        if (raw.isNullOrBlank() || document == null) {
            item { DailySummaryCard("No briefing loaded", dailyAuthHint(state.auth)) }
        } else {
            item {
                Text(document.title ?: "Daily Executive Briefing", style = MaterialTheme.typography.headlineMedium)
                Text("Tap a section to collapse or expand it.", modifier = Modifier.padding(top = 4.dp))
            }
            if (document.preamble.isNotEmpty()) {
                item { DailyBriefingTextCard("Overview", document.preamble) }
            }
            items(document.sections) { section ->
                val isCollapsed = section.title in collapsed
                Card(
                    onClick = {
                        collapsed = if (isCollapsed) collapsed - section.title else collapsed + section.title
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (isCollapsed) "Tap to expand" else "Tap to collapse", modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                        if (!isCollapsed) {
                            section.lines.map(HorizonDocumentParser::displayLine).filter(String::isNotBlank).forEach {
                                Text("• $it", modifier = Modifier.padding(top = 8.dp))
                            }
                            section.subsections.forEach { subsection ->
                                Text(subsection.title, modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
                                subsection.lines.map(HorizonDocumentParser::displayLine).filter(String::isNotBlank).forEach {
                                    Text("• $it", modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh briefing") } }
    }
}

@Composable
private fun DailyCalendarScreen(
    dashboard: DashboardRuntimeState?,
    commandState: CalendarCommandRuntimeState,
    canMutate: Boolean,
    onResolve: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    var eventText by remember { mutableStateOf("") }

    DailyList {
        item { DailySourceCard("Calendar", dashboard?.source, dashboard?.fetchedAtEpochMs, dashboard?.error) }
        item { Text("Agenda", style = MaterialTheme.typography.headlineMedium) }
        if (dashboard == null) {
            item { DailySummaryCard("Calendar not loaded", "Authenticate and sync canonical data.") }
        } else {
            item { Text("Today • ${dashboard.snapshot.todayEvents.size}", style = MaterialTheme.typography.titleLarge) }
            if (dashboard.snapshot.todayEvents.isEmpty()) item { DailySummaryCard("No events today", "Schedule is clear.") }
            else items(dashboard.snapshot.todayEvents) { DailyEventCard(it) }

            item { Text("Tomorrow • ${dashboard.snapshot.tomorrowEvents.size}", style = MaterialTheme.typography.titleLarge) }
            if (dashboard.snapshot.tomorrowEvents.isEmpty()) item { DailySummaryCard("No events tomorrow", "No events returned for tomorrow.") }
            else items(dashboard.snapshot.tomorrowEvents) { DailyEventCard(it) }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Quick add", style = MaterialTheme.typography.titleMedium)
                    Text("Describe an event, review the resolved proposal, then explicitly add it.", modifier = Modifier.padding(top = 5.dp))
                    OutlinedTextField(
                        value = eventText,
                        onValueChange = { eventText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        label = { Text("Event description") },
                        enabled = canMutate && commandState.progress !in setOf(CalendarCommandProgress.RESOLVING, CalendarCommandProgress.CREATING),
                    )
                    Button(
                        onClick = { onResolve(eventText) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = canMutate && eventText.isNotBlank() && commandState.progress !in setOf(CalendarCommandProgress.RESOLVING, CalendarCommandProgress.CREATING),
                    ) { Text(if (commandState.progress == CalendarCommandProgress.RESOLVING) "Resolving…" else "Resolve & preview") }
                    if (!canMutate) Text("Live authenticated session required for creation.", modifier = Modifier.padding(top = 8.dp))
                    commandState.error?.let { Text("Error: $it", modifier = Modifier.padding(top = 8.dp)) }
                    commandState.lastMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
        commandState.proposal?.let { proposal ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Review before creating", style = MaterialTheme.typography.titleMedium)
                        Text(proposal.title, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                        Text("${proposal.start} → ${proposal.end}", modifier = Modifier.padding(top = 5.dp))
                        proposal.location?.let { Text("Location • $it", modifier = Modifier.padding(top = 5.dp)) }
                        proposal.description?.let { Text(it, modifier = Modifier.padding(top = 5.dp)) }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Cancel proposal") }
                        Button(
                            onClick = onCreate,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            enabled = canMutate && commandState.canCreate,
                        ) { Text(if (commandState.progress == CalendarCommandProgress.CREATING) "Adding…" else "Add to Google Calendar") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh calendar") } }
    }
}

@Composable
private fun DailyTasksScreen(
    dashboard: DashboardRuntimeState?,
    commandState: TaskCommandRuntimeState,
    canMutate: Boolean,
    onTaskStaged: (String?, Boolean) -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showConfirmation by remember { mutableStateOf(false) }
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { if (commandState.progress == CommandProgress.IDLE) showConfirmation = false },
            title = { Text("Complete selected tasks?") },
            text = { Text("${commandState.stagedCanonicalIds.size} task(s) will be submitted to canonical Google Tasks and then verified with a fresh read.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        onApply()
                    },
                    enabled = commandState.canApply,
                ) { Text("Complete") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("Cancel") } },
        )
    }

    val tasks = dashboard?.snapshot?.tasks.orEmpty()
    val scheduled = tasks.filter { !it.timeLabel.isNullOrBlank() }
    val unscheduled = tasks.filter { it.timeLabel.isNullOrBlank() }

    DailyList {
        item { DailySourceCard("Tasks", dashboard?.source, dashboard?.fetchedAtEpochMs, dashboard?.error) }
        item {
            Text("Active tasks • ${tasks.size}", style = MaterialTheme.typography.headlineMedium)
            Text("Select tasks locally, then confirm completion once. GPOS verifies canonical state before declaring success.", modifier = Modifier.padding(top = 4.dp))
        }
        if (commandState.stagedCanonicalIds.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("${commandState.stagedCanonicalIds.size} staged", style = MaterialTheme.typography.titleMedium)
                        Text("Nothing has been written yet.", modifier = Modifier.padding(top = 4.dp))
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = commandState.progress == CommandProgress.IDLE) { Text("Clear staged") }
                        Button(onClick = { showConfirmation = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = canMutate && commandState.canApply) { Text("Apply completions") }
                    }
                }
            }
        }
        commandState.error?.let { item { DailySummaryCard("Task action needs attention", it) } }
        commandState.lastMessage?.let { item { DailySummaryCard("Task completion confirmed", it) } }
        if (dashboard == null) {
            item { DailySummaryCard("Tasks not loaded", "Authenticate and sync canonical data.") }
        } else if (tasks.isEmpty()) {
            item { DailySummaryCard("All clear", "No active Google Tasks returned.") }
        } else {
            if (scheduled.isNotEmpty()) {
                item { Text("Scheduled", style = MaterialTheme.typography.titleLarge) }
                items(scheduled) { task -> DailyTaskCard(task, commandState, canMutate, onTaskStaged) }
            }
            if (unscheduled.isNotEmpty()) {
                item { Text("Other", style = MaterialTheme.typography.titleLarge) }
                items(unscheduled) { task -> DailyTaskCard(task, commandState, canMutate, onTaskStaged) }
            }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh tasks") } }
    }
}

@Composable
private fun DailyFinanceScreen(finance: FinanceRuntimeState?, onRefresh: () -> Unit) {
    val categoryTotals = finance?.snapshot?.transactions.orEmpty()
        .groupBy { it.category }
        .mapValues { (_, transactions) -> transactions.sumOf { it.amount } }
        .entries
        .sortedByDescending { kotlin.math.abs(it.value) }
        .take(4)

    DailyList {
        item { DailySourceCard("SENTINEL-FIN", finance?.source, finance?.fetchedAtEpochMs, finance?.error) }
        item { Text("Money snapshot", style = MaterialTheme.typography.headlineMedium) }
        if (finance == null) {
            item { DailySummaryCard("Finance not loaded", "Authenticate and sync to read the bounded recent-finance contract.") }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Last ${finance.snapshot.hours} hours", style = MaterialTheme.typography.titleMedium)
                        Text("Purchases ${finance.snapshot.summary.purchaseTotal?.let(dailyCurrencyFormatter::format) ?: "—"}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                        Text("Credits ${finance.snapshot.summary.creditTotal?.let(dailyCurrencyFormatter::format) ?: "—"}", modifier = Modifier.padding(top = 5.dp))
                        Text("${finance.snapshot.transactions.size} transaction(s)", modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            if (categoryTotals.isNotEmpty()) {
                item { Text("Category pulse", style = MaterialTheme.typography.titleLarge) }
                items(categoryTotals.toList()) { entry ->
                    DailySummaryCard(entry.key, dailyCurrencyFormatter.format(entry.value))
                }
            }
            item { Text("Recent transactions", style = MaterialTheme.typography.titleLarge) }
            if (finance.snapshot.transactions.isEmpty()) item { DailySummaryCard("No recent activity", "No qualifying transactions returned.") }
            else items(finance.snapshot.transactions) { DailyFinanceTransactionCard(it) }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh finances") } }
    }
}

@Composable
private fun DailyNotificationsScreen(
    state: NotificationsRuntimeState?,
    commandState: NotificationCommandRuntimeState,
    canMutate: Boolean,
    onAcknowledge: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val active = state?.snapshot?.active.orEmpty()
    val ordered = active.sortedWith(
        compareBy<ServerNotification> {
            when (it.severity) {
                NotificationSeverity.CRITICAL -> 0
                NotificationSeverity.WARNING -> 1
                NotificationSeverity.INFO -> 2
                NotificationSeverity.UNKNOWN -> 3
            }
        }.thenByDescending { it.createdAtEpochMs ?: 0L },
    )

    DailyList {
        item { DailySourceCard("Notifications", state?.source, state?.fetchedAtEpochMs, state?.error) }
        item {
            Text("Attention center • ${active.size}", style = MaterialTheme.typography.headlineMedium)
            Text("Opening an alert never acknowledges it. Acknowledge remains an explicit server write.", modifier = Modifier.padding(top = 4.dp))
        }
        if (active.any { it.severity == NotificationSeverity.CRITICAL }) {
            item { DailySummaryCard("Critical attention present", "Review critical alerts first.") }
        }
        commandState.error?.let { item { DailySummaryCard("Acknowledgement failed", it) } }
        if (state == null) {
            item { DailySummaryCard("Notifications not loaded", "Authenticate and sync current server alerts.") }
        } else if (ordered.isEmpty()) {
            item { DailySummaryCard("All clear", "No active server notifications.") }
        } else {
            items(ordered) { notification ->
                DailyNotificationCard(
                    notification = notification,
                    canAcknowledge = canMutate && notification.id !in commandState.submittingIds,
                    submitting = notification.id in commandState.submittingIds,
                    onAcknowledge = { onAcknowledge(notification.id) },
                )
            }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh notifications") } }
    }
}

@Composable
private fun DailyInsightsScreen(state: RuntimeUiState) {
    val raw = state.briefing?.plainText ?: state.dashboard?.snapshot?.briefingPlainText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }
    val sections = document?.sections.orEmpty().filter { section ->
        val title = section.title.lowercase()
        listOf("news", "newspaper", "insight", "things to consider", "strategic").any(title::contains)
    }

    DailyList {
        item { DailySourceCard("HORIZON insights", state.briefing?.source ?: state.dashboard?.source, state.briefing?.fetchedAtEpochMs ?: state.dashboard?.fetchedAtEpochMs, state.briefing?.error) }
        item {
            Text("News & strategic insights", style = MaterialTheme.typography.headlineMedium)
            Text("Zero-generation projection of the current canonical briefing. No RSS or Gemini refresh is triggered here.", modifier = Modifier.padding(top = 4.dp))
        }
        if (sections.isEmpty()) {
            item { DailySummaryCard("No matching insight sections", "The current HORIZON briefing did not expose matching sections.") }
        } else {
            items(sections) { section ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        (section.lines + section.subsections.flatMap { it.lines })
                            .map(HorizonDocumentParser::displayLine)
                            .filter(String::isNotBlank)
                            .forEach { Text("• $it", modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
    }
}

private data class LocalSearchHit(
    val kind: String,
    val title: String,
    val detail: String,
    val route: String,
)

@Composable
private fun GlobalSearchScreen(state: RuntimeUiState, onNavigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val allHits = remember(state.dashboard, state.briefing, state.finance, state.notifications) {
        buildLocalSearchHits(state)
    }
    val results = remember(query, allHits) {
        val q = query.trim().lowercase()
        if (q.isBlank()) allHits.take(12)
        else allHits.filter { hit ->
            hit.title.lowercase().contains(q) || hit.detail.lowercase().contains(q) || hit.kind.lowercase().contains(q)
        }.take(50)
    }

    DailyList {
        item {
            Text("Search synced GPOS", style = MaterialTheme.typography.headlineMedium)
            Text("Searches only data already present on this device; it does not trigger network or AI work.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Calendar, tasks, HORIZON, finance, alerts") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
        }
        if (results.isEmpty()) {
            item { DailySummaryCard("No matches", "Try another term or sync current canonical data.") }
        } else {
            items(results) { hit ->
                Card(onClick = { onNavigate(hit.route) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(hit.title) },
                        supportingContent = { Text("${hit.kind} • ${hit.detail}") },
                    )
                }
            }
        }
    }
}

private fun buildLocalSearchHits(state: RuntimeUiState): List<LocalSearchHit> = buildList {
    state.dashboard?.snapshot?.todayEvents.orEmpty().forEach { event ->
        add(LocalSearchHit("Calendar • Today", event.title, "${event.timeLabel}${event.note?.let { " • $it" }.orEmpty()}", calendarDestination.route))
    }
    state.dashboard?.snapshot?.tomorrowEvents.orEmpty().forEach { event ->
        add(LocalSearchHit("Calendar • Tomorrow", event.title, "${event.timeLabel}${event.note?.let { " • $it" }.orEmpty()}", calendarDestination.route))
    }
    state.dashboard?.snapshot?.tasks.orEmpty().forEach { task ->
        add(LocalSearchHit("Task", task.title, task.timeLabel ?: "Active Google Task", tasksDestination.route))
    }
    val raw = state.briefing?.plainText ?: state.dashboard?.snapshot?.briefingPlainText
    raw?.let(HorizonDocumentParser::parse)?.sections.orEmpty().forEach { section ->
        val detail = (section.lines + section.subsections.flatMap { it.lines })
            .map(HorizonDocumentParser::displayLine)
            .filter(String::isNotBlank)
            .take(3)
            .joinToString(" • ")
        add(LocalSearchHit("HORIZON", section.title, detail.ifBlank { "Briefing section" }, briefingDestination.route))
    }
    state.finance?.snapshot?.transactions.orEmpty().forEach { transaction ->
        add(LocalSearchHit("Finance", transaction.vendor, "${transaction.category} • ${dailyCurrencyFormatter.format(transaction.amount)}", financesDestination.route))
    }
    state.notifications?.snapshot?.active.orEmpty().forEach { notification ->
        add(LocalSearchHit("Alert • ${dailySeverityLabel(notification.severity)}", notification.title, notification.message.ifBlank { notification.type }, notificationsDestination.route))
    }
}

@Composable
private fun DailyMoreScreen(onNavigate: (String) -> Unit) {
    DailyList {
        item {
            Text("More GPOS", style = MaterialTheme.typography.headlineMedium)
            Text("Search, alerts, finances, intelligence and system controls.", modifier = Modifier.padding(top = 4.dp))
        }
        items(moreDestinations) { destination ->
            Card(onClick = { onNavigate(destination.route) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(destination.title) },
                    supportingContent = { Text(destination.subtitle) },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun DailySystemScreen(
    state: RuntimeUiState,
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    notificationPermissionGranted: Boolean,
    onNotificationPermissionRequested: () -> Unit,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    val context = LocalContext.current
    val connectivity = remember(context) { AndroidConnectivityObserver(context).current() }
    val config = state.backend.authConfig
    var showDiagnostics by remember { mutableStateOf(false) }

    DailyList {
        item { Text("System", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(if (state.backend.reachable) "Backend connected" else "Backend unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(config?.let { "${it.authVersion} • backend ${it.backendVersion} • enforcement ${if (it.enforcementRequired) "ON" else "OFF"}" } ?: state.backend.error.orEmpty(), modifier = Modifier.padding(top = 5.dp))
                    Text("Network • ${dailyConnectivityLabel(connectivity)}", modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item { DailyAuthenticationCard(state.auth, onSignInRequested, onSignOutRequested) }
        item { Text("Sync health", style = MaterialTheme.typography.titleLarge) }
        item { DailySourceCard("Dashboard", state.dashboard?.source, state.dashboard?.fetchedAtEpochMs, state.dashboard?.error) }
        item { DailySourceCard("HORIZON", state.briefing?.source, state.briefing?.fetchedAtEpochMs, state.briefing?.error) }
        item { DailySourceCard("Finance", state.finance?.source, state.finance?.fetchedAtEpochMs, state.finance?.error) }
        item { DailySourceCard("Notifications", state.notifications?.source, state.notifications?.fetchedAtEpochMs, state.notifications?.error) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Background sync", style = MaterialTheme.typography.titleMedium)
                    Text("Every ${CanonicalSyncScheduler.REPEAT_MINUTES} minutes when network is available and battery is not low. Dashboard, finance, notifications and stale HORIZON only; never background mutations.", modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Android notifications", style = MaterialTheme.typography.titleMedium)
                    Text(if (notificationPermissionGranted) "Permission enabled" else "Permission disabled", modifier = Modifier.padding(top = 5.dp))
                    if (!notificationPermissionGranted) {
                        Button(onClick = onNotificationPermissionRequested, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Enable notifications") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefreshBackend, modifier = Modifier.fillMaxWidth()) { Text("Recheck backend") }
        }
        item {
            Button(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Sync canonical data") }
        }
        item { Text("Appearance", style = MaterialTheme.typography.titleLarge) }
        items(GposThemeOption.entries) { option ->
            Card(onClick = { onThemeSelected(option) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    supportingContent = { Text(option.description) },
                    leadingContent = { RadioButton(selected = option == selectedTheme, onClick = { onThemeSelected(option) }) },
                )
            }
        }
        item {
            OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showDiagnostics) "Hide developer diagnostics" else "Show developer diagnostics")
            }
        }
        if (showDiagnostics) {
            item {
                DailySummaryCard(
                    "Android OAuth",
                    "Package ${BuildConfig.GPOS_ANDROID_PACKAGE} • SHA-1 ${BuildConfig.GPOS_CHECKPOINT_CERT_SHA1} • web/server client ${BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID}",
                )
            }
            item {
                DailySummaryCard(
                    state.backend.lastProtectedRead ?: "Protected reads not checked",
                    state.backend.error ?: "Health/capability checks are read-only.",
                )
            }
        }
    }
}

@Composable
private fun GatedDailyScreen(title: String, detail: String) {
    DailyList {
        item { Text(title, style = MaterialTheme.typography.headlineMedium) }
        item { DailySummaryCard("Integration intentionally gated", detail) }
        item { DailySummaryCard("Safety boundary", "READ AUTOMATICALLY. MUTATE EXPLICITLY.") }
    }
}

@Composable
private fun DailyAuthenticationCard(
    auth: AuthState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            when (auth) {
                AuthState.Restoring -> {
                    Text("Authentication", style = MaterialTheme.typography.titleMedium)
                    Text("Restoring protected session…", modifier = Modifier.padding(top = 5.dp))
                }
                AuthState.SignedOut -> {
                    Text("Google • signed out", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onSignInRequested, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Sign in with Google") }
                }
                AuthState.Authenticating -> {
                    Text("Google • signing in…", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for Google identity and AUTH-1.", modifier = Modifier.padding(top = 5.dp))
                }
                is AuthState.Authenticated -> {
                    Text("Google • connected", style = MaterialTheme.typography.titleMedium)
                    Text(auth.user.name ?: auth.user.email, modifier = Modifier.padding(top = 5.dp))
                    Text(auth.user.email, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onSignOutRequested, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Sign out") }
                }
                is AuthState.OfflineRestored -> {
                    Text("Google • offline session", style = MaterialTheme.typography.titleMedium)
                    Text("Cached reads available; mutations are disabled until live authentication returns.", modifier = Modifier.padding(top = 5.dp))
                    Button(onClick = onSignInRequested, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Re-authenticate") }
                    OutlinedButton(onClick = onSignOutRequested, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign out") }
                }
                is AuthState.Error -> {
                    Text("Authentication needs attention", style = MaterialTheme.typography.titleMedium)
                    Text(auth.message, modifier = Modifier.padding(top = 5.dp))
                    Button(onClick = onSignInRequested, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Try sign-in again") }
                }
            }
        }
    }
}

@Composable
private fun DailyStatusPill(label: String, source: RuntimeDataSource?) {
    Card {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(source?.name ?: "—", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DailySourceCard(label: String, source: RuntimeDataSource?, fetchedAtEpochMs: Long?, error: String?) {
    val detail = buildString {
        append(fetchedAtEpochMs?.let { "Updated ${dailyFormatTime(it)}" } ?: "No local payload")
        if (!error.isNullOrBlank()) append(" • $error")
    }
    DailySummaryCard("$label • ${source?.name ?: "NOT LOADED"}", detail)
}

@Composable
private fun DailySummaryCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) })
    }
}

@Composable
private fun DailyEventCard(event: DashboardEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title) },
            supportingContent = { Text(event.timeLabel + (event.note?.let { " • $it" } ?: "")) },
            leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
        )
    }
}

@Composable
private fun DailyTaskCard(
    task: DashboardTask,
    state: TaskCommandRuntimeState,
    canMutate: Boolean,
    onTaskStaged: (String?, Boolean) -> Unit,
) {
    val staged = task.canonicalId?.let(state.stagedCanonicalIds::contains) == true
    val mutable = task.canonicalId != null
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(task.title) },
            supportingContent = {
                Text(
                    (task.timeLabel ?: "No schedule") + when {
                        !mutable -> " • display-only"
                        staged -> " • staged locally"
                        else -> ""
                    },
                )
            },
            leadingContent = {
                Checkbox(
                    checked = staged,
                    onCheckedChange = { selected -> onTaskStaged(task.canonicalId, selected) },
                    enabled = mutable && canMutate && state.progress == CommandProgress.IDLE,
                )
            },
        )
    }
}

@Composable
private fun DailyFinanceTransactionCard(transaction: FinanceTransaction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(transaction.vendor) },
            supportingContent = {
                Text(
                    buildString {
                        append(transaction.category)
                        append(" • ")
                        append(transaction.paymentSource)
                        transaction.occurredAtEpochMs?.let { append(" • ${dailyFormatTime(it)}") }
                        transaction.notes?.let { append(" • $it") }
                    },
                )
            },
            trailingContent = { Text(dailyCurrencyFormatter.format(transaction.amount)) },
        )
    }
}

@Composable
private fun DailyNotificationCard(
    notification: ServerNotification,
    canAcknowledge: Boolean,
    submitting: Boolean,
    onAcknowledge: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(notification.title) },
                supportingContent = {
                    Text(
                        buildString {
                            if (notification.message.isNotBlank()) append(notification.message)
                            if (isNotEmpty()) append(" • ")
                            append(notification.type)
                            notification.createdAtEpochMs?.let { append(" • ${dailyFormatTime(it)}") }
                            notification.detail?.let { append(" • $it") }
                        },
                    )
                },
                leadingContent = { Text(dailySeverityLabel(notification.severity)) },
            )
            OutlinedButton(
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                enabled = canAcknowledge,
            ) { Text(if (submitting) "Acknowledging…" else "Acknowledge") }
        }
    }
}

@Composable
private fun DailyBriefingTextCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.map(HorizonDocumentParser::displayLine).filter(String::isNotBlank).forEach {
                Text(it, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DailyList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

private fun dailyAuthHint(auth: AuthState): String = when (auth) {
    AuthState.Restoring -> "Restoring your protected session."
    AuthState.SignedOut -> "Open System and sign in with Google to load canonical data."
    AuthState.Authenticating -> "Authentication is currently in progress."
    is AuthState.Authenticated -> "Connected as ${auth.user.email}."
    is AuthState.OfflineRestored -> auth.message
    is AuthState.Error -> auth.message
}

private fun dailyFormatTime(epochMs: Long): String = dailyDateTimeFormatter.format(Instant.ofEpochMilli(epochMs))

private fun dailyConnectivityLabel(state: ConnectivityState): String = when (state) {
    ConnectivityState.Unknown -> "UNKNOWN"
    ConnectivityState.Offline -> "OFFLINE"
    is ConnectivityState.Online -> if (state.validated) "ONLINE / VALIDATED" else "ONLINE / UNVALIDATED"
}

private fun dailySeverityLabel(severity: NotificationSeverity): String = when (severity) {
    NotificationSeverity.INFO -> "INFO"
    NotificationSeverity.WARNING -> "WARN"
    NotificationSeverity.CRITICAL -> "CRIT"
    NotificationSeverity.UNKNOWN -> "?"
}
