package com.cokkles.gpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.domain.CanonicalSnapshot
import com.cokkles.gpos.domain.PreviewFixtures
import com.cokkles.gpos.domain.TaskPriority
import com.cokkles.gpos.platform.connectivity.AndroidConnectivityObserver
import com.cokkles.gpos.platform.connectivity.ConnectivityState
import com.cokkles.gpos.platform.security.GoogleSignInCoordinator
import com.cokkles.gpos.ui.home.quoteFor
import com.cokkles.gpos.ui.theme.GposTheme
import com.cokkles.gpos.ui.theme.GposThemeOption
import com.cokkles.gpos.ui.theme.ThemePreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtimeViewModel: GposRuntimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val googleSignInCoordinator = GoogleSignInCoordinator(this)

        setContent {
            val themePreferences = remember { ThemePreferences(applicationContext) }
            var selectedTheme by remember { mutableStateOf(themePreferences.load()) }
            val runtimeState by runtimeViewModel.uiState.collectAsStateWithLifecycle()

            GposTheme(selectedTheme) {
                GposApp(
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
                    onRefreshProtectedReads = runtimeViewModel::refreshProtectedReads,
                )
            }
        }
    }
}

data class GposDestination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val home = GposDestination("home", "Home", "Executive overview", Icons.Outlined.Home)
private val briefing = GposDestination("briefing", "Briefing", "Latest canonical HORIZON briefing", Icons.Outlined.Description)
private val calendar = GposDestination("calendar", "Calendar", "Agenda and schedule", Icons.Outlined.Event)
private val tasks = GposDestination("tasks", "Tasks", "Canonical and pending actions", Icons.Outlined.CheckCircle)
private val followups = GposDestination("followups", "Follow-ups", "Pending and overdue follow-ups", Icons.Outlined.Notifications)
private val finances = GposDestination("finances", "Finances", "Canonical SENTINEL-FIN summary", Icons.Outlined.AccountBalanceWallet)
private val aegis = GposDestination("aegis", "Ask AEGIS", "GPOS conversational surface", Icons.Outlined.Forum)
private val system = GposDestination("system", "System", "Status, compatibility and settings", Icons.Outlined.Settings)
private val more = GposDestination("more", "More", "Additional GPOS areas", Icons.Outlined.MoreHoriz)

