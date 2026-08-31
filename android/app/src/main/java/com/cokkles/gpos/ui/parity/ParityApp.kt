package com.cokkles.gpos.ui.parity

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cokkles.gpos.BuildConfig
import com.cokkles.gpos.CaptureUiState
import com.cokkles.gpos.ParityUiState
import com.cokkles.gpos.TaskQueueUiState
import com.cokkles.gpos.data.command.CalendarCommandProgress
import com.cokkles.gpos.data.command.CalendarCommandRuntimeState
import com.cokkles.gpos.data.command.CaptureKind
import com.cokkles.gpos.data.command.NotificationCommandRuntimeState
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.PendingTaskMutation
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.CalendarRangeEvent
import com.cokkles.gpos.data.remote.DashboardEvent
import com.cokkles.gpos.data.remote.DashboardTask
import com.cokkles.gpos.data.remote.IntelligenceItem
import com.cokkles.gpos.data.remote.NotificationSeverity
import com.cokkles.gpos.data.remote.NutritionDay
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.data.remote.ServerNotification
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.sync.CanonicalSyncScheduler
import com.cokkles.gpos.platform.sync.TaskQueueSyncScheduler
import com.cokkles.gpos.ui.briefing.HorizonDocumentParser
import com.cokkles.gpos.ui.theme.GposThemeOption
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.delay

private data class Destination(
    val route: String,
    val title: String,
    val icon: ImageVector,
)

private val home = Destination("home", "Home", Icons.Outlined.Home)
private val calendar = Destination("calendar", "Calendar", Icons.Outlined.Event)
private val tasks = Destination("tasks", "Tasks", Icons.Outlined.CheckCircle)
private val capture = Destination("capture", "Capture", Icons.Outlined.Inbox)
private val more = Destination("more", "More", Icons.Outlined.MoreHoriz)
private val news = Destination("news", "News & Insights", Icons.Outlined.Article)
private val nutrition = Destination("nutrition", "Nutrition", Icons.Outlined.Fastfood)
private val alerts = Destination("alerts", "Alerts & Receipts", Icons.Outlined.Notifications)
private val briefing = Destination("briefing", "HORIZON Report", Icons.Outlined.Description)
private val finances = Destination("finances", "Finances", Icons.Outlined.AccountBalanceWallet)
private val search = Destination("search", "Search", Icons.Outlined.Search)
private val system = Destination("system", "Diagnostics & Settings", Icons.Outlined.Settings)

private val bottomDestinations = listOf(home, calendar, tasks, capture, more)
private val secondaryDestinations = listOf(news, nutrition, alerts, briefing, finances, search, system)
private val allDestinations = bottomDestinations + secondaryDestinations

private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d • h:mm a")
    .withZone(ZoneId.systemDefault())
private val currencyFormatter = NumberFormat.getCurrencyInstance()
private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

private data class Quote(val text: String, val author: String)

