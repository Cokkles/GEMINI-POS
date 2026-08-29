package com.cokkles.gpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.DashboardEvent
import com.cokkles.gpos.data.remote.DashboardRuntimeState
import com.cokkles.gpos.data.remote.DashboardTask
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.platform.connectivity.AndroidConnectivityObserver
import com.cokkles.gpos.platform.connectivity.ConnectivityState
import com.cokkles.gpos.platform.security.GoogleSignInCoordinator
import com.cokkles.gpos.ui.briefing.HorizonDocumentParser
import com.cokkles.gpos.ui.home.quoteFor
import com.cokkles.gpos.ui.theme.GposTheme
import com.cokkles.gpos.ui.theme.GposThemeOption
import com.cokkles.gpos.ui.theme.ThemePreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class GposActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val googleSignInCoordinator = GoogleSignInCoordinator(this)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            var selectedTheme by remember { mutableStateOf(themePreferences.load()) }
            val runtimeState by runtimeViewModel.uiState.collectAsStateWithLifecycle()

            GposTheme(selectedTheme) {
                GposApp02(
                    selectedTheme = selectedTheme,
                    onThemeSelected = { theme ->
                        selectedTheme = theme
                        themePreferences.save(theme)
                    },
                    runtimeState = runtimeState,
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
                        }
                    },
                    onRefreshBackend = runtimeViewModel::refreshBackendAndRestoreSession,
                    onRefreshCanonical = runtimeViewModel::refreshCanonicalReads,
                )
            }
        }
    }
}

private data class Destination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val home02 = Destination("home", "Home", "Canonical mobile overview", Icons.Outlined.Home)
private val briefing02 = Destination("briefing", "Briefing", "Latest canonical HORIZON", Icons.Outlined.Description)
private val calendar02 = Destination("calendar", "Calendar", "Read-only canonical schedule", Icons.Outlined.Event)
private val tasks02 = Destination("tasks", "Tasks", "Read-only canonical tasks", Icons.Outlined.CheckCircle)
private val more02 = Destination("more", "More", "Additional GPOS areas", Icons.Outlined.MoreHoriz)
private val followups02 = Destination("followups", "Follow-ups", "Contract integration pending", Icons.Outlined.Notifications)
private val finances02 = Destination("finances", "Finances", "SENTINEL-FIN integration pending", Icons.Outlined.AccountBalanceWallet)
private val aegis02 = Destination("aegis", "Ask AEGIS", "Conversation surface", Icons.Outlined.Forum)
private val system02 = Destination("system", "System", "Status, authentication and appearance", Icons.Outlined.Settings)

private val primary02 = listOf(home02, briefing02, calendar02, tasks02, more02)
private val secondary02 = listOf(followups02, finances02, aegis02, system02)
private val all02 = primary02 + secondary02

