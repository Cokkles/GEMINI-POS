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

class GposActivity : ComponentActivity() {
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
                GposApp030(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { theme ->
                        selectedTheme = theme
                        themePreferences.save(theme)
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
                                googleSignInCoordinator.requestIdToken(
                                    BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID,
                                )
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

private data class Destination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val home = Destination("home", "Home", "Canonical mobile overview", Icons.Outlined.Home)
private val briefing = Destination("briefing", "Briefing", "Latest canonical HORIZON", Icons.Outlined.Description)
private val calendar = Destination("calendar", "Calendar", "Schedule + confirmed event creation", Icons.Outlined.Event)
private val tasks = Destination("tasks", "Tasks", "Stage + explicitly complete tasks", Icons.Outlined.CheckCircle)
private val more = Destination("more", "More", "Additional GPOS areas", Icons.Outlined.MoreHoriz)
private val notifications = Destination("notifications", "Notifications", "Server alerts + explicit acknowledgement", Icons.Outlined.Notifications)
private val finances = Destination("finances", "Finances", "Recent SENTINEL-FIN activity", Icons.Outlined.AccountBalanceWallet)
private val insights = Destination("insights", "Insights", "HORIZON-derived insight sections", Icons.Outlined.Lightbulb)
private val followups = Destination("followups", "Follow-ups", "Dedicated contract pending", Icons.Outlined.Notifications)
private val aegis = Destination("aegis", "Ask AEGIS", "Conversation contract pending", Icons.Outlined.Forum)
private val system = Destination("system", "System", "Status, authentication and appearance", Icons.Outlined.Settings)

private val primaryDestinations = listOf(home, briefing, calendar, tasks, more)
private val secondaryDestinations = listOf(notifications, finances, insights, followups, aegis, system)
private val allDestinations = primaryDestinations + secondaryDestinations

private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d • h:mm a")
    .withZone(ZoneId.systemDefault())
private val currencyFormatter: NumberFormat = NumberFormat.getCurrencyInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GposApp030(
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
    val currentRoute = backStackEntry?.destination?.route ?: home.route
    val currentDestination = allDestinations.firstOrNull { it.route == currentRoute } ?: home
    val selectedPrimaryRoute = if (secondaryDestinations.any { it.route == currentRoute }) more.route else currentRoute
    val canMutate = runtimeState.auth is AuthState.Authenticated

    LaunchedEffect(deepLinkTarget) {
        deepLinkTarget?.let { target ->
            navController.navigate(target.route) { launchSingleTop = true }
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
                    IconButton(onClick = onRefreshCanonical) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh canonical data")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                primaryDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedPrimaryRoute == destination.route,
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
            startDestination = home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(home.route) { HomeScreen(runtimeState, onRefreshCanonical) }
            composable(briefing.route) { BriefingScreen(runtimeState, onRefreshCanonical) }
            composable(calendar.route) {
                CalendarScreen(
                    dashboard = runtimeState.dashboard,
                    commandState = calendarCommandState,
                    canMutate = canMutate,
                    onResolve = onCalendarResolve,
                    onCancel = onCalendarCancel,
                    onCreate = onCalendarCreate,
                    onRefreshCanonical = onRefreshCanonical,
                )
            }
            composable(tasks.route) {
                TasksScreen(
                    dashboard = runtimeState.dashboard,
                    commandState = taskCommandState,
                    canMutate = canMutate,
                    onTaskStaged = onTaskStaged,
                    onClear = onTaskClear,
                    onApply = onTaskApply,
                    onRefreshCanonical = onRefreshCanonical,
                )
            }
            composable(more.route) { MoreScreen(onNavigate = { route -> navController.navigate(route) }) }
            composable(notifications.route) {
                NotificationsScreen(
                    state = runtimeState.notifications,
                    commandState = notificationCommandState,
                    canMutate = canMutate,
                    onAcknowledge = onNotificationAcknowledge,
                    onRefreshCanonical = onRefreshCanonical,
                )
            }
            composable(finances.route) { FinanceScreen(runtimeState.finance, onRefreshCanonical) }
            composable(insights.route) { InsightsScreen(runtimeState) }
            composable(followups.route) {
                PendingContractScreen(
                    title = "Follow-ups",
                    detail = "No dedicated bounded Follow-ups contract has been proven yet. Android will not manufacture follow-ups from HORIZON prose or Gmail heuristics.",
                )
            }
            composable(aegis.route) {
                PendingContractScreen(
                    title = "Ask AEGIS",
                    detail = "The conversation/query transport remains gated until its mobile authentication, cost, and mutation semantics are explicitly reviewed.",
                )
            }
            composable(system.route) {
                SystemScreen(
                    runtimeState = runtimeState,
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
private fun HomeScreen(runtimeState: RuntimeUiState, onRefreshCanonical: () -> Unit) {
    val quote = remember { quoteFor(LocalDate.now()) }
    val dashboard = runtimeState.dashboard
    val nextEvent = dashboard?.snapshot?.todayEvents?.firstOrNull()
        ?: dashboard?.snapshot?.tomorrowEvents?.firstOrNull()
    val taskCount = dashboard?.snapshot?.tasks?.size
    val finance = runtimeState.finance?.snapshot
    val activeAlerts = runtimeState.notifications?.snapshot?.active.orEmpty()
    val briefingText = runtimeState.briefing?.plainText ?: dashboard?.snapshot?.briefingPlainText

    ScreenList {
        item { DailyInspirationCard(quote.text, quote.attribution) }
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("GPOS Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        }
        item { DataSourceCard("Canonical dashboard", dashboard?.source, dashboard?.fetchedAtEpochMs, dashboard?.error) }

        if (dashboard == null) {
            item { SummaryCard("Canonical data not loaded", authHint(runtimeState.auth)) }
        } else {
            item {
                SummaryCard(
                    nextEvent?.let { "Next: ${it.title}" } ?: "Calendar clear",
                    nextEvent?.let { event ->
                        "${if (event.day.name == "TODAY") "Today" else "Tomorrow"} • ${event.timeLabel}${event.note?.let { " • $it" }.orEmpty()}"
                    } ?: "No today/tomorrow events were returned.",
                )
            }
            item {
                SummaryCard(
                    "${taskCount ?: 0} active task${if (taskCount == 1) "" else "s"}",
                    dashboard.snapshot.tasks.take(3).joinToString(" • ") { it.title }
                        .ifBlank { "No active Google Tasks returned." },
                )
            }
        }

        item {
            val purchase = finance?.summary?.purchaseTotal
            val credits = finance?.summary?.creditTotal
            SummaryCard(
                title = if (purchase == null) "Finance not loaded" else "72h purchases ${currencyFormatter.format(purchase)}",
                detail = if (finance == null) {
                    "SENTINEL-FIN recent activity will appear after authenticated canonical refresh."
                } else {
                    "${finance.transactions.size} transaction${if (finance.transactions.size == 1) "" else "s"} • credits ${credits?.let(currencyFormatter::format) ?: "—"}"
                },
            )
        }
        item {
            SummaryCard(
                title = if (activeAlerts.isEmpty()) "No active server alerts" else "${activeAlerts.size} active server alert${if (activeAlerts.size == 1) "" else "s"}",
                detail = if (activeAlerts.any { it.severity == NotificationSeverity.CRITICAL }) {
                    "Critical attention is present. Open More → Notifications."
                } else {
                    "Server notification state from AEGIS."
                },
            )
        }
        item {
            SummaryCard(
                title = if (briefingText.isNullOrBlank()) "Briefing unavailable" else "HORIZON briefing available",
                detail = briefingText
                    ?.lineSequence()
                    ?.map(String::trim)
                    ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: "Sign in and refresh to retrieve the canonical briefing.",
            )
        }
        item {
            Button(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh all canonical reads") }
        }
    }
}

@Composable
private fun BriefingScreen(runtimeState: RuntimeUiState, onRefreshCanonical: () -> Unit) {
    val briefingState = runtimeState.briefing
    val fallbackText = runtimeState.dashboard?.snapshot?.briefingPlainText
    val raw = briefingState?.plainText ?: fallbackText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }

    ScreenList {
        item {
            DataSourceCard(
                "HORIZON",
                briefingState?.source ?: runtimeState.dashboard?.source,
                briefingState?.fetchedAtEpochMs ?: runtimeState.dashboard?.fetchedAtEpochMs,
                briefingState?.error,
            )
        }
        if (raw.isNullOrBlank() || document == null) {
            item { SummaryCard("No canonical briefing loaded", authHint(runtimeState.auth)) }
        } else {
            item { Text(document.title ?: "Daily Executive Briefing", style = MaterialTheme.typography.headlineSmall) }
            if (document.preamble.isNotEmpty()) item { BriefingTextCard("Overview", document.preamble) }
            items(document.sections) { section ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        section.lines.map(HorizonDocumentParser::displayLine)
                            .filter(String::isNotBlank)
                            .forEach { Text("• $it", modifier = Modifier.padding(top = 8.dp)) }
                        section.subsections.forEach { subsection ->
                            Text(subsection.title, modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
                            subsection.lines.map(HorizonDocumentParser::displayLine)
                                .filter(String::isNotBlank)
                                .forEach { Text("• $it", modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh briefing") } }
    }
}

@Composable
private fun CalendarScreen(
    dashboard: DashboardRuntimeState?,
    commandState: CalendarCommandRuntimeState,
    canMutate: Boolean,
    onResolve: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    var eventText by remember { mutableStateOf("") }

    ScreenList {
        item { DataSourceCard("Calendar", dashboard?.source, dashboard?.fetchedAtEpochMs, dashboard?.error) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Add an event", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Describe the event. Resolve creates a proposal only; nothing reaches Google Calendar until you review it and press Add to Calendar.",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = eventText,
                        onValueChange = { eventText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        enabled = canMutate && commandState.progress !in setOf(
                            CalendarCommandProgress.RESOLVING,
                            CalendarCommandProgress.CREATING,
                        ),
                        label = { Text("Event description") },
                        placeholder = { Text("Dinner Tuesday at 7 PM at…") },
                    )
                    Button(
                        onClick = { onResolve(eventText) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = canMutate && eventText.isNotBlank() && commandState.progress !in setOf(
                            CalendarCommandProgress.RESOLVING,
                            CalendarCommandProgress.CREATING,
                        ),
                    ) {
                        Text(if (commandState.progress == CalendarCommandProgress.RESOLVING) "Resolving…" else "Resolve and preview")
                    }
                    if (!canMutate) {
                        Text("Sign in online to enable Calendar creation.", modifier = Modifier.padding(top = 8.dp))
                    }
                    commandState.error?.let {
                        Text("Calendar action failed: $it", modifier = Modifier.padding(top = 8.dp))
                    }
                    commandState.lastMessage?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        commandState.proposal?.let { proposal ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Review proposed event", style = MaterialTheme.typography.titleMedium)
                        Text(proposal.title, modifier = Modifier.padding(top = 10.dp))
                        Text("${proposal.start} → ${proposal.end}", modifier = Modifier.padding(top = 6.dp))
                        proposal.location?.let { Text("Location: $it", modifier = Modifier.padding(top = 6.dp)) }
                        proposal.description?.let { Text(it, modifier = Modifier.padding(top = 6.dp)) }
                        Text(
                            "This is still a proposal. Add to Calendar is the write action.",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            Button(
                                onClick = onCreate,
                                modifier = Modifier.weight(1f),
                                enabled = canMutate && commandState.canCreate,
                            ) {
                                Text(if (commandState.progress == CalendarCommandProgress.CREATING) "Adding…" else "Add to Calendar")
                            }
                        }
                    }
                }
            }
        }
        if (dashboard == null) {
            item { SummaryCard("Calendar not loaded", "Authenticate in System, then refresh canonical data.") }
        } else {
            item { Text("Today", style = MaterialTheme.typography.headlineSmall) }
            if (dashboard.snapshot.todayEvents.isEmpty()) {
                item { SummaryCard("No events today", "No today events were returned by the canonical dashboard feed.") }
            } else {
                items(dashboard.snapshot.todayEvents) { EventCard(it) }
            }
            item { Text("Tomorrow", style = MaterialTheme.typography.headlineSmall) }
            if (dashboard.snapshot.tomorrowEvents.isEmpty()) {
                item { SummaryCard("No events tomorrow", "No tomorrow events were returned by the canonical dashboard feed.") }
            } else {
                items(dashboard.snapshot.tomorrowEvents) { EventCard(it) }
            }
        }
        item { OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh calendar") } }
    }
}

@Composable
private fun TasksScreen(
    dashboard: DashboardRuntimeState?,
    commandState: TaskCommandRuntimeState,
    canMutate: Boolean,
    onTaskStaged: (String?, Boolean) -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    var showConfirmation by remember { mutableStateOf(false) }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { if (commandState.progress != CommandProgress.SUBMITTING) showConfirmation = false },
            title = { Text("Complete Google Tasks?") },
            text = {
                Text(
                    "Complete ${commandState.stagedCanonicalIds.size} selected task${if (commandState.stagedCanonicalIds.size == 1) "" else "s"}? This writes to canonical Google Tasks only after you confirm.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        onApply()
                    },
                    enabled = commandState.canApply,
                ) { Text("Complete tasks") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    ScreenList {
        item { DataSourceCard("Tasks", dashboard?.source, dashboard?.fetchedAtEpochMs, dashboard?.error) }
        item {
            Text("Active Google Tasks", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Selections are local staging only. Google Tasks are changed only after Apply completions and the confirmation dialog.",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (commandState.stagedCanonicalIds.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "${commandState.stagedCanonicalIds.size} pending completion${if (commandState.stagedCanonicalIds.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("Not written yet.", modifier = Modifier.padding(top = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onClear,
                                modifier = Modifier.weight(1f),
                                enabled = commandState.progress == CommandProgress.IDLE,
                            ) { Text("Clear") }
                            Button(
                                onClick = { showConfirmation = true },
                                modifier = Modifier.weight(1f),
                                enabled = canMutate && commandState.canApply,
                            ) { Text("Apply completions") }
                        }
                    }
                }
            }
        }
        commandState.error?.let { item { SummaryCard("Task completion failed", "$it • Pending selections were preserved.") } }
        commandState.lastMessage?.let { item { SummaryCard("Task completion confirmed", it) } }
        if (!canMutate) {
            item { SummaryCard("Task writes disabled", "Sign in online to stage and complete canonical Google Tasks. Cached/offline sessions remain read-only.") }
        }
        if (dashboard == null) {
            item { SummaryCard("Tasks not loaded", "Authenticate in System, then refresh canonical data.") }
        } else if (dashboard.snapshot.tasks.isEmpty()) {
            item { SummaryCard("No active tasks", "The canonical dashboard returned no active Google Tasks.") }
        } else {
            items(dashboard.snapshot.tasks) { task ->
                TaskCard(
                    task = task,
                    staged = task.canonicalId?.let(commandState.stagedCanonicalIds::contains) == true,
                    canMutate = canMutate && commandState.progress == CommandProgress.IDLE,
                    onStaged = { selected -> onTaskStaged(task.canonicalId, selected) },
                )
            }
        }
        item { OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh tasks") } }
    }
}

@Composable
private fun FinanceScreen(finance: FinanceRuntimeState?, onRefreshCanonical: () -> Unit) {
    ScreenList {
        item { DataSourceCard("SENTINEL-FIN", finance?.source, finance?.fetchedAtEpochMs, finance?.error) }
        item { Text("Recent activity", style = MaterialTheme.typography.headlineSmall) }
        if (finance == null) {
            item { SummaryCard("Finance not loaded", "Authenticate and refresh to read the bounded recent-finance contract.") }
        } else {
            item {
                val summary = finance.snapshot.summary
                SummaryCard(
                    "${finance.snapshot.hours}h purchases ${summary.purchaseTotal?.let(currencyFormatter::format) ?: "—"}",
                    "Credits ${summary.creditTotal?.let(currencyFormatter::format) ?: "—"} • ${finance.snapshot.transactions.size} transaction${if (finance.snapshot.transactions.size == 1) "" else "s"}",
                )
            }
            if (finance.snapshot.transactions.isEmpty()) {
                item { SummaryCard("No recent activity", "No qualifying finance activity was returned for this window.") }
            } else {
                items(finance.snapshot.transactions) { FinanceTransactionCard(it) }
            }
        }
        item { OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh finance") } }
    }
}

@Composable
private fun NotificationsScreen(
    state: NotificationsRuntimeState?,
    commandState: NotificationCommandRuntimeState,
    canMutate: Boolean,
    onAcknowledge: (String) -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    ScreenList {
        item { DataSourceCard("Notifications", state?.source, state?.fetchedAtEpochMs, state?.error) }
        item {
            Text("Server notifications", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Opening or deep-linking never acknowledges an alert. Acknowledge is an explicit server write from the button on that alert only.",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        commandState.error?.let { item { SummaryCard("Acknowledgement failed", it) } }
        if (state == null) {
            item { SummaryCard("Notifications not loaded", "Authenticate and refresh to read current server alerts.") }
        } else if (state.snapshot.active.isEmpty()) {
            item { SummaryCard("All clear", "No unacknowledged server notifications were returned.") }
        } else {
            items(state.snapshot.active) { notification ->
                NotificationCard(
                    notification = notification,
                    canAcknowledge = canMutate && notification.id !in commandState.submittingIds,
                    submitting = notification.id in commandState.submittingIds,
                    onAcknowledge = { onAcknowledge(notification.id) },
                )
            }
        }
        item { OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) { Text("Refresh notifications") } }
    }
}

@Composable
private fun InsightsScreen(runtimeState: RuntimeUiState) {
    val raw = runtimeState.briefing?.plainText ?: runtimeState.dashboard?.snapshot?.briefingPlainText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }
    val sections = document?.sections.orEmpty().filter { section ->
        val title = section.title.lowercase()
        listOf("news", "newspaper", "insight", "things to consider", "strategic").any(title::contains)
    }

    ScreenList {
        item {
            DataSourceCard(
                "HORIZON insights",
                runtimeState.briefing?.source ?: runtimeState.dashboard?.source,
                runtimeState.briefing?.fetchedAtEpochMs ?: runtimeState.dashboard?.fetchedAtEpochMs,
                runtimeState.briefing?.error,
            )
        }
        item {
            Text("News & insights", style = MaterialTheme.typography.headlineSmall)
            Text("This view is a mobile projection of matching canonical HORIZON sections; it does not trigger RSS or Gemini refresh.", modifier = Modifier.padding(top = 4.dp))
        }
        if (sections.isEmpty()) {
            item { SummaryCard("No matching HORIZON sections", "The current briefing did not expose a news/insight/strategic section.") }
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

@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    ScreenList {
        item {
            Text("More GPOS", style = MaterialTheme.typography.headlineSmall)
            Text("Additional mobile surfaces", modifier = Modifier.padding(top = 4.dp))
        }
        items(secondaryDestinations) { destination ->
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
private fun PendingContractScreen(title: String, detail: String) {
    ScreenList {
        item { Text(title, style = MaterialTheme.typography.headlineSmall) }
        item { SummaryCard("Integration intentionally gated", detail) }
        item { SummaryCard("Safety boundary", "READ AUTOMATICALLY. MUTATE EXPLICITLY. No production write is exposed from this screen.") }
    }
}

@Composable
private fun SystemScreen(
    runtimeState: RuntimeUiState,
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
    val config = runtimeState.backend.authConfig

    ScreenList {
        item { Text("System", style = MaterialTheme.typography.headlineSmall) }
        item {
            SummaryCard(
                if (runtimeState.backend.reachable) "Backend reachable" else "Backend unavailable",
                config?.let { "${it.authVersion} • backend ${it.backendVersion} • enforcement ${if (it.enforcementRequired) "ON" else "OFF"}" }
                    ?: runtimeState.backend.error ?: "AUTH-1 discovery has not completed.",
            )
        }
        item { AuthenticationCard(runtimeState.auth, onSignInRequested, onSignOutRequested) }
        item {
            SummaryCard(
                "Android OAuth registration",
                "Package ${BuildConfig.GPOS_ANDROID_PACKAGE} • checkpoint SHA-1 ${BuildConfig.GPOS_CHECKPOINT_CERT_SHA1} • server/web client ${BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID}. If Google fails before AUTH-1, verify an Android OAuth client with this package + SHA-1 in the same Cloud project.",
            )
        }
        item {
            SummaryCard(
                "Device connectivity: ${connectivityLabel(connectivity)}",
                "OS network capability only; backend reachability is tracked separately above.",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Background refresh", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Automatically scheduled after a valid session: every ${CanonicalSyncScheduler.REPEAT_MINUTES} minutes when network is connected and battery is not low. Uses read-only dashboard, finance, notifications, and stale HORIZON reads only. Command clients are never used by WorkManager.",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Local notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationPermissionGranted) {
                            "Permission granted. Background critical alerts use privacy-safe generic lock-screen text."
                        } else {
                            "Permission not granted. GPOS will not post Android notifications until you enable it."
                        },
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    if (!notificationPermissionGranted) {
                        Button(onClick = onNotificationPermissionRequested) { Text("Enable notifications") }
                    }
                }
            }
        }
        item { DataSourceCard("Dashboard cache", runtimeState.dashboard?.source, runtimeState.dashboard?.fetchedAtEpochMs, runtimeState.dashboard?.error) }
        item { DataSourceCard("HORIZON cache", runtimeState.briefing?.source, runtimeState.briefing?.fetchedAtEpochMs, runtimeState.briefing?.error) }
        item { DataSourceCard("Finance cache", runtimeState.finance?.source, runtimeState.finance?.fetchedAtEpochMs, runtimeState.finance?.error) }
        item { DataSourceCard("Notifications cache", runtimeState.notifications?.source, runtimeState.notifications?.fetchedAtEpochMs, runtimeState.notifications?.error) }
        item {
            SummaryCard(
                runtimeState.backend.lastProtectedRead ?: "Protected reads not checked",
                runtimeState.backend.error ?: "Health and capability checks are read-only.",
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRefreshBackend, modifier = Modifier.weight(1f)) { Text("Backend") }
                Button(onClick = onRefreshCanonical, modifier = Modifier.weight(1f)) { Text("Canonical") }
            }
        }
        item { Text("Appearance", style = MaterialTheme.typography.headlineSmall) }
        items(GposThemeOption.entries) { option ->
            Card(onClick = { onThemeSelected(option) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    supportingContent = { Text(option.description) },
                    leadingContent = {
                        RadioButton(selected = option == selectedTheme, onClick = { onThemeSelected(option) })
                    },
                )
            }
        }
    }
}

@Composable
private fun AuthenticationCard(
    authState: AuthState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            when (authState) {
                AuthState.Restoring -> {
                    Text("Authentication", style = MaterialTheme.typography.titleMedium)
                    Text("Restoring protected session…", modifier = Modifier.padding(top = 6.dp))
                }
                AuthState.SignedOut -> {
                    Text("Signed out", style = MaterialTheme.typography.titleMedium)
                    Text("Google AUTH-1 is required for live canonical reads and explicit commands.", modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    Button(onClick = onSignInRequested) { Text("Sign in with Google") }
                }
                AuthState.Authenticating -> {
                    Text("Signing in…", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for Google identity and AUTH-1 validation.", modifier = Modifier.padding(top = 6.dp))
                }
                is AuthState.Authenticated -> {
                    Text("Signed in", style = MaterialTheme.typography.titleMedium)
                    Text(authState.user.name ?: authState.user.email, modifier = Modifier.padding(top = 6.dp))
                    Text(authState.user.email, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onSignOutRequested, modifier = Modifier.padding(top = 12.dp)) { Text("Sign out") }
                }
                is AuthState.OfflineRestored -> {
                    Text("Offline protected session", style = MaterialTheme.typography.titleMedium)
                    Text(authState.message, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    Text("Cached reads remain available; mutations are disabled until online re-authentication.", modifier = Modifier.padding(bottom = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSignInRequested) { Text("Re-authenticate") }
                        OutlinedButton(onClick = onSignOutRequested) { Text("Sign out") }
                    }
                }
                is AuthState.Error -> {
                    Text("Authentication needs attention", style = MaterialTheme.typography.titleMedium)
                    Text(authState.message, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    Button(onClick = onSignInRequested) { Text("Try sign-in again") }
                }
            }
        }
    }
}

@Composable
private fun DataSourceCard(label: String, source: RuntimeDataSource?, fetchedAtEpochMs: Long?, error: String?) {
    val sourceLabel = source?.name ?: "NOT LOADED"
    val detail = buildString {
        append(fetchedAtEpochMs?.let { "Fetched ${formatTime(it)}" } ?: "No last-known-good payload on this device")
        if (!error.isNullOrBlank()) append(" • $error")
    }
    SummaryCard("$label • $sourceLabel", detail)
}

@Composable
private fun DailyInspirationCard(text: String, attribution: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Quote of the day", style = MaterialTheme.typography.labelLarge)
            Text("“$text”", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            if (!attribution.isNullOrBlank()) {
                Text("— $attribution", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventCard(event: DashboardEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title) },
            supportingContent = { Text(event.timeLabel + (event.note?.let { " • $it" } ?: "")) },
            leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
        )
    }
}

@Composable
private fun TaskCard(
    task: DashboardTask,
    staged: Boolean,
    canMutate: Boolean,
    onStaged: (Boolean) -> Unit,
) {
    val mutable = task.canonicalId != null
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(task.title) },
            supportingContent = {
                Text(
                    if (mutable) {
                        (task.timeLabel ?: "Google Task") + if (staged) " • staged locally" else ""
                    } else {
                        (task.timeLabel ?: "Google Task") + " • display-only: canonical task ID unavailable"
                    },
                )
            },
            leadingContent = {
                Checkbox(
                    checked = staged,
                    onCheckedChange = onStaged,
                    enabled = mutable && canMutate,
                )
            },
        )
    }
}

@Composable
private fun FinanceTransactionCard(transaction: FinanceTransaction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(transaction.vendor) },
            supportingContent = {
                Text(
                    buildString {
                        append(transaction.category)
                        append(" • ")
                        append(transaction.paymentSource)
                        transaction.occurredAtEpochMs?.let { append(" • ${formatTime(it)}") }
                        transaction.notes?.let { append(" • $it") }
                    },
                )
            },
            trailingContent = { Text(currencyFormatter.format(transaction.amount)) },
        )
    }
}

@Composable
private fun NotificationCard(
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
                            notification.createdAtEpochMs?.let { append(" • ${formatTime(it)}") }
                            notification.detail?.let { append(" • $it") }
                        },
                    )
                },
                leadingContent = { Text(severityLabel(notification.severity)) },
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
private fun BriefingTextCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.map(HorizonDocumentParser::displayLine).filter(String::isNotBlank)
                .forEach { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) })
    }
}

@Composable
private fun ScreenList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

private fun authHint(authState: AuthState): String = when (authState) {
    AuthState.Restoring -> "Restoring your protected session."
    AuthState.SignedOut -> "Open System and sign in with Google to load live canonical data."
    AuthState.Authenticating -> "Authentication is currently in progress."
    is AuthState.Authenticated -> "Signed in as ${authState.user.email}. Use Refresh all canonical reads."
    is AuthState.OfflineRestored -> authState.message
    is AuthState.Error -> authState.message
}

private fun formatTime(epochMs: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMs))

private fun connectivityLabel(state: ConnectivityState): String = when (state) {
    ConnectivityState.Unknown -> "UNKNOWN"
    ConnectivityState.Offline -> "OFFLINE"
    is ConnectivityState.Online -> if (state.validated) "ONLINE / VALIDATED" else "ONLINE / UNVALIDATED"
}

private fun severityLabel(severity: NotificationSeverity): String = when (severity) {
    NotificationSeverity.INFO -> "INFO"
    NotificationSeverity.WARNING -> "WARN"
    NotificationSeverity.CRITICAL -> "CRIT"
    NotificationSeverity.UNKNOWN -> "?"
}