// Attributed-human entries from the canonical AEGIS quote library. The rotation behavior matches
// Windows/PWA; the larger library can continue expanding without changing UI semantics.
private val quotes = listOf(
    Quote("The way to get started is to quit talking and begin doing.", "Walt Disney"),
    Quote("Do what you can, with what you have, where you are.", "Theodore Roosevelt"),
    Quote("It always seems impossible until it's done.", "Nelson Mandela"),
    Quote("Action is the foundational key to all success.", "Pablo Picasso"),
    Quote("The secret of getting ahead is getting started.", "Mark Twain"),
    Quote("Start where you are. Use what you have. Do what you can.", "Arthur Ashe"),
    Quote("Whatever you are, be a good one.", "Abraham Lincoln"),
    Quote("It does not matter how slowly you go as long as you do not stop.", "Confucius"),
    Quote("Turn your wounds into wisdom.", "Oprah Winfrey"),
    Quote("Failure is simply the opportunity to begin again, this time more intelligently.", "Henry Ford"),
    Quote("The man who moves a mountain begins by carrying away small stones.", "Confucius"),
    Quote("Freedom lies in being bold.", "Robert Frost"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParityApp(
    runtimeState: RuntimeUiState,
    parityState: ParityUiState,
    taskQueueState: TaskQueueUiState,
    captureState: CaptureUiState,
    calendarCommandState: CalendarCommandRuntimeState,
    notificationCommandState: NotificationCommandRuntimeState,
    selectedTheme: GposThemeOption,
    deepLinkTarget: GposDeepLinkTarget?,
    notificationPermissionGranted: Boolean,
    onDeepLinkConsumed: () -> Unit,
    onThemeSelected: (GposThemeOption) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onNotificationPermission: () -> Unit,
    onRefreshCanonical: () -> Unit,
    onRefreshBackend: () -> Unit,
    onParityRefresh: () -> Unit,
    onShiftMonth: (Long) -> Unit,
    onTodayMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onRefreshCalendar: () -> Unit,
    onRefreshIntelligence: () -> Unit,
    onNutritionDays: (Int) -> Unit,
    onTaskHistoryDays: (Int) -> Unit,
    onTaskStage: (String?, String) -> Unit,
    onTaskUndo: (String) -> Unit,
    onTaskSyncNow: () -> Unit,
    onCaptureSubmit: (CaptureKind, String) -> Unit,
    onLocalAlertAck: (String) -> Unit,
    onCalendarResolve: (String) -> Unit,
    onCalendarCancel: () -> Unit,
    onCalendarCreate: () -> Unit,
    onServerNotificationAck: (String) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: home.route
    val currentDestination = allDestinations.firstOrNull { it.route == currentRoute } ?: home
    val selectedBottom = if (secondaryDestinations.any { it.route == currentRoute }) more.route else currentRoute
    val authenticated = runtimeState.auth is AuthState.Authenticated

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    LaunchedEffect(runtimeState.auth) {
        if (runtimeState.auth is AuthState.Authenticated) onParityRefresh()
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
                        Text("AEGIS")
                        Text(currentDestination.title, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { navigate(search.route) }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search AEGIS")
                    }
                    IconButton(onClick = onRefreshCanonical) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh AEGIS")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedBottom == destination.route,
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
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(home.route) {
                HomeScreen(runtimeState, parityState, taskQueueState, ::navigate, onRefreshCanonical)
            }
            composable(calendar.route) {
                CalendarScreen(
                    parityState = parityState,
                    commandState = calendarCommandState,
                    canMutate = authenticated,
                    onShiftMonth = onShiftMonth,
                    onToday = onTodayMonth,
                    onSelectDate = onSelectDate,
                    onRefresh = onRefreshCalendar,
                    onResolve = onCalendarResolve,
                    onCancel = onCalendarCancel,
                    onCreate = onCalendarCreate,
                )
            }
            composable(tasks.route) {
                TasksScreen(
                    runtimeState = runtimeState,
                    parityState = parityState,
                    queueState = taskQueueState,
                    canMutate = authenticated,
                    onStage = onTaskStage,
                    onUndo = onTaskUndo,
                    onSyncNow = onTaskSyncNow,
                    onHistoryDays = onTaskHistoryDays,
                )
            }
            composable(capture.route) {
                CaptureScreen(
                    state = captureState,
                    canMutate = authenticated,
                    onSubmit = onCaptureSubmit,
                    onOpenAlerts = { navigate(alerts.route) },
                )
            }
            composable(more.route) { MoreScreen(::navigate) }
            composable(news.route) { NewsScreen(parityState, onRefreshIntelligence) }
            composable(nutrition.route) {
                NutritionScreen(runtimeState, parityState, onNutritionDays)
            }
            composable(alerts.route) {
                AlertsScreen(
                    runtimeState = runtimeState,
                    captureState = captureState,
                    taskQueueState = taskQueueState,
                    notificationCommandState = notificationCommandState,
                    canMutate = authenticated,
                    onLocalAlertAck = onLocalAlertAck,
                    onServerAck = onServerNotificationAck,
                )
            }
            composable(briefing.route) { BriefingScreen(runtimeState) }
            composable(finances.route) { FinanceScreen(runtimeState) }
            composable(search.route) { SearchScreen(runtimeState, parityState, ::navigate) }
            composable(system.route) {
                SystemScreen(
                    runtimeState = runtimeState,
                    parityState = parityState,
                    selectedTheme = selectedTheme,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onThemeSelected = onThemeSelected,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onNotificationPermission = onNotificationPermission,
                    onRefreshBackend = onRefreshBackend,
                    onRefresh = onRefreshCanonical,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    runtime: RuntimeUiState,
    parity: ParityUiState,
    queue: TaskQueueUiState,
    navigate: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val dashboard = runtime.dashboard?.snapshot
    val pendingIds = queue.ledger.pendingTasks.mapTo(mutableSetOf()) { it.taskId }
    val horizonTime = dashboard?.briefingUpdatedAtEpochMs ?: dashboard?.horizonLastSuccessAtEpochMs
    val horizonFreshness = horizonFreshness(horizonTime)
    val newsItems = parity.intelligence.snapshot?.items.orEmpty()
    val nutritionToday = parity.nutrition.snapshot?.today

    ScreenList {
        item {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.headlineMedium)
            Text(authSummary(runtime.auth), modifier = Modifier.padding(top = 4.dp))
        }
        item { QuoteCard() }
        item {
            Card(onClick = { navigate(briefing.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("HORIZON • $horizonFreshness", style = MaterialTheme.typography.titleMedium)
                    Text(
                        horizonTime?.let { "Loaded ${formatTime(it)} • ${runtime.briefing?.source ?: runtime.dashboard?.source ?: RuntimeDataSource.LIVE}" }
                            ?: "No canonical HORIZON date observed.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (horizonFreshness != "CURRENT") {
                        Text("A newer daily report has not been observed.", modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item { SectionTitle("Today's itinerary") }
        if (dashboard?.todayEvents.isNullOrEmpty()) {
            item { SummaryCard("No events today", "Your canonical Calendar currently shows a clear day.") }
        } else {
            items(dashboard!!.todayEvents.take(5)) { EventCard(it) }
        }
        item {
            Card(onClick = { navigate(tasks.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Google Tasks", style = MaterialTheme.typography.titleMedium)
                    val taskList = dashboard?.tasks.orEmpty()
                    if (taskList.isEmpty()) Text("No active Tasks returned.", modifier = Modifier.padding(top = 5.dp))
                    taskList.take(5).forEach { task ->
                        val queued = task.canonicalId in pendingIds
                        Text("• ${task.title}${if (queued) " • QUEUED" else ""}", modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Card(onClick = { navigate(nutrition.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nutrition", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatNumber(nutritionToday?.calories ?: dashboard?.totalCalories)} cal • " +
                            "${formatNumber(nutritionToday?.protein)}g protein • " +
                            "${formatNumber(nutritionToday?.carbs)}g carbs • ${formatNumber(nutritionToday?.fat)}g fat",
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        parity.nutrition.source?.name ?: humanizeToken(dashboard?.nutritionAdherence) ?: "Dashboard glance",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(onClick = { navigate(news.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Morning news", style = MaterialTheme.typography.titleMedium)
                    if (newsItems.isEmpty()) Text("No intelligence stories loaded.", modifier = Modifier.padding(top = 5.dp))
                    newsItems.take(4).forEach { story ->
                        Text("• ${story.title}", modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "${parity.intelligence.source?.name ?: "NOT LOADED"} • ${newsItems.size} stories",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(onClick = { navigate(capture.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Quick capture", style = MaterialTheme.typography.titleMedium)
                    Text("Note • Journal • Vent • Reflect • Assess • Meal • Receipt", modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item {
            val localAlerts = queue.ledger.alerts.count { !it.acknowledged }
            val captureAlerts = 0
            val serverAlerts = runtime.notifications?.snapshot?.active?.size ?: 0
            Card(onClick = { navigate(alerts.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Alerts & receipts", style = MaterialTheme.typography.titleMedium)
                    Text("${countLabel(serverAlerts + localAlerts + captureAlerts, "active alert")} • ${countLabel(queue.ledger.receipts.size, "task receipt")}", modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Check for latest") } }
    }
}

@Composable
private fun QuoteCard() {
    val seed = remember { (LocalDate.now().toEpochDay() % quotes.size).toInt().let { if (it < 0) it + quotes.size else it } }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5L * 60L * 1000L)
            step += 1
        }
    }
    val quote = quotes[(seed + step) % quotes.size]
    Card(
        modifier = Modifier.fillMaxWidth().clickable { step += 1 },
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuoteContent(quote)
        }
    }
}

@Composable
private fun QuoteContent(quote: Quote) {
    Text("“${quote.text}”", style = MaterialTheme.typography.titleMedium)
    Text("— ${quote.author}", modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CalendarScreen(
    parityState: ParityUiState,
    commandState: CalendarCommandRuntimeState,
    canMutate: Boolean,
    onShiftMonth: (Long) -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    onResolve: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    val snapshot = parityState.calendar.snapshot
    val byDate = snapshot?.events.orEmpty().groupBy { it.localDate }
    val month = parityState.selectedMonth
    val gridStart = remember(month) {
        val offset = (month.dayOfWeek.value - 1).toLong()
        month.minusDays(offset)
    }
    val selectedEvents = byDate[parityState.selectedDate.toString()].orEmpty()
    var text by remember { mutableStateOf("") }

    ScreenList {
        item { SourceCard("Calendar", parityState.calendar.source, parityState.calendar.fetchedAtEpochMs, parityState.calendar.error) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onShiftMonth(-1) }) { Icon(Icons.Outlined.KeyboardArrowLeft, "Previous month") }
                Column {
                    Text(month.format(monthFormatter), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onToday) { Text("Today") }
                }
                IconButton(onClick = { onShiftMonth(1) }) { Icon(Icons.Outlined.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
                    Box(Modifier.weight(1f).padding(2.dp)) { Text(day, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        items((0 until 6).toList()) { week ->
            Row(Modifier.fillMaxWidth()) {
                for (dayIndex in 0 until 7) {
                    val date = gridStart.plusDays((week * 7 + dayIndex).toLong())
                    val events = byDate[date.toString()].orEmpty()
                    val outside = date.month != month.month
                    val selected = date == parityState.selectedDate
                    Card(
                        onClick = { onSelectDate(date) },
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) {
                        Column(Modifier.height(92.dp).padding(5.dp)) {
                            Text(
                                date.dayOfMonth.toString() + if (selected) " •" else "",
                                style = if (outside) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            )
                            events.take(2).forEach { event ->
                                Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                            if (events.size > 2) Text("+${events.size - 2} more", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item { SectionTitle(parityState.selectedDate.format(dayFormatter)) }
        if (selectedEvents.isEmpty()) item { SummaryCard("No events", "No canonical events on this selected day.") }
        else items(selectedEvents) { CalendarRangeEventCard(it) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Calendar add", style = MaterialTheme.typography.titleMedium)
                    Text("Describe an event. AEGIS resolves a proposal first; nothing is written until you confirm it.", modifier = Modifier.padding(top = 4.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("add: Dentist Thursday 2 PM for 45 minutes") },
                        enabled = canMutate && commandState.progress !in setOf(CalendarCommandProgress.RESOLVING, CalendarCommandProgress.CREATING),
                    )
                    Button(
                        onClick = { onResolve(text) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = canMutate && text.isNotBlank() && commandState.progress !in setOf(CalendarCommandProgress.RESOLVING, CalendarCommandProgress.CREATING),
                    ) { Text(if (commandState.progress == CalendarCommandProgress.RESOLVING) "Resolving…" else "Resolve & preview") }
                    commandState.error?.let { Text("Error: $it", modifier = Modifier.padding(top = 7.dp)) }
                    commandState.lastMessage?.let { Text(it, modifier = Modifier.padding(top = 7.dp)) }
                }
            }
        }
        commandState.proposal?.let { proposal ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Review before creating", style = MaterialTheme.typography.titleMedium)
                        Text(proposal.title, modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.titleLarge)
                        Text("${proposal.start} → ${proposal.end}", modifier = Modifier.padding(top = 5.dp))
                        proposal.location?.let { Text("Location • $it", modifier = Modifier.padding(top = 4.dp)) }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Cancel proposal") }
                        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), enabled = canMutate && commandState.canCreate) {
                            Text(if (commandState.progress == CalendarCommandProgress.CREATING) "Adding…" else "Confirm add")
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh Calendar") } }
    }
}

@Composable
private fun TasksScreen(
    runtimeState: RuntimeUiState,
    parityState: ParityUiState,
    queueState: TaskQueueUiState,
    canMutate: Boolean,
    onStage: (String?, String) -> Unit,
    onUndo: (String) -> Unit,
    onSyncNow: () -> Unit,
    onHistoryDays: (Int) -> Unit,
) {
    val activeTasks = runtimeState.dashboard?.snapshot?.tasks.orEmpty()
    val pendingByTask = queueState.ledger.pendingTasks.associateBy { it.taskId }
    ScreenList {
        item { SourceCard("Google Tasks", runtimeState.dashboard?.source, runtimeState.dashboard?.fetchedAtEpochMs, runtimeState.dashboard?.error) }
        item {
            Text("Active Tasks • ${activeTasks.size}", style = MaterialTheme.typography.headlineMedium)
            Text("Checking a task queues completion locally. Undo remains available for five minutes before canonical synchronization.", modifier = Modifier.padding(top = 4.dp))
        }
        if (activeTasks.isEmpty()) item { SummaryCard("All clear", "No active Google Tasks returned.") }
        else items(activeTasks) { task ->
            val pending = task.canonicalId?.let(pendingByTask::get)
            TaskQueueCard(task, pending, canMutate, onStage, onUndo)
        }
        if (queueState.ledger.pendingTasks.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pending synchronization • ${queueState.ledger.pendingTasks.size}", style = MaterialTheme.typography.titleMedium)
                        queueState.ledger.pendingTasks.forEach { pending ->
                            val remaining = ((pending.syncAfterEpochMs - System.currentTimeMillis()).coerceAtLeast(0) / 60000) + 1
                            Text("• ${pending.title} • ~${remaining}m", modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
        queueState.lastMessage?.let { item { SummaryCard("Task status", it) } }
        item {
            Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth(), enabled = canMutate && !queueState.syncing) {
                Text(if (queueState.syncing) "Synchronizing…" else "Sync & refresh Tasks")
            }
        }
        item { SectionTitle("Completed task history") }
        if (!parityState.taskHistory.contractAvailable) {
            item { SummaryCard("History contract not deployed", parityState.taskHistory.error ?: "Active Tasks and delayed completion remain available.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = parityState.taskHistoryDays == 7, onClick = { onHistoryDays(7) }, label = { Text("7 days") })
                    FilterChip(selected = parityState.taskHistoryDays == 30, onClick = { onHistoryDays(30) }, label = { Text("30 days") })
                }
            }
            parityState.taskHistory.snapshot.orEmpty().let { history ->
                if (history.isEmpty()) item { SummaryCard("No completed tasks", "No completed items were returned for this period.") }
                else items(history) { item ->
                    SummaryCard(item.title, item.completedAtEpochMs?.let { "Completed ${formatTime(it)}" } ?: "Completed")
                }
            }
        }
    }
}

@Composable
private fun TaskQueueCard(
    task: DashboardTask,
    pending: PendingTaskMutation?,
    canMutate: Boolean,
    onStage: (String?, String) -> Unit,
    onUndo: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(if (pending != null) "Pending • Undo available until sync" else task.timeLabel ?: "Google Task") },
                leadingContent = {
                    Checkbox(
                        checked = pending != null,
                        onCheckedChange = { checked -> if (checked) onStage(task.canonicalId, task.title) },
                        enabled = canMutate && pending == null && task.canonicalId != null,
                    )
                },
            )
            if (pending != null) {
                OutlinedButton(onClick = { onUndo(pending.id) }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun CaptureScreen(
    state: CaptureUiState,
    canMutate: Boolean,
    onSubmit: (CaptureKind, String) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    var selected by remember { mutableStateOf(CaptureKind.NOTE) }
    var text by remember { mutableStateOf("") }
    ScreenList {
        item {
            Text("Capture", style = MaterialTheme.typography.headlineMedium)
            Text("Every submission receives a protected local receipt. Success requires a meaningful backend payload; non-idempotent capture is never blindly retried.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureKind.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { kind ->
                            FilterChip(selected = selected == kind, onClick = { selected = kind }, label = { Text(kind.displayName) })
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(8000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                label = { Text(selected.displayName) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                enabled = canMutate && !state.submitting,
            )
        }
        item {
            Button(
                onClick = {
                    onSubmit(selected, text)
                    text = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canMutate && text.isNotBlank() && !state.submitting,
            ) { Text(if (state.submitting) "Sending…" else "Submit to AEGIS") }
        }
        state.lastMessage?.let { item { SummaryCard("CONFIRMED", it) } }
        state.error?.let { item { SummaryCard("FAILED", it) } }
        item {
            OutlinedButton(onClick = onOpenAlerts, modifier = Modifier.fillMaxWidth()) {
                Text("Open Alerts & Receipts • ${state.ledger.receipts.size}")
            }
        }
    }
}

@Composable
private fun NewsScreen(state: ParityUiState, onRefresh: () -> Unit) {
    val snapshot = state.intelligence.snapshot
    val uriHandler = LocalUriHandler.current
    ScreenList {
        item { SourceCard("News & Intelligence", state.intelligence.source, state.intelligence.fetchedAtEpochMs, state.intelligence.error) }
        item {
            Text("News & Insights", style = MaterialTheme.typography.headlineMedium)
            Text("Live intelligence is independent of the frozen HORIZON report date. A failed source does not suppress healthy feeds.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            val successful = snapshot?.sourceHealth?.count { !it.status.equals("failed", true) }
            SummaryCard(
                "Pipeline • ${snapshot?.status ?: "not loaded"}",
                "${successful ?: "—"} / ${snapshot?.sourceCount ?: "—"} sources ready • ${countLabel(snapshot?.sourceErrors?.size ?: 0, "isolated failure")}",
            )
        }
        val categories = snapshot?.items.orEmpty().groupBy { it.category }
        if (categories.isEmpty()) item { SummaryCard("No stories loaded", "Refresh after authentication or use the protected cache when available.") }
        categories.forEach { (category, stories) ->
            item { SectionTitle(category.replace('-', ' ').uppercase()) }
            items(stories.take(10)) { story ->
                Card(
                    onClick = { story.link?.let(uriHandler::openUri) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(story.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${story.source}${story.publishedAtEpochMs?.let { " • ${formatTime(it)}" }.orEmpty()}") },
                    )
                }
            }
        }
        snapshot?.sourceHealth?.filter { it.status.equals("failed", true) }?.takeIf { it.isNotEmpty() }?.let { failures ->
            item { SectionTitle("Source health") }
            items(failures) { source -> SummaryCard(source.source, source.error ?: "Feed failed independently.") }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Check for latest news") } }
    }
}

@Composable
private fun NutritionScreen(runtime: RuntimeUiState, parity: ParityUiState, onDays: (Int) -> Unit) {
    val snapshot = parity.nutrition.snapshot
    val dashboardCalories = runtime.dashboard?.snapshot?.totalCalories
    val today = snapshot?.today
    val loggedDays = snapshot?.daily.orEmpty().filter { it.calories != null || (it.mealCount ?: 0) > 0 }
    fun average(value: (NutritionDay) -> Double?): Double? {
        val values = loggedDays.mapNotNull(value)
        return if (values.isEmpty()) null else values.average()
    }

    ScreenList {
        item { SourceCard("Nutrition", parity.nutrition.source ?: runtime.dashboard?.source, parity.nutrition.fetchedAtEpochMs ?: runtime.dashboard?.fetchedAtEpochMs, parity.nutrition.error) }
        item { Text("Nutrition Insights", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    Text("${formatNumber(today?.calories ?: dashboardCalories)} calories", modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.titleLarge)
                    Text("Protein ${formatNumber(today?.protein)}g • Carbs ${formatNumber(today?.carbs)}g • Fat ${formatNumber(today?.fat)}g", modifier = Modifier.padding(top = 5.dp))
                    Text("Sodium ${formatNumber(today?.sodium)} mg • Meals ${today?.mealCount ?: "—"}", modifier = Modifier.padding(top = 4.dp))
                    snapshot?.calorieTarget?.let { Text("Calorie target ${formatNumber(it)}", modifier = Modifier.padding(top = 4.dp)) }
                    snapshot?.proteinTarget?.let { Text("Protein target ${formatNumber(it)}g", modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        if (!parity.nutrition.contractAvailable) {
            item { SummaryCard("Detailed history unavailable", "The backend has not advertised ux_contracts.nutrition_history_v1. Missing macros remain unavailable rather than being invented as zero.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = parity.nutritionDays == 7, onClick = { onDays(7) }, label = { Text("7 days") })
                    FilterChip(selected = parity.nutritionDays == 30, onClick = { onDays(30) }, label = { Text("30 days") })
                }
            }
            item {
                SummaryCard(
                    countLabel(loggedDays.size, "logged day"),
                    "Avg ${formatNumber(average { it.calories })} cal • ${formatNumber(average { it.protein })}g protein • ${formatNumber(average { it.carbs })}g carbs • ${formatNumber(average { it.fat })}g fat",
                )
            }
            snapshot?.daily?.takeLast(if (parity.nutritionDays == 7) 7 else 14)?.forEach { day ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(day.date, style = MaterialTheme.typography.labelLarge)
                            Text("${formatNumber(day.calories)} cal • ${formatNumber(day.protein)}g P", modifier = Modifier.padding(top = 3.dp))
                            val target = snapshot.calorieTarget
                            if (target != null && target > 0 && day.calories != null) {
                                LinearProgressIndicator(
                                    progress = { (day.calories / target).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Meal history") }
            if (snapshot?.meals.isNullOrEmpty()) item { SummaryCard("No meals returned", "No meal history is available for this range.") }
            else items(snapshot!!.meals.take(50)) { meal ->
                SummaryCard(
                    meal.item,
                    listOfNotNull(meal.date, meal.time, meal.portion, meal.calories?.let { "${formatNumber(it)} cal" }, meal.status).joinToString(" • "),
                )
            }
            if ((snapshot?.pendingEstimateCount ?: 0) > 0) item { SummaryCard("Pending estimates", "${countLabel(snapshot?.pendingEstimateCount ?: 0, "meal estimate")} still pending.") }
        }
    }
}

@Composable
private fun AlertsScreen(
    runtimeState: RuntimeUiState,
    captureState: CaptureUiState,
    taskQueueState: TaskQueueUiState,
    notificationCommandState: NotificationCommandRuntimeState,
    canMutate: Boolean,
    onLocalAlertAck: (String) -> Unit,
    onServerAck: (String) -> Unit,
) {
    val localLedger = mergeLedger(captureState, taskQueueState)
    val server = runtimeState.notifications?.snapshot?.active.orEmpty()
    ScreenList {
        item { SourceCard("Server Alerts", runtimeState.notifications?.source, runtimeState.notifications?.fetchedAtEpochMs, runtimeState.notifications?.error) }
        item { Text("Alerts & Receipts", style = MaterialTheme.typography.headlineMedium) }
        item { SummaryCard("Active attention", "${server.size} server • ${localLedger.first.count { !it.acknowledged }} local • ${localLedger.second.size} recent receipts") }
        server.sortedBy { severityRank(it.severity) }.forEach { notification ->
            item { ServerAlertCard(notification, canMutate && notification.id !in notificationCommandState.submittingIds, onServerAck) }
        }
        localLedger.first.filterNot { it.acknowledged }.forEach { alert ->
            item { LocalAlertCard(alert, onLocalAlertAck) }
        }
        item { SectionTitle("Recent receipts") }
        if (localLedger.second.isEmpty()) item { SummaryCard("No local receipts yet", "Capture and delayed Task changes will appear here.") }
        else items(localLedger.second.take(40)) { receipt -> ReceiptCard(receipt) }
    }
}

@Composable
private fun BriefingScreen(runtime: RuntimeUiState) {
    val raw = runtime.briefing?.plainText ?: runtime.dashboard?.snapshot?.briefingPlainText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }
    ScreenList {
        item { SourceCard("HORIZON", runtime.briefing?.source ?: runtime.dashboard?.source, runtime.briefing?.fetchedAtEpochMs ?: runtime.dashboard?.fetchedAtEpochMs, runtime.briefing?.error) }
        item { Text("Full canonical HORIZON", style = MaterialTheme.typography.headlineMedium) }
        if (document == null) item { SummaryCard("No report loaded", "Check for latest from Home. Ordinary refresh never generates HORIZON.") }
        else {
            document.preamble.takeIf { it.isNotEmpty() }?.let { lines -> item { Text(lines.joinToString("\n")) } }
            items(document.sections) { section ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        (section.lines + section.subsections.flatMap { it.lines })
                            .map(HorizonDocumentParser::displayLine)
                            .filter(String::isNotBlank)
                            .forEach { Text("• $it", modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
        }
        item { SummaryCard("Generation boundary", "This checkpoint keeps HORIZON generation gated. Refresh is always read-only; generation will arrive behind the Windows-equivalent replacement confirmations.") }
    }
}

@Composable
private fun FinanceScreen(runtime: RuntimeUiState) {
    val finance = runtime.finance
    ScreenList {
        item { SourceCard("SENTINEL-FIN", finance?.source, finance?.fetchedAtEpochMs, finance?.error) }
        item { Text("Recent Finances", style = MaterialTheme.typography.headlineMedium) }
        finance?.snapshot?.let { snapshot ->
            item { SummaryCard("Last ${snapshot.hours} hours", "Purchases ${snapshot.summary.purchaseTotal?.let(currencyFormatter::format) ?: "—"} • Credits ${snapshot.summary.creditTotal?.let(currencyFormatter::format) ?: "—"}") }
            items(snapshot.transactions) { tx -> SummaryCard(tx.vendor, "${tx.category} • ${tx.paymentSource} • ${currencyFormatter.format(tx.amount)}") }
        } ?: item { SummaryCard("Finance not loaded", "Sync current canonical data.") }
    }
}

private data class SearchHit(val kind: String, val title: String, val detail: String, val route: String)

@Composable
private fun SearchScreen(runtime: RuntimeUiState, parity: ParityUiState, navigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val hits = remember(runtime.dashboard, runtime.briefing, runtime.finance, runtime.notifications, parity.calendar, parity.intelligence) {
        buildSearchHits(runtime, parity)
    }
    val filtered = remember(query, hits) {
        val q = query.trim().lowercase()
        if (q.isBlank()) hits.take(20) else hits.filter { "${it.kind} ${it.title} ${it.detail}".lowercase().contains(q) }.take(60)
    }
    ScreenList {
        item {
            Text("Search AEGIS", style = MaterialTheme.typography.headlineMedium)
            Text("Searches only synchronized local data. It never invokes Gemini or a network refresh.", modifier = Modifier.padding(top = 4.dp))
        }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Calendar, Tasks, HORIZON, News, Finance, Alerts") }) }
        if (filtered.isEmpty()) item { SummaryCard("No matches", "Try another term or refresh synchronized data.") }
        else items(filtered) { hit ->
            Card(onClick = { navigate(hit.route) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(headlineContent = { Text(hit.title) }, supportingContent = { Text("${hit.kind} • ${hit.detail}") })
            }
        }
    }
}

@Composable
private fun MoreScreen(navigate: (String) -> Unit) {
    ScreenList {
        item { Text("More", style = MaterialTheme.typography.headlineMedium) }
        items(secondaryDestinations) { destination ->
            Card(onClick = { navigate(destination.route) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(destination.title) },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun SystemScreen(
    runtimeState: RuntimeUiState,
    parityState: ParityUiState,
    selectedTheme: GposThemeOption,
    notificationPermissionGranted: Boolean,
    onThemeSelected: (GposThemeOption) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onNotificationPermission: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefresh: () -> Unit,
) {
    ScreenList {
        item { Text("Diagnostics & Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            SummaryCard(
                "AEGIS Android ${BuildConfig.VERSION_NAME}",
                "Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT} • ${Build.SUPPORTED_ABIS.joinToString()}",
            )
        }
        item {
            when (val auth = runtimeState.auth) {
                is AuthState.Authenticated -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Google • authenticated", style = MaterialTheme.typography.titleMedium)
                        Text(auth.user.name ?: auth.user.email, modifier = Modifier.padding(top = 4.dp))
                        Text(auth.user.email, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign out and clear protected local state") }
                    }
                }
                is AuthState.OfflineRestored -> SummaryCard("Offline protected session", "Cached reads are available; mutations remain disabled until live authentication returns.")
                is AuthState.Error -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Authentication needs attention", style = MaterialTheme.typography.titleMedium)
                        Text(auth.message, modifier = Modifier.padding(top = 5.dp))
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Authenticate") }
                    }
                }
                AuthState.SignedOut -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Google • signed out", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign in with Google") }
                    }
                }
                AuthState.Restoring, AuthState.Authenticating -> SummaryCard("Authentication", "Restoring or completing the protected session…")
            }
        }
        item {
            val caps = parityState.capabilities
            SummaryCard(
                "Backend capabilities",
                if (caps == null) parityState.capabilityError ?: "Capabilities not loaded."
                else "tasks_history_v1=${caps.tasksHistoryV1} • nutrition_history_v1=${caps.nutritionHistoryV1} • ${caps.advertisedFeatures.size} advertised feature(s)",
            )
        }
        item { SectionTitle("Protected cache states") }
        item { SourceCard("Dashboard", runtimeState.dashboard?.source, runtimeState.dashboard?.fetchedAtEpochMs, runtimeState.dashboard?.error) }
        item { SourceCard("Calendar", parityState.calendar.source, parityState.calendar.fetchedAtEpochMs, parityState.calendar.error) }
        item { SourceCard("HORIZON", runtimeState.briefing?.source, runtimeState.briefing?.fetchedAtEpochMs, runtimeState.briefing?.error) }
        item { SourceCard("News", parityState.intelligence.source, parityState.intelligence.fetchedAtEpochMs, parityState.intelligence.error) }
        item { SourceCard("Nutrition", parityState.nutrition.source, parityState.nutrition.fetchedAtEpochMs, parityState.nutrition.error) }
        item { SourceCard("Finance", runtimeState.finance?.source, runtimeState.finance?.fetchedAtEpochMs, runtimeState.finance?.error) }
        item { SourceCard("Notifications", runtimeState.notifications?.source, runtimeState.notifications?.fetchedAtEpochMs, runtimeState.notifications?.error) }
        item { SummaryCard("Background reads", "Every ${CanonicalSyncScheduler.REPEAT_MINUTES} minutes when connected and battery is not low. Routine refresh never triggers HORIZON generation or capture mutations.") }
        item { SummaryCard("Task completion queue", "Five-minute local grace period; WorkManager periodic safety net every ${TaskQueueSyncScheduler.PERIODIC_MINUTES} minutes. Canonical completion is verified before receipt confirmation.") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Android notifications", style = MaterialTheme.typography.titleMedium)
                    Text(if (notificationPermissionGranted) "Enabled" else "Permission disabled", modifier = Modifier.padding(top = 4.dp))
                    if (!notificationPermissionGranted) Button(onClick = onNotificationPermission, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Enable notifications") }
                }
            }
        }
        item { OutlinedButton(onClick = onRefreshBackend, modifier = Modifier.fillMaxWidth()) { Text("Recheck backend") } }
        item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Sync AEGIS") } }
        item { SectionTitle("Appearance") }
        items(GposThemeOption.entries) { option ->
            Card(onClick = { onThemeSelected(option) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    supportingContent = { Text(option.description) },
                    leadingContent = { RadioButton(selected = option == selectedTheme, onClick = { onThemeSelected(option) }) },
                )
            }
        }
    }
}

@Composable
private fun EventCard(event: DashboardEvent) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(event.timeLabel + event.note?.let { " • $it" }.orEmpty()) },
            leadingContent = { Icon(Icons.Outlined.Event, null) },
        )
    }
}

@Composable
private fun CalendarRangeEventCard(event: CalendarRangeEvent) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        if (event.allDay) "All day" else event.localTime,
                        event.location,
                        event.description,
                    ).joinToString(" • ").ifBlank { "Calendar event" },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@Composable
private fun ServerAlertCard(notification: ServerNotification, enabled: Boolean, onAck: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(notification.title) },
                supportingContent = { Text(listOfNotNull(notification.message.takeIf(String::isNotBlank), notification.detail, notification.type).joinToString(" • ")) },
                leadingContent = { Text(severityLabel(notification.severity)) },
            )
            OutlinedButton(onClick = { onAck(notification.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Text("Acknowledge") }
        }
    }
}

@Composable
private fun LocalAlertCard(alert: LocalAlert, onAck: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(headlineContent = { Text(alert.title) }, supportingContent = { Text(alert.detail) }, leadingContent = { Text(alert.severity.uppercase()) })
            OutlinedButton(onClick = { onAck(alert.id) }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ReceiptCard(receipt: LocalReceipt) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(receipt.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(listOfNotNull(receipt.kind, receipt.result, receipt.error, formatTime(receipt.updatedAtEpochMs)).joinToString(" • ")) },
            trailingContent = { Text(receipt.state.name) },
        )
    }
}

@Composable
private fun SourceCard(label: String, source: RuntimeDataSource?, fetched: Long?, error: String?) {
    SummaryCard(
        "$label • ${source?.name ?: "NOT LOADED"}",
        buildString {
            append(fetched?.let { "Updated ${formatTime(it)}" } ?: "No protected payload")
            if (!error.isNullOrBlank()) append(" • $error")
        },
    )
}

@Composable
private fun SummaryCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) })
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun ScreenList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private fun horizonFreshness(epochMs: Long?): String {
    if (epochMs == null) return "UNKNOWN"
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "CURRENT"
        date == today.minusDays(1) -> "PREVIOUS DAY"
        date.isBefore(today.minusDays(1)) -> "STALE"
        else -> "UNKNOWN"
    }
}

private fun authSummary(auth: AuthState): String = when (auth) {
    is AuthState.Authenticated -> "Connected as ${auth.user.name ?: auth.user.email}"
    is AuthState.OfflineRestored -> "OFFLINE • protected last-known-good data available"
    is AuthState.Error -> "Authentication needs attention"
    AuthState.Authenticating -> "Authenticating…"
    AuthState.Restoring -> "Restoring protected session…"
    AuthState.SignedOut -> "Signed out"
}

private fun formatTime(epochMs: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMs))
private fun formatNumber(value: Double?): String = value?.let { if (abs(it % 1.0) < 0.001) it.toInt().toString() else "%.1f".format(it) } ?: "—"

private fun severityRank(severity: NotificationSeverity): Int = when (severity) {
    NotificationSeverity.CRITICAL -> 0
    NotificationSeverity.WARNING -> 1
    NotificationSeverity.INFO -> 2
    NotificationSeverity.UNKNOWN -> 3
}

private fun severityLabel(severity: NotificationSeverity): String = when (severity) {
    NotificationSeverity.CRITICAL -> "CRIT"
    NotificationSeverity.WARNING -> "WARN"
    NotificationSeverity.INFO -> "INFO"
    NotificationSeverity.UNKNOWN -> "?"
}

private fun mergeLedger(capture: CaptureUiState, tasks: TaskQueueUiState): Pair<List<LocalAlert>, List<LocalReceipt>> {
    val alerts = (capture.ledger.alerts + tasks.ledger.alerts).distinctBy { it.id }.sortedByDescending { it.createdAtEpochMs }
    val receipts = (capture.ledger.receipts + tasks.ledger.receipts).distinctBy { it.id }.sortedByDescending { it.updatedAtEpochMs }
    return alerts to receipts
}

private fun buildSearchHits(runtime: RuntimeUiState, parity: ParityUiState): List<SearchHit> = buildList {
    parity.calendar.snapshot?.events.orEmpty().forEach { event ->
        add(SearchHit("Calendar", event.title, listOfNotNull(event.localDate, event.localTime, event.location).joinToString(" • "), calendar.route))
    }
    runtime.dashboard?.snapshot?.tasks.orEmpty().forEach { task ->
        add(SearchHit("Task", task.title, task.timeLabel ?: "Active Google Task", tasks.route))
    }
    parity.intelligence.snapshot?.items.orEmpty().forEach { story ->
        add(SearchHit("News • ${story.source}", story.title, story.category, news.route))
    }
    runtime.finance?.snapshot?.transactions.orEmpty().forEach { transaction ->
        add(SearchHit("Finance", transaction.vendor, "${transaction.category} • ${currencyFormatter.format(transaction.amount)}", finances.route))
    }
    runtime.notifications?.snapshot?.active.orEmpty().forEach { notification ->
        add(SearchHit("Alert", notification.title, notification.message, alerts.route))
    }
    val raw = runtime.briefing?.plainText ?: runtime.dashboard?.snapshot?.briefingPlainText
    raw?.let(HorizonDocumentParser::parse)?.sections.orEmpty().forEach { section ->
        add(SearchHit("HORIZON", section.title, section.lines.take(2).joinToString(" • "), briefing.route))
    }
}