private val dateTimeFormatter02 = DateTimeFormatter.ofPattern("MMM d • h:mm a")
    .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GposApp02(
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    runtimeState: RuntimeUiState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: home02.route
    val currentDestination = all02.firstOrNull { it.route == currentRoute } ?: home02
    val selectedPrimaryRoute = if (secondary02.any { it.route == currentRoute }) more02.route else currentRoute

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
                primary02.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedPrimaryRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
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
            startDestination = home02.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(home02.route) {
                HomeScreen02(runtimeState, onRefreshCanonical)
            }
            composable(briefing02.route) {
                BriefingScreen02(runtimeState, onRefreshCanonical)
            }
            composable(calendar02.route) {
                CalendarScreen02(runtimeState.dashboard, onRefreshCanonical)
            }
            composable(tasks02.route) {
                TasksScreen02(runtimeState.dashboard, onRefreshCanonical)
            }
            composable(more02.route) {
                MoreScreen02(onNavigate = navController::navigate)
            }
            composable(followups02.route) {
                PendingContractScreen02(
                    title = "Follow-ups",
                    detail = "0.2 does not invent a follow-up feed from HORIZON prose. This surface will switch to canonical data when a bounded backend contract is proven.",
                )
            }
            composable(finances02.route) {
                PendingContractScreen02(
                    title = "Finances",
                    detail = "SENTINEL-FIN remains canonical authority. Android will not infer financial state from briefing text; the dedicated finance read contract is the next integration gate.",
                )
            }
            composable(aegis02.route) { AegisScreen02() }
            composable(system02.route) {
                SystemScreen02(
                    runtimeState = runtimeState,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
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
private fun HomeScreen02(
    runtimeState: RuntimeUiState,
    onRefreshCanonical: () -> Unit,
) {
    val quote = remember { quoteFor(LocalDate.now()) }
    val dashboard = runtimeState.dashboard
    val nextEvent = dashboard?.snapshot?.todayEvents?.firstOrNull()
        ?: dashboard?.snapshot?.tomorrowEvents?.firstOrNull()
    val briefingText = runtimeState.briefing?.plainText
        ?: dashboard?.snapshot?.briefingPlainText

    ScreenList02 {
        item { DailyInspirationCard02(quote.text, quote.attribution) }
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "GPOS Android ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            DataSourceCard02(
                label = "Dashboard",
                source = dashboard?.source,
                fetchedAtEpochMs = dashboard?.fetchedAtEpochMs,
                error = dashboard?.error,
            )
        }

        if (dashboard == null) {
            item {
                CanonicalEmptyCard02(
                    title = "Canonical dashboard not loaded",
                    detail = authHint02(runtimeState.auth),
                )
            }
        } else {
            item {
                SummaryCard02(
                    title = nextEvent?.let { "Next: ${it.title}" } ?: "No scheduled events returned",
                    detail = nextEvent?.let { event ->
                        "${if (event.day.name == "TODAY") "Today" else "Tomorrow"} • ${event.timeLabel}${event.note?.let { " • $it" }.orEmpty()}"
                    } ?: "The bounded calendar feed currently contains no today/tomorrow events.",
                )
            }
            item {
                val tasks = dashboard.snapshot.tasks
                SummaryCard02(
                    title = "${tasks.size} active task${if (tasks.size == 1) "" else "s"}",
                    detail = tasks.take(3).joinToString(" • ") { it.title }
                        .ifBlank { "No active Google Tasks returned." },
                )
            }
            item {
                val status = dashboard.snapshot.horizonLastSuccessAtEpochMs
                    ?.let { "Last successful HORIZON ${formatTime02(it)}" }
                    ?: "No HORIZON generation timestamp returned"
                SummaryCard02(
                    title = "HORIZON ${dashboard.snapshot.horizonMode ?: "status"}",
                    detail = status,
                )
            }
        }

        item {
            SummaryCard02(
                title = if (briefingText.isNullOrBlank()) "Briefing unavailable" else "Briefing available",
                detail = briefingText
                    ?.lineSequence()
                    ?.map(String::trim)
                    ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: "Sign in and refresh to retrieve the canonical HORIZON briefing.",
            )
        }
        item {
            Button(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh canonical data")
            }
        }
    }
}

@Composable
private fun BriefingScreen02(
    runtimeState: RuntimeUiState,
    onRefreshCanonical: () -> Unit,
) {
    val briefing = runtimeState.briefing
    val fallbackText = runtimeState.dashboard?.snapshot?.briefingPlainText
    val raw = briefing?.plainText ?: fallbackText
    val document = remember(raw) { raw?.let(HorizonDocumentParser::parse) }

    ScreenList02 {
        item {
            DataSourceCard02(
                label = "HORIZON",
                source = briefing?.source ?: runtimeState.dashboard?.source,
                fetchedAtEpochMs = briefing?.fetchedAtEpochMs ?: runtimeState.dashboard?.fetchedAtEpochMs,
                error = briefing?.error,
            )
        }
        if (raw.isNullOrBlank() || document == null) {
            item {
                CanonicalEmptyCard02(
                    title = "No canonical briefing loaded",
                    detail = authHint02(runtimeState.auth),
                )
            }
        } else {
            item {
                Text(
                    document.title ?: "Daily Executive Briefing",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (document.preamble.isNotEmpty()) {
                item {
                    BriefingTextCard02(
                        title = "Overview",
                        lines = document.preamble,
                    )
                }
            }
            items(document.sections) { section ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        section.lines
                            .map(HorizonDocumentParser::displayLine)
                            .filter(String::isNotBlank)
                            .forEach { line ->
                                Text(
                                    "• $line",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        section.subsections.forEach { subsection ->
                            Text(
                                subsection.title,
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            subsection.lines
                                .map(HorizonDocumentParser::displayLine)
                                .filter(String::isNotBlank)
                                .forEach { line ->
                                    Text(
                                        "• $line",
                                        modifier = Modifier.padding(top = 6.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh briefing")
            }
        }
    }
}

@Composable
private fun CalendarScreen02(
    dashboard: DashboardRuntimeState?,
    onRefreshCanonical: () -> Unit,
) {
    ScreenList02 {
        item {
            DataSourceCard02(
                label = "Calendar",
                source = dashboard?.source,
                fetchedAtEpochMs = dashboard?.fetchedAtEpochMs,
                error = dashboard?.error,
            )
        }
        if (dashboard == null) {
            item {
                CanonicalEmptyCard02(
                    title = "Calendar not loaded",
                    detail = "Authenticate in System, then refresh canonical data.",
                )
            }
        } else {
            item { Text("Today", style = MaterialTheme.typography.headlineSmall) }
            if (dashboard.snapshot.todayEvents.isEmpty()) {
                item { SummaryCard02("No events today", "No today events were returned by the canonical dashboard feed.") }
            } else {
                items(dashboard.snapshot.todayEvents) { EventCard02(it) }
            }
            item { Text("Tomorrow", style = MaterialTheme.typography.headlineSmall) }
            if (dashboard.snapshot.tomorrowEvents.isEmpty()) {
                item { SummaryCard02("No events tomorrow", "No tomorrow events were returned by the canonical dashboard feed.") }
            } else {
                items(dashboard.snapshot.tomorrowEvents) { EventCard02(it) }
            }
        }
        item {
            OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh calendar")
            }
        }
    }
}

@Composable
private fun TasksScreen02(
    dashboard: DashboardRuntimeState?,
    onRefreshCanonical: () -> Unit,
) {
    ScreenList02 {
        item {
            DataSourceCard02(
                label = "Tasks",
                source = dashboard?.source,
                fetchedAtEpochMs = dashboard?.fetchedAtEpochMs,
                error = dashboard?.error,
            )
        }
        item {
            SummaryCard02(
                title = "Read-only in 0.2",
                detail = "Task completion and edits remain disabled until mutation authorization, confirmation and retry semantics are validated.",
            )
        }
        if (dashboard == null) {
            item {
                CanonicalEmptyCard02(
                    title = "Tasks not loaded",
                    detail = "Authenticate in System, then refresh canonical data.",
                )
            }
        } else if (dashboard.snapshot.tasks.isEmpty()) {
            item { SummaryCard02("No active tasks", "The canonical dashboard returned no active Google Tasks.") }
        } else {
            items(dashboard.snapshot.tasks) { TaskCard02(it) }
        }
        item {
            OutlinedButton(onClick = onRefreshCanonical, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh tasks")
            }
        }
    }
}

@Composable
private fun MoreScreen02(onNavigate: (String) -> Unit) {
    ScreenList02 {
        item {
            Text("More GPOS", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Additional first-class mobile surfaces",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(secondary02) { destination ->
            Card(
                onClick = { onNavigate(destination.route) },
                modifier = Modifier.fillMaxWidth(),
            ) {
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
private fun PendingContractScreen02(title: String, detail: String) {
    ScreenList02 {
        item { Text(title, style = MaterialTheme.typography.headlineSmall) }
        item { SummaryCard02("Canonical integration pending", detail) }
    }
}

@Composable
private fun AegisScreen02() {
    ScreenList02 {
        item { Text("Ask AEGIS", style = MaterialTheme.typography.headlineSmall) }
        item {
            SummaryCard02(
                "Conversation surface reserved",
                "0.2 remains read-oriented. Model invocation and conversational mutation controls stay disabled until the shared request/cost/confirmation contract is reviewed.",
            )
        }
    }
}

@Composable
private fun SystemScreen02(
    runtimeState: RuntimeUiState,
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshCanonical: () -> Unit,
) {
    val context = LocalContext.current
    val connectivityObserver = remember(context) { AndroidConnectivityObserver(context) }
    val connectivity = remember { connectivityObserver.current() }

    ScreenList02 {
        item { Text("System", style = MaterialTheme.typography.headlineSmall) }
        item {
            SummaryCard02(
                title = "GPOS Android ${BuildConfig.VERSION_NAME}",
                detail = "Native direct client • read automatically • mutate explicitly",
            )
        }
        item { Text("Appearance", style = MaterialTheme.typography.titleMedium) }
        items(GposThemeOption.entries) { option ->
            Card(
                onClick = { onThemeSelected(option) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    supportingContent = { Text(option.description) },
                    leadingContent = {
                        RadioButton(
                            selected = option == selectedTheme,
                            onClick = { onThemeSelected(option) },
                        )
                    },
                )
            }
        }
        item { Text("Backend & authentication", style = MaterialTheme.typography.titleMedium) }
        item {
            val config = runtimeState.backend.authConfig
            SummaryCard02(
                title = when {
                    runtimeState.backend.checking -> "Backend: checking"
                    runtimeState.backend.reachable -> "Backend: reachable"
                    else -> "Backend: unavailable"
                },
                detail = config?.let {
                    "${it.authVersion} • backend ${it.backendVersion} • enforcement ${if (it.enforcementRequired) "ON" else "OFF"}"
                } ?: runtimeState.backend.error ?: "AUTH-1 discovery has not completed.",
            )
        }
        item { AuthenticationCard02(runtimeState.auth, onSignInRequested, onSignOutRequested) }
        item {
            SummaryCard02(
                title = "Device connectivity: ${connectivityLabel02(connectivity)}",
                detail = "OS network capability only; this indicator does not itself call GPOS.",
            )
        }
        item {
            DataSourceCard02(
                label = "Dashboard cache",
                source = runtimeState.dashboard?.source,
                fetchedAtEpochMs = runtimeState.dashboard?.fetchedAtEpochMs,
                error = runtimeState.dashboard?.error,
            )
        }
        item {
            DataSourceCard02(
                label = "HORIZON cache",
                source = runtimeState.briefing?.source,
                fetchedAtEpochMs = runtimeState.briefing?.fetchedAtEpochMs,
                error = runtimeState.briefing?.error,
            )
        }
        item {
            SummaryCard02(
                title = runtimeState.backend.lastProtectedRead ?: "Protected reads not checked",
                detail = runtimeState.backend.error ?: "Health and capability checks are read-only.",
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onRefreshBackend, modifier = Modifier.weight(1f)) {
                    Text("Backend")
                }
                Button(onClick = onRefreshCanonical, modifier = Modifier.weight(1f)) {
                    Text("Canonical")
                }
            }
        }
    }
}

@Composable
private fun AuthenticationCard02(
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
                    Text(
                        "Google AUTH-1 is required for live canonical reads.",
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    Button(onClick = onSignInRequested) { Text("Sign in with Google") }
                }
                AuthState.Authenticating -> {
                    Text("Signing in…", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for AUTH-1 validation.", modifier = Modifier.padding(top = 6.dp))
                }
                is AuthState.Authenticated -> {
                    Text("Signed in", style = MaterialTheme.typography.titleMedium)
                    Text(
                        authState.user.name ?: authState.user.email,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(authState.user.email, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = onSignOutRequested,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("Sign out")
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
private fun DataSourceCard02(
    label: String,
    source: RuntimeDataSource?,
    fetchedAtEpochMs: Long?,
    error: String?,
) {
    val sourceLabel = source?.name ?: "NOT LOADED"
    val detail = buildString {
        if (fetchedAtEpochMs != null) {
            append("Fetched ${formatTime02(fetchedAtEpochMs)}")
        } else {
            append("No last-known-good payload on this device")
        }
        if (!error.isNullOrBlank()) append(" • $error")
    }
    SummaryCard02("$label • $sourceLabel", detail)
}

@Composable
private fun DailyInspirationCard02(text: String, attribution: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Quote of the day", style = MaterialTheme.typography.labelLarge)
            Text(
                "“$text”",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!attribution.isNullOrBlank()) {
                Text(
                    "— $attribution",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EventCard02(event: DashboardEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title) },
            supportingContent = {
                Text(
                    buildString {
                        append(event.timeLabel)
                        event.note?.let { append(" • $it") }
                    },
                )
            },
            leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
        )
    }
}

@Composable
private fun TaskCard02(task: DashboardTask) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(task.title) },
            supportingContent = { Text(task.timeLabel ?: "Google Task") },
            leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
        )
    }
}

@Composable
private fun BriefingTextCard02(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.map(HorizonDocumentParser::displayLine)
                .filter(String::isNotBlank)
                .forEach { line ->
                    Text(line, modifier = Modifier.padding(top = 8.dp))
                }
        }
    }
}

@Composable
private fun CanonicalEmptyCard02(title: String, detail: String) {
    SummaryCard02(title, detail)
}

@Composable
private fun SummaryCard02(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(detail) },
        )
    }
}

@Composable
private fun ScreenList02(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

private fun authHint02(authState: AuthState): String = when (authState) {
    AuthState.Restoring -> "Restoring your protected session."
    AuthState.SignedOut -> "Open System and sign in with Google to load live canonical data."
    AuthState.Authenticating -> "Authentication is currently in progress."
    is AuthState.Authenticated -> "Signed in as ${authState.user.email}. Use Refresh canonical data."
    is AuthState.Error -> authState.message
}

private fun formatTime02(epochMs: Long): String = dateTimeFormatter02.format(Instant.ofEpochMilli(epochMs))

private fun connectivityLabel02(state: ConnectivityState): String = when (state) {
    ConnectivityState.Unknown -> "UNKNOWN"
    ConnectivityState.Offline -> "OFFLINE"
    is ConnectivityState.Online -> if (state.validated) "ONLINE / VALIDATED" else "ONLINE / UNVALIDATED"
}