private val primaryDestinations = listOf(home, briefing, calendar, tasks, more)
private val secondaryDestinations = listOf(followups, finances, aegis, system)
private val allDestinations = primaryDestinations + secondaryDestinations
private val previewSnapshot = PreviewFixtures.snapshot
private val timeFormatter = DateTimeFormatter.ofPattern("MMM d • h:mm a")
    .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GposApp(
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    runtimeState: RuntimeUiState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshProtectedReads: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: home.route
    val currentDestination = allDestinations.firstOrNull { it.route == currentRoute } ?: home
    val selectedPrimaryRoute = if (secondaryDestinations.any { it.route == currentRoute }) more.route else currentRoute

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPOS")
                        Text(currentDestination.title, style = MaterialTheme.typography.labelMedium)
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
            startDestination = home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(home.route) { HomeScreen(previewSnapshot, runtimeState) }
            composable(briefing.route) { BriefingScreen(previewSnapshot) }
            composable(calendar.route) { CalendarScreen(previewSnapshot) }
            composable(tasks.route) { TasksScreen(previewSnapshot) }
            composable(more.route) {
                MoreScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(followups.route) { FollowUpsScreen(previewSnapshot) }
            composable(finances.route) { FinancesScreen(previewSnapshot) }
            composable(aegis.route) { AegisScreen() }
            composable(system.route) {
                SystemScreen(
                    snapshot = previewSnapshot,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
                    runtimeState = runtimeState,
                    onSignInRequested = onSignInRequested,
                    onSignOutRequested = onSignOutRequested,
                    onRefreshBackend = onRefreshBackend,
                    onRefreshProtectedReads = onRefreshProtectedReads,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(snapshot: CanonicalSnapshot, runtimeState: RuntimeUiState) {
    val dailyQuote = remember { quoteFor(LocalDate.now()) }

    ScreenList {
        item { DailyInspirationCard(dailyQuote.text, dailyQuote.attribution) }
        item { PreviewBanner() }
        item {
            Text("Android 0.1", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Independent mobile client • AUTH-1 connectivity foundation • offline-capable shell",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            SummaryCard(
                title = if (runtimeState.backend.reachable) "GPOS backend discovered" else "GPOS backend not yet reachable",
                detail = runtimeState.backend.authConfig?.let { config ->
                    "${config.authVersion} • backend ${config.backendVersion} • enforcement ${if (config.enforcementRequired) "ON" else "OFF"}"
                } ?: runtimeState.backend.error ?: "Checking AUTH-1 configuration.",
            )
        }
        item {
            SummaryCard(
                title = snapshot.briefing?.title ?: "Briefing unavailable",
                detail = snapshot.briefing?.summary ?: "No briefing fixture loaded.",
            )
        }
        item {
            SummaryCard(
                title = "Today",
                detail = "${snapshot.calendar.size} calendar items • ${snapshot.tasks.count { it.status.name != "COMPLETED" }} open tasks • ${snapshot.followUps.size} follow-up",
            )
        }
        item {
            SummaryCard(
                title = "Sync posture",
                detail = "AUTH-1 discovery and authenticated health/dashboard checks may be live. Screen content remains deterministic fixture data until canonical response mapping is validated. Remote mutation remains disabled.",
            )
        }
    }
}

@Composable
private fun BriefingScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item {
            val value = snapshot.briefing
            Text(value?.title ?: "No briefing", style = MaterialTheme.typography.headlineSmall)
            Text(
                value?.summary ?: "No canonical briefing fixture is available.",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Generated ${formatTime(snapshot.generatedAt)} • ${value?.canonicalVersion ?: "version unknown"}",
                modifier = Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CalendarScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item { Text("Agenda", style = MaterialTheme.typography.headlineSmall) }
        items(snapshot.calendar) { event ->
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(event.title) },
                    supportingContent = {
                        val endsAt = event.endsAt
                        Text(
                            if (endsAt == null) {
                                formatTime(event.startsAt)
                            } else {
                                "${formatTime(event.startsAt)} → ${formatTime(endsAt)}"
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun TasksScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item { Text("Tasks", style = MaterialTheme.typography.headlineSmall) }
        items(snapshot.tasks) { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(task.title) },
                    supportingContent = {
                        val priority = if (task.priority == TaskPriority.NORMAL) "" else " • ${task.priority.name}"
                        Text("${task.status.name.replace('_', ' ')}$priority")
                    },
                    leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun FollowUpsScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item { Text("Follow-ups", style = MaterialTheme.typography.headlineSmall) }
        items(snapshot.followUps) { followUp ->
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(followUp.title) },
                    supportingContent = {
                        val dueAt = followUp.dueAt
                        Text(
                            when {
                                followUp.overdue -> "Overdue"
                                dueAt != null -> "Due ${formatTime(dueAt)}"
                                else -> "No due time"
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun FinancesScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item { Text("Finances", style = MaterialTheme.typography.headlineSmall) }
        item {
            val value = snapshot.finances
            SummaryCard(
                title = value?.headline ?: "No finance summary",
                detail = value?.detail ?: "Canonical finance data is not loaded.",
            )
        }
    }
}

@Composable
private fun AegisScreen() {
    ScreenList {
        item { PreviewBanner() }
        item { Text("Ask AEGIS", style = MaterialTheme.typography.headlineSmall) }
        item {
            SummaryCard(
                title = "Conversation surface reserved",
                detail = "Android 0.1 does not invoke model generation. Authentication and read-only backend plumbing are being established before conversational requests are enabled.",
            )
        }
    }
}

@Composable
private fun SystemScreen(
    snapshot: CanonicalSnapshot,
    selectedTheme: GposThemeOption,
    onThemeSelected: (GposThemeOption) -> Unit,
    runtimeState: RuntimeUiState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshBackend: () -> Unit,
    onRefreshProtectedReads: () -> Unit,
) {
    val context = LocalContext.current
    val connectivityObserver = remember(context) { AndroidConnectivityObserver(context) }
    val connectivity = remember { connectivityObserver.current() }

    ScreenList {
        item { PreviewBanner() }
        item { Text("System", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Theme changes apply immediately and are saved on this device.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
        item {
            Text("Backend & authentication", style = MaterialTheme.typography.titleMedium)
        }
        item {
            val config = runtimeState.backend.authConfig
            SummaryCard(
                title = when {
                    runtimeState.backend.checking -> "Backend: checking"
                    runtimeState.backend.reachable -> "Backend: reachable"
                    else -> "Backend: unavailable"
                },
                detail = config?.let {
                    "${it.authVersion} • backend ${it.backendVersion} • allowlist ${if (it.allowlistConfigured) "configured" else "missing"} • enforcement ${if (it.enforcementRequired) "ON" else "OFF"}"
                } ?: runtimeState.backend.error ?: "Public AUTH-1 discovery has not completed.",
            )
        }
        item {
            AuthenticationCard(
                authState = runtimeState.auth,
                onSignInRequested = onSignInRequested,
                onSignOutRequested = onSignOutRequested,
                onRefreshProtectedReads = onRefreshProtectedReads,
            )
        }
        runtimeState.backend.lastProtectedRead?.let { status ->
            item {
                SummaryCard(
                    title = "Protected read check",
                    detail = status,
                )
            }
        }
        runtimeState.backend.error?.let { error ->
            item {
                SummaryCard(
                    title = "Backend notice",
                    detail = error,
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onRefreshBackend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Recheck backend / session")
            }
        }
        item {
            SummaryCard(
                title = "Fixture compatibility: ${snapshot.system.compatibilityState.name}",
                detail = snapshot.system.message ?: "No fixture system status message.",
            )
        }
        item {
            SummaryCard(
                title = "Device connectivity: ${connectivityLabel(connectivity)}",
                detail = "Read from Android network capabilities. AUTH-1 status above is the separate real backend reachability check.",
            )
        }
    }
}

@Composable
private fun AuthenticationCard(
    authState: AuthState,
    onSignInRequested: () -> Unit,
    onSignOutRequested: () -> Unit,
    onRefreshProtectedReads: () -> Unit,
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
                        "Sign in with an authorized Google account to validate protected GPOS reads.",
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    Button(onClick = onSignInRequested, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign in with Google")
                    }
                }

                AuthState.Authenticating -> {
                    Text("Authenticating…", style = MaterialTheme.typography.titleMedium)
                    Text("Validating Google identity with AUTH-1.", modifier = Modifier.padding(top = 6.dp))
                }

                is AuthState.Authenticated -> {
                    Text("Authenticated", style = MaterialTheme.typography.titleMedium)
                    Text(
                        authState.user.email,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    Button(
                        onClick = onRefreshProtectedReads,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Refresh secure reads")
                    }
                    OutlinedButton(
                        onClick = onSignOutRequested,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text("Sign out")
                    }
                }

                is AuthState.Error -> {
                    Text("Authentication needs attention", style = MaterialTheme.typography.titleMedium)
                    Text(
                        authState.message,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    Button(onClick = onSignInRequested, modifier = Modifier.fillMaxWidth()) {
                        Text("Try Google sign-in")
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
            Text(
                "Additional first-class mobile surfaces",
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(secondaryDestinations) { destination ->
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
private fun DailyInspirationCard(text: String, attribution: String?) {
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
private fun PreviewBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("0.1 Preview Data") },
            supportingContent = {
                Text("Canonical screen content is fixture-backed • auth/connectivity may be live • no canonical mutation")
            },
        )
    }
}

@Composable
private fun SummaryCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(detail) },
        )
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

private fun formatTime(value: Instant): String = timeFormatter.format(value)

private fun connectivityLabel(state: ConnectivityState): String = when (state) {
    ConnectivityState.Unknown -> "UNKNOWN"
    ConnectivityState.Offline -> "OFFLINE"
    is ConnectivityState.Online -> if (state.validated) "ONLINE / VALIDATED" else "ONLINE / UNVALIDATED"
}
