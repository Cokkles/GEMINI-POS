package com.cokkles.gpos.ui.daily

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cokkles.gpos.AegisInteractionUiState
import com.cokkles.gpos.BuildConfig
import com.cokkles.gpos.CaptureUiState
import com.cokkles.gpos.ParityUiState
import com.cokkles.gpos.TaskQueueUiState
import com.cokkles.gpos.data.command.CaptureKind
import com.cokkles.gpos.data.command.NotificationCommandRuntimeState
import com.cokkles.gpos.data.interaction.AegisFollowup
import com.cokkles.gpos.data.interaction.AiChatMessage
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalReceipt
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
private val askAegis = Destination("aegis", "Ask AEGIS", Icons.Outlined.Description)
private val followups = Destination("followups", "Follow-ups", Icons.Outlined.AssignmentTurnedIn)
private val news = Destination("news", "News & Insights", Icons.Outlined.Article)
private val nutrition = Destination("nutrition", "Nutrition", Icons.Outlined.Fastfood)
private val alerts = Destination("alerts", "Alerts & Receipts", Icons.Outlined.Notifications)
private val briefing = Destination("briefing", "HORIZON", Icons.Outlined.Description)
private val finances = Destination("finances", "Finances", Icons.Outlined.AccountBalanceWallet)
private val search = Destination("search", "Search", Icons.Outlined.Search)
private val system = Destination("system", "Settings", Icons.Outlined.Settings)

private val bottomDestinations = listOf(home, calendar, tasks, capture, more)
private val secondaryDestinations = listOf(askAegis, followups, news, nutrition, alerts, briefing, finances, search, system)
private val allDestinations = bottomDestinations + secondaryDestinations

private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d • h:mm a").withZone(ZoneId.systemDefault())
private val currencyFormatter = NumberFormat.getCurrencyInstance()
private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

private data class Quote(val text: String, val author: String)

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
fun DailyUxApp(
    runtimeState: RuntimeUiState,
    parityState: ParityUiState,
    taskQueueState: TaskQueueUiState,
    captureState: CaptureUiState,
    interactionState: AegisInteractionUiState,
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
    onTaskCreate: (String, String) -> Unit,
    onCaptureSubmit: (CaptureKind, String) -> Unit,
    onLocalAlertAck: (String) -> Unit,
    onServerNotificationAck: (String) -> Unit,
    onInteractionRefresh: () -> Unit,
    onFollowupResolve: (AegisFollowup) -> Unit,
    onFollowupDismiss: (AegisFollowup) -> Unit,
    onFollowupPromote: (AegisFollowup) -> Unit,
    onAiMode: (String) -> Unit,
    onAskAegis: (String) -> Unit,
    onClearAiChat: () -> Unit,
    onCalendarAsk: (String) -> Unit,
    onCalendarConfirm: () -> Unit,
    onCalendarCancel: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: home.route
    val currentDestination = allDestinations.firstOrNull { it.route == currentRoute }
        ?: if (currentRoute == "notifications") alerts else home
    val selectedBottom = if (secondaryDestinations.any { it.route == currentRoute } || currentRoute == "notifications") more.route else currentRoute
    val authenticated = runtimeState.auth is AuthState.Authenticated

    fun navigate(route: String) {
        val mapped = if (route == "notifications") alerts.route else route
        if (navController.currentDestination?.route == mapped) return
        navController.navigate(mapped) {
            if (bottomDestinations.any { it.route == mapped }) {
                // Tabs always open their named root; never resurrect a shortcut's child stack.
                popUpTo(navController.graph.findStartDestination().id)
            }
            launchSingleTop = true
        }
    }

    LaunchedEffect(runtimeState.auth) {
        if (runtimeState.auth is AuthState.Authenticated) {
            onParityRefresh()
            onInteractionRefresh()
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
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("AEGIS")
                            Text(currentDestination.title, modifier = Modifier.testTag("current_destination"), style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        TextButton(onClick = { navigate(system.route) }) {
                            Text(connectionLabel(runtimeState.auth), style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = onRefreshCanonical, enabled = !runtimeState.refreshing && !parityState.refreshing) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh AEGIS")
                        }
                    },
                )
                if (runtimeState.refreshing || parityState.refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedBottom == destination.route,
                        onClick = { navigate(destination.route) },
                        modifier = Modifier.testTag("nav_${destination.route}"),
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
                    interactionState = interactionState,
                    canMutate = authenticated,
                    onShiftMonth = onShiftMonth,
                    onToday = onTodayMonth,
                    onSelectDate = onSelectDate,
                    onRefresh = onRefreshCalendar,
                    onAsk = onCalendarAsk,
                    onConfirm = onCalendarConfirm,
                    onCancel = onCalendarCancel,
                )
            }
            composable(tasks.route) {
                TasksScreen(
                    runtimeState = runtimeState,
                    parityState = parityState,
                    queueState = taskQueueState,
                    interactionState = interactionState,
                    canMutate = authenticated,
                    onStage = onTaskStage,
                    onUndo = onTaskUndo,
                    onSyncNow = onTaskSyncNow,
                    onHistoryDays = onTaskHistoryDays,
                    onCreate = onTaskCreate,
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
            composable(askAegis.route) {
                AskAegisScreen(interactionState, authenticated, onAiMode, onAskAegis, onClearAiChat)
            }
            composable(followups.route) {
                FollowupsScreen(
                    interactionState,
                    authenticated,
                    onInteractionRefresh,
                    onFollowupResolve,
                    onFollowupDismiss,
                    onFollowupPromote,
                )
            }
            composable(news.route) { NewsScreen(parityState, onRefreshIntelligence) }
            composable(nutrition.route) { NutritionScreen(runtimeState, parityState, onNutritionDays) }
            composable(alerts.route) {
                AlertsScreen(
                    runtimeState,
                    captureState,
                    taskQueueState,
                    notificationCommandState,
                    authenticated,
                    onLocalAlertAck,
                    onServerNotificationAck,
                )
            }
            composable("notifications") {
                AlertsScreen(
                    runtimeState,
                    captureState,
                    taskQueueState,
                    notificationCommandState,
                    authenticated,
                    onLocalAlertAck,
                    onServerNotificationAck,
                )
            }
            composable(briefing.route) { BriefingScreen(runtimeState) }
            composable(finances.route) { FinanceScreen(runtimeState) }
            composable(search.route) { SearchScreen(runtimeState, parityState, interactionState, ::navigate) }
            composable(system.route) {
                SystemScreen(
                    runtimeState,
                    parityState,
                    interactionState,
                    selectedTheme,
                    notificationPermissionGranted,
                    onThemeSelected,
                    onSignIn,
                    onSignOut,
                    onNotificationPermission,
                    onRefreshBackend,
                    onRefreshCanonical,
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
    val newsItems = parity.intelligence.snapshot?.items.orEmpty()
    val nutritionToday = parity.nutrition.snapshot?.today
    val uriHandler = LocalUriHandler.current

    ScreenList {
        item {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.headlineMedium)
            Text("Your day at a glance", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
        }
        item { QuoteCard() }
        item {
            Card(onClick = { navigate(briefing.route) }, modifier = Modifier.fillMaxWidth().testTag("home_briefing")) {
                Column(Modifier.padding(16.dp)) {
                    Text("HORIZON • ${humanizeToken(horizonFreshness(horizonTime)) ?: "Unknown"}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        horizonTime?.let { "Updated ${formatTime(it)}" } ?: "No canonical report loaded yet.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item { SectionTitle("Today") }
        if (dashboard?.todayEvents.isNullOrEmpty()) {
            item { SummaryCard("No events today", "Your Calendar is clear.") }
        } else {
            items(dashboard!!.todayEvents.take(5)) { EventCard(it) }
        }
        item {
            Card(onClick = { navigate(tasks.route) }, modifier = Modifier.fillMaxWidth().testTag("home_tasks")) {
                Column(Modifier.padding(16.dp)) {
                    val taskList = dashboard?.tasks.orEmpty()
                    Text("Tasks • ${taskList.size}", style = MaterialTheme.typography.titleMedium)
                    if (taskList.isEmpty()) Text("No active Google Tasks.", modifier = Modifier.padding(top = 5.dp))
                    taskList.take(4).forEach { task ->
                        val pending = task.canonicalId in pendingIds
                        Text(
                            "• ${task.title}${if (pending) " • Pending" else ""}",
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                            "${formatNumber(nutritionToday?.protein)}g protein • ${formatNumber(nutritionToday?.carbs)}g carbs • ${formatNumber(nutritionToday?.fat)}g fat",
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        humanizeToken(dashboard?.nutritionAdherence) ?: "Today's nutrition glance",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { SectionTitle("Headliners") }
        if (newsItems.isEmpty()) {
            item { SummaryCard("No headliners loaded", "Open News & Insights or refresh AEGIS.") }
        } else {
            items(newsItems.take(4)) { story ->
                StoryCard(story) { story.link?.let(uriHandler::openUri) }
            }
            item {
                TextButton(onClick = { navigate(news.route) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open all News & Insights")
                }
            }
        }
        item {
            val localAlerts = queue.ledger.alerts.count { !it.acknowledged }
            val serverAlerts = runtime.notifications?.snapshot?.active?.size ?: 0
            Card(onClick = { navigate(alerts.route) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Attention", style = MaterialTheme.typography.titleMedium)
                    Text(countLabel(serverAlerts + localAlerts, "active alert"), modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh my day") } }
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
    Card(modifier = Modifier.fillMaxWidth().clickable { step += 1 }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("“${quote.text}”", style = MaterialTheme.typography.titleMedium)
            Text("— ${quote.author}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CalendarScreen(
    parityState: ParityUiState,
    interactionState: AegisInteractionUiState,
    canMutate: Boolean,
    onShiftMonth: (Long) -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    onAsk: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val snapshot = parityState.calendar.snapshot
    val byDate = remember(snapshot) { snapshot?.events.orEmpty().groupBy { it.localDate } }
    val month = parityState.selectedMonth
    val gridStart = remember(month) { month.minusDays((month.dayOfWeek.value - 1).toLong()) }
    val selectedEvents = byDate[parityState.selectedDate.toString()].orEmpty()
    var text by remember { mutableStateOf("") }
    val calendarState = interactionState.calendar
    val canUseV2 = interactionState.capabilities?.calendarAiV2 == true

    ScreenList {
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
                    Card(onClick = { onSelectDate(date) }, modifier = Modifier.weight(1f).padding(2.dp)) {
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
        if (selectedEvents.isEmpty()) item { SummaryCard("No events", "Nothing is scheduled on this day.") }
        else items(selectedEvents) { CalendarRangeEventCard(it) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ask Calendar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ask what's scheduled, or describe a create, move, edit, or delete. A change is never applied until you confirm the preview.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(4000) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        minLines = 2,
                        label = { Text("e.g. Move dentist tomorrow from 2 PM to 3 PM") },
                        enabled = canMutate && canUseV2 && !calendarState.submitting,
                    )
                    Button(
                        onClick = { onAsk(text) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = canMutate && canUseV2 && text.isNotBlank() && !calendarState.submitting,
                    ) { Text(if (calendarState.submitting && calendarState.result == null) "Checking…" else "Review request") }
                    if (!canUseV2) {
                        Text("Conversational Calendar is not advertised by the current backend.", modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        calendarState.answer?.let { answer -> item { SummaryCard("AEGIS Calendar", answer) } }
        calendarState.error?.let { error -> item { SummaryCard("Calendar needs attention", error) } }
        calendarState.result?.let { result ->
            if (result.candidates.isNotEmpty()) {
                item { SectionTitle("Possible matches") }
                items(result.candidates) { candidate ->
                    SummaryCard(candidate.title, listOfNotNull(candidate.start, candidate.end).joinToString(" → "))
                }
            }
            if (result.confirmationRequired && result.confirmationToken != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Confirm ${humanizeToken(result.operation) ?: "Calendar change"}", style = MaterialTheme.typography.titleMedium)
                            result.previewSummary?.let { Text(it, modifier = Modifier.padding(top = 7.dp)) }
                            Text("Nothing has been changed yet.", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !calendarState.submitting) { Text("Cancel") }
                            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), enabled = canMutate && !calendarState.submitting) {
                                Text(if (calendarState.submitting) "Applying…" else "Confirm change")
                            }
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
    interactionState: AegisInteractionUiState,
    canMutate: Boolean,
    onStage: (String?, String) -> Unit,
    onUndo: (String) -> Unit,
    onSyncNow: () -> Unit,
    onHistoryDays: (Int) -> Unit,
    onCreate: (String, String) -> Unit,
) {
    val activeTasks = runtimeState.dashboard?.snapshot?.tasks.orEmpty()
    val pendingByTask = queueState.ledger.pendingTasks.associateBy { it.taskId }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val canCreate = interactionState.capabilities?.taskActionV1 == true

    ScreenList {
        item {
            Text("Google Tasks", style = MaterialTheme.typography.headlineMedium)
            Text("Add a task, check one off, or undo a completion before it synchronizes.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add Task", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(500) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Task") },
                        enabled = canMutate && canCreate && !interactionState.taskSubmitting,
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(4000) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Notes (optional)") },
                        minLines = 2,
                        enabled = canMutate && canCreate && !interactionState.taskSubmitting,
                    )
                    Button(
                        onClick = {
                            onCreate(title, notes)
                            title = ""
                            notes = ""
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = canMutate && canCreate && title.isNotBlank() && !interactionState.taskSubmitting,
                    ) { Text(if (interactionState.taskSubmitting) "Adding…" else "Add to Google Tasks") }
                    if (!canCreate) Text("Task creation is not advertised by this backend.", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        interactionState.taskMessage?.let { item { SummaryCard("Task added", it) } }
        interactionState.taskError?.let { item { SummaryCard("Task could not be added", it) } }
        item { SectionTitle("Active • ${activeTasks.size}") }
        if (activeTasks.isEmpty()) item { SummaryCard("All clear", "No active Google Tasks returned.") }
        else items(activeTasks) { task ->
            val pending = task.canonicalId?.let(pendingByTask::get)
            TaskQueueCard(task, pending, canMutate, onStage, onUndo)
        }
        if (queueState.ledger.pendingTasks.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Waiting to sync • ${queueState.ledger.pendingTasks.size}", style = MaterialTheme.typography.titleMedium)
                        queueState.ledger.pendingTasks.forEach { pending ->
                            val remaining = ((pending.syncAfterEpochMs - System.currentTimeMillis()).coerceAtLeast(0) / 60000) + 1
                            Text("• ${pending.title} • about ${remaining}m", modifier = Modifier.padding(top = 5.dp))
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
        item { SectionTitle("Completed history") }
        if (!parityState.taskHistory.contractAvailable) {
            item { SummaryCard("History unavailable", "Active Tasks and delayed completion still work normally.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(parityState.taskHistoryDays == 7, { onHistoryDays(7) }, label = { Text("7 days") })
                    FilterChip(parityState.taskHistoryDays == 30, { onHistoryDays(30) }, label = { Text("30 days") })
                }
            }
            val history = parityState.taskHistory.snapshot.orEmpty()
            if (history.isEmpty()) item { SummaryCard("No completed tasks", "No completed items were returned for this period.") }
            else items(history) { item -> SummaryCard(item.title, item.completedAtEpochMs?.let { "Completed ${formatTime(it)}" } ?: "Completed") }
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
                supportingContent = { Text(if (pending != null) "Pending • Undo available" else task.timeLabel ?: "Google Task") },
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
    val primaryKinds = listOf(CaptureKind.NOTE, CaptureKind.JOURNAL, CaptureKind.CALORIES, CaptureKind.RECEIPT)
    var selected by remember { mutableStateOf(CaptureKind.NOTE) }
    var text by remember { mutableStateOf("") }
    val hint = when (selected) {
        CaptureKind.NOTE -> "Save a note or idea"
        CaptureKind.JOURNAL -> "Write a journal entry"
        CaptureKind.CALORIES -> "Describe what you ate or drank"
        CaptureKind.RECEIPT -> "Record an expense, e.g. Target $45.20 groceries"
        else -> selected.displayName
    }

    ScreenList {
        item {
            Text("Capture", style = MaterialTheme.typography.headlineMedium)
            Text("Choose what you're recording, submit it, and AEGIS will confirm when it is saved.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryKinds.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { kind ->
                            FilterChip(
                                selected = selected == kind,
                                onClick = { selected = kind },
                                label = { Text(primaryCaptureLabel(kind)) },
                                modifier = Modifier.weight(1f),
                            )
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
                minLines = 6,
                label = { Text(hint) },
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
            ) { Text(if (state.submitting) "Saving…" else "Save ${primaryCaptureLabel(selected)}") }
        }
        state.lastMessage?.let { item { SummaryCard("Saved ✓", it) } }
        state.error?.let { item { SummaryCard("Could not save", it) } }
        item {
            OutlinedButton(onClick = onOpenAlerts, modifier = Modifier.fillMaxWidth()) {
                Text("View confirmations & receipts • ${state.ledger.receipts.size}")
            }
        }
    }
}

@Composable
private fun AskAegisScreen(
    state: AegisInteractionUiState,
    authenticated: Boolean,
    onMode: (String) -> Unit,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val modes = listOf("general", "career", "finance", "logistics", "system")
    ScreenList {
        item {
            Text("Ask AEGIS", style = MaterialTheme.typography.headlineMedium)
            Text("Read-only advice using current AEGIS context. This conversation stays in this app session and does not create durable memory.", modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.take(3).forEach { mode ->
                    FilterChip(state.aiMode == mode, { onMode(mode) }, label = { Text(humanizeToken(mode) ?: mode) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.drop(3).forEach { mode ->
                    FilterChip(state.aiMode == mode, { onMode(mode) }, label = { Text(humanizeToken(mode) ?: mode) })
                }
            }
        }
        if (state.aiMessages.isEmpty()) item { SummaryCard("Ready", "Ask about your current day, career, finances, logistics, or AEGIS system state.") }
        else items(state.aiMessages) { message -> ChatCard(message) }
        state.aiError?.let { item { SummaryCard("Ask AEGIS needs attention", it) } }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(4000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Ask a question") },
                enabled = authenticated && state.capabilities?.aiQueryV1 == true && !state.aiSubmitting,
            )
        }
        item {
            Button(
                onClick = {
                    onAsk(text)
                    text = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = authenticated && state.capabilities?.aiQueryV1 == true && text.isNotBlank() && !state.aiSubmitting,
            ) { Text(if (state.aiSubmitting) "Thinking…" else "Ask AEGIS") }
        }
        if (state.aiMessages.isNotEmpty()) item { TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear session chat") } }
    }
}

@Composable
private fun ChatCard(message: AiChatMessage) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(if (message.role.equals("assistant", true)) "AEGIS" else "You", style = MaterialTheme.typography.labelLarge)
            Text(message.text, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FollowupsScreen(
    state: AegisInteractionUiState,
    authenticated: Boolean,
    onRefresh: () -> Unit,
    onResolve: (AegisFollowup) -> Unit,
    onDismiss: (AegisFollowup) -> Unit,
    onPromote: (AegisFollowup) -> Unit,
) {
    val active = state.followups.filter { !it.status.equals("RESOLVED", true) && !it.status.equals("DISMISSED", true) }
    ScreenList {
        item {
            Text("Follow-ups", style = MaterialTheme.typography.headlineMedium)
            Text("Review AEGIS follow-ups and explicitly resolve, dismiss, or turn one into a Google Task.", modifier = Modifier.padding(top = 4.dp))
        }
        if (state.capabilities?.followupsV1 != true) {
            item { SummaryCard("Follow-ups unavailable", state.followupsError ?: "The backend has not advertised this contract.") }
        } else if (active.isEmpty() && !state.followupsLoading) {
            item { SummaryCard("Nothing waiting", "No active follow-ups were returned.") }
        } else {
            items(active) { followup ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(followup.title, style = MaterialTheme.typography.titleMedium)
                        if (followup.summary.isNotBlank()) Text(followup.summary, modifier = Modifier.padding(top = 6.dp))
                        Text(humanizeToken(followup.priority) ?: followup.priority, modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { onPromote(followup) },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            enabled = authenticated && !state.followupsLoading && state.capabilities.taskActionV1,
                        ) { Text("Add to Google Tasks") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onResolve(followup) }, modifier = Modifier.weight(1f), enabled = authenticated && !state.followupsLoading) { Text("Resolve") }
                            OutlinedButton(onClick = { onDismiss(followup) }, modifier = Modifier.weight(1f), enabled = authenticated && !state.followupsLoading) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        state.followupsError?.let { item { SummaryCard("Follow-up status", it) } }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = authenticated && !state.followupsLoading) { Text(if (state.followupsLoading) "Refreshing…" else "Refresh Follow-ups") } }
    }
}

@Composable
private fun NewsScreen(state: ParityUiState, onRefresh: () -> Unit) {
    val snapshot = state.intelligence.snapshot
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val allStories = snapshot?.items.orEmpty()
    val headliners = allStories.take(5)
    val headlineKeys = headliners.mapTo(mutableSetOf()) { "${it.title}|${it.link}" }
    val categories = allStories
        .filterNot { "${it.title}|${it.link}" in headlineKeys }
        .groupBy { it.category }
        .toSortedMap()

    ScreenList {
        item {
            Text("News & Insights", style = MaterialTheme.typography.headlineMedium)
            Text(
                snapshot?.updatedAtEpochMs?.let { "Updated ${formatTime(it)} • ${snapshot.items.size} stories" } ?: "News has not been loaded yet.",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item { SectionTitle("Headliners") }
        if (headliners.isEmpty()) item { SummaryCard("No headliners", "Refresh AEGIS to check the current intelligence feed.") }
        else items(headliners) { story -> StoryCard(story) { story.link?.let(uriHandler::openUri) } }

        categories.forEach { (category, stories) ->
            item {
                val isExpanded = category in expanded
                Card(
                    onClick = {
                        expanded = if (isExpanded) expanded - category else expanded + category
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(humanizeToken(category) ?: category) },
                        supportingContent = { Text(countLabel(stories.size, "story")) },
                        trailingContent = { Text(if (isExpanded) "Collapse" else "Expand") },
                    )
                }
            }
            if (category in expanded) {
                items(stories.take(24)) { story -> StoryCard(story) { story.link?.let(uriHandler::openUri) } }
            }
        }
        val failures = snapshot?.sourceHealth?.filter { it.status.equals("failed", true) }.orEmpty()
        if (failures.isNotEmpty()) {
            item { SummaryCard("Source health", "${countLabel(failures.size, "source")} unavailable; healthy feeds remain visible.") }
        }
        item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh News") } }
    }
}

@Composable
private fun StoryCard(story: IntelligenceItem, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(story.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        story.source,
                        story.publishedAtEpochMs?.let(::formatTime),
                    ).joinToString(" • "),
                )
            },
        )
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
        item { Text("Nutrition", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    Text("${formatNumber(today?.calories ?: dashboardCalories)} calories", modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.titleLarge)
                    Text("Protein ${formatNumber(today?.protein)}g • Carbs ${formatNumber(today?.carbs)}g • Fat ${formatNumber(today?.fat)}g", modifier = Modifier.padding(top = 5.dp))
                    Text("Sodium ${formatNumber(today?.sodium)} mg • Meals ${today?.mealCount ?: "—"}", modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (!parity.nutrition.contractAvailable) {
            item { SummaryCard("Detailed history unavailable", "Today's calorie total remains available from the Dashboard.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(parity.nutritionDays == 7, { onDays(7) }, label = { Text("7 days") })
                    FilterChip(parity.nutritionDays == 30, { onDays(30) }, label = { Text("30 days") })
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
                            Text("${formatNumber(day.calories)} cal • ${formatNumber(day.protein)}g protein", modifier = Modifier.padding(top = 3.dp))
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
                    listOfNotNull(
                        meal.date,
                        meal.time,
                        meal.portion,
                        meal.calories?.let { "${formatNumber(it)} cal" },
                        humanizeToken(meal.status),
                    ).joinToString(" • "),
                )
            }
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
        item { Text("Alerts & Receipts", style = MaterialTheme.typography.headlineMedium) }
        item { SummaryCard("Active attention", "${server.size} server • ${localLedger.first.count { !it.acknowledged }} local • ${localLedger.second.size} confirmations") }
        server.sortedBy { severityRank(it.severity) }.forEach { notification ->
            item { ServerAlertCard(notification, canMutate && notification.id !in notificationCommandState.submittingIds, onServerAck) }
        }
        localLedger.first.filterNot { it.acknowledged }.forEach { alert -> item { LocalAlertCard(alert, onLocalAlertAck) } }
        item { SectionTitle("Recent confirmations") }
        if (localLedger.second.isEmpty()) item { SummaryCard("No local confirmations yet", "Capture and delayed Task changes will appear here.") }
        else items(localLedger.second.take(40)) { receipt -> ReceiptCard(receipt) }
    }
}

@Composable
private fun BriefingScreen(runtime: RuntimeUiState) {
    val raw = runtime.briefing?.plainText ?: runtime.dashboard?.snapshot?.briefingPlainText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }
    ScreenList {
        item { Text("HORIZON", style = MaterialTheme.typography.headlineMedium) }
        if (document == null) item { SummaryCard("No report loaded", "Refresh AEGIS from Home. Ordinary refresh never generates a new HORIZON report.") }
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
        item { SummaryCard("Generation safeguard", "This screen reads the canonical report only. Routine Android refresh does not trigger HORIZON generation.") }
    }
}

@Composable
private fun FinanceScreen(runtime: RuntimeUiState) {
    val finance = runtime.finance
    ScreenList {
        item { Text("Recent Finances", style = MaterialTheme.typography.headlineMedium) }
        finance?.snapshot?.let { snapshot ->
            item { SummaryCard("Last ${snapshot.hours} hours", "Purchases ${snapshot.summary.purchaseTotal?.let(currencyFormatter::format) ?: "—"} • Credits ${snapshot.summary.creditTotal?.let(currencyFormatter::format) ?: "—"}") }
            items(snapshot.transactions) { tx -> SummaryCard(tx.vendor, "${tx.category} • ${tx.paymentSource} • ${currencyFormatter.format(tx.amount)}") }
        } ?: item { SummaryCard("Finance not loaded", "Refresh AEGIS to load the current read-only finance view.") }
    }
}

private data class SearchHit(val kind: String, val title: String, val detail: String, val route: String)

@Composable
private fun SearchScreen(
    runtime: RuntimeUiState,
    parity: ParityUiState,
    interaction: AegisInteractionUiState,
    navigate: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hits = remember(runtime.dashboard, runtime.briefing, runtime.finance, runtime.notifications, parity.calendar, parity.intelligence, interaction.followups) {
        buildSearchHits(runtime, parity, interaction)
    }
    val filtered = remember(query, hits) {
        val q = query.trim().lowercase()
        if (q.isBlank()) hits.take(20) else hits.filter { "${it.kind} ${it.title} ${it.detail}".lowercase().contains(q) }.take(60)
    }
    ScreenList {
        item {
            Text("Search", style = MaterialTheme.typography.headlineMedium)
            Text("Searches only synchronized local data. It never invokes Gemini or refreshes the network.", modifier = Modifier.padding(top = 4.dp))
        }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Calendar, Tasks, HORIZON, News, Finance, Follow-ups") }) }
        if (filtered.isEmpty()) item { SummaryCard("No matches", "Try another term or refresh AEGIS.") }
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
    interactionState: AegisInteractionUiState,
    selectedTheme: GposThemeOption,
    notificationPermissionGranted: Boolean,
    onThemeSelected: (GposThemeOption) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onNotificationPermission: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showRawHorizon by remember { mutableStateOf(false) }
    val rawHorizon = runtimeState.briefing?.plainText.orEmpty()
    val rawChunks = remember(rawHorizon, showRawHorizon) {
        if (showRawHorizon) rawHorizon.chunked(2000) else emptyList()
    }
    ScreenList {
        item { Text("Settings & Diagnostics", style = MaterialTheme.typography.headlineMedium) }
        item {
            when (val auth = runtimeState.auth) {
                is AuthState.Authenticated -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text(auth.user.name ?: auth.user.email, modifier = Modifier.padding(top = 4.dp))
                        Text(auth.user.email, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign out") }
                    }
                }
                is AuthState.OfflineRestored -> SummaryCard("Offline", "Your protected last-known data is available. Mutations stay disabled until a live authenticated connection returns.")
                is AuthState.Error -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sign-in needs attention", style = MaterialTheme.typography.titleMedium)
                        Text(auth.message, modifier = Modifier.padding(top = 5.dp))
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign in with Google") }
                    }
                }
                AuthState.SignedOut -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Signed out", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sign in with Google") }
                    }
                }
                AuthState.Restoring, AuthState.Authenticating -> SummaryCard("Connecting", "Restoring or validating your protected session…")
            }
        }
        item {
            SummaryCard(
                "AEGIS Android ${BuildConfig.VERSION_NAME}",
                "Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}",
            )
        }
        item {
            val caps = interactionState.capabilities
            SummaryCard(
                "Daily-use contracts",
                if (caps == null) interactionState.capabilityError ?: "Checking backend capabilities…"
                else "Add Task ${yesNo(caps.taskActionV1)} • Follow-ups ${yesNo(caps.followupsV1)} • Ask AEGIS ${yesNo(caps.aiQueryV1)} • Calendar control ${yesNo(caps.calendarAiV2)}",
            )
        }
        item { SummaryCard("Backend", runtimeState.backend.authConfig?.backendVersion ?: "Not yet discovered") }
        item {
            OutlinedButton(onClick = { showRawHorizon = !showRawHorizon }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showRawHorizon) "Hide raw HORIZON text" else "Show raw HORIZON text")
            }
        }
        if (showRawHorizon) {
            if (rawChunks.isEmpty()) item { Text("No canonical HORIZON text is cached yet.") }
            items(rawChunks.size) { index ->
                SelectionContainer {
                    Text(rawChunks[index], style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle("Data status") }
        item { SourceCard("Dashboard", runtimeState.dashboard?.source, runtimeState.dashboard?.fetchedAtEpochMs, runtimeState.dashboard?.error) }
        item { SourceCard("Calendar", parityState.calendar.source, parityState.calendar.fetchedAtEpochMs, parityState.calendar.error) }
        item { SourceCard("HORIZON", runtimeState.briefing?.source, runtimeState.briefing?.fetchedAtEpochMs, runtimeState.briefing?.error) }
        item { SourceCard("News", parityState.intelligence.source, parityState.intelligence.fetchedAtEpochMs, parityState.intelligence.error) }
        item { SourceCard("Finance", runtimeState.finance?.source, runtimeState.finance?.fetchedAtEpochMs, runtimeState.finance?.error) }
        item { SummaryCard("Background reads", "Every ${CanonicalSyncScheduler.REPEAT_MINUTES} minutes when connected and battery is not low. Routine refresh never generates HORIZON or submits Capture entries.") }
        item { SummaryCard("Task completion queue", "Five-minute Undo grace period with a WorkManager safety net every ${TaskQueueSyncScheduler.PERIODIC_MINUTES} minutes.") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Android notifications", style = MaterialTheme.typography.titleMedium)
                    Text(if (notificationPermissionGranted) "Enabled" else "Permission disabled", modifier = Modifier.padding(top = 4.dp))
                    if (!notificationPermissionGranted) Button(onClick = onNotificationPermission, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Enable notifications") }
                }
            }
        }
        item { OutlinedButton(onClick = onRefreshBackend, modifier = Modifier.fillMaxWidth()) { Text("Recheck connection") } }
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
                supportingContent = { Text(listOfNotNull(notification.message.takeIf(String::isNotBlank), notification.detail).joinToString(" • ")) },
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
            supportingContent = { Text(listOfNotNull(humanizeToken(receipt.kind), receipt.result, receipt.error, formatTime(receipt.updatedAtEpochMs)).joinToString(" • ")) },
            trailingContent = { Text(humanizeToken(receipt.state.name) ?: receipt.state.name) },
        )
    }
}

@Composable
private fun SourceCard(label: String, source: RuntimeDataSource?, fetched: Long?, error: String?) {
    SummaryCard(
        "$label • ${humanizeToken(source?.name) ?: "Not loaded"}",
        buildString {
            append(fetched?.let { "Updated ${formatTime(it)}" } ?: "No protected payload")
            if (!error.isNullOrBlank()) append(" • ").append(error)
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
        date == today.minusDays(1) -> "PREVIOUS_DAY"
        date.isBefore(today.minusDays(1)) -> "STALE"
        else -> "UNKNOWN"
    }
}

private fun connectionLabel(auth: AuthState): String = when (auth) {
    is AuthState.Authenticated -> "● Connected"
    is AuthState.OfflineRestored -> "● Offline"
    is AuthState.Error -> "Sign in"
    AuthState.Authenticating -> "Connecting…"
    AuthState.Restoring -> "Connecting…"
    AuthState.SignedOut -> "Sign in"
}

private fun primaryCaptureLabel(kind: CaptureKind): String = when (kind) {
    CaptureKind.NOTE -> "Note"
    CaptureKind.JOURNAL -> "Journal"
    CaptureKind.CALORIES -> "Calories"
    CaptureKind.RECEIPT -> "Receipt"
    else -> kind.displayName
}

private fun humanizeToken(value: String?): String? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return text
        .replace('-', ' ')
        .replace('_', ' ')
        .lowercase()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

private fun countLabel(count: Int, singular: String): String =
    "$count $singular${if (count == 1) "" else "s"}"

private fun yesNo(value: Boolean): String = if (value) "✓" else "—"
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

private fun buildSearchHits(
    runtime: RuntimeUiState,
    parity: ParityUiState,
    interaction: AegisInteractionUiState,
): List<SearchHit> = buildList {
    parity.calendar.snapshot?.events.orEmpty().forEach { event ->
        add(SearchHit("Calendar", event.title, listOfNotNull(event.localDate, event.localTime, event.location).joinToString(" • "), calendar.route))
    }
    runtime.dashboard?.snapshot?.tasks.orEmpty().forEach { task ->
        add(SearchHit("Task", task.title, task.timeLabel ?: "Active Google Task", tasks.route))
    }
    interaction.followups.forEach { followup ->
        add(SearchHit("Follow-up", followup.title, followup.summary, followups.route))
    }
    parity.intelligence.snapshot?.items.orEmpty().forEach { story ->
        add(SearchHit("News • ${story.source}", story.title, humanizeToken(story.category) ?: story.category, news.route))
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
