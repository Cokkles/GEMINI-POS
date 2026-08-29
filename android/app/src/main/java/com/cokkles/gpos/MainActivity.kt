package com.cokkles.gpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cokkles.gpos.domain.CanonicalSnapshot
import com.cokkles.gpos.domain.PreviewFixtures
import com.cokkles.gpos.domain.TaskPriority
import com.cokkles.gpos.platform.connectivity.AndroidConnectivityObserver
import com.cokkles.gpos.platform.connectivity.ConnectivityState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GposApp()
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
private fun GposApp() {
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
            composable(home.route) { HomeScreen(previewSnapshot) }
            composable(briefing.route) { BriefingScreen(previewSnapshot) }
            composable(calendar.route) { CalendarScreen(previewSnapshot) }
            composable(tasks.route) { TasksScreen(previewSnapshot) }
            composable(more.route) {
                MoreScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(followups.route) { FollowUpsScreen(previewSnapshot) }
            composable(finances.route) { FinancesScreen(previewSnapshot) }
            composable(aegis.route) { AegisScreen() }
            composable(system.route) { SystemScreen(previewSnapshot) }
        }
    }
}

@Composable
private fun HomeScreen(snapshot: CanonicalSnapshot) {
    ScreenList {
        item { PreviewBanner() }
        item {
            Text("Android A0", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Independent mobile client • canonical data boundary • offline-capable foundation",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
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
                detail = "Local preview only. Production transport, authentication and background sync remain disabled.",
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
                detail = "A0 does not invoke models or generation. The UI route exists so authentication, request contracts and cost controls can be added deliberately later.",
            )
        }
    }
}

@Composable
private fun SystemScreen(snapshot: CanonicalSnapshot) {
    val context = LocalContext.current
    val connectivityObserver = remember(context) { AndroidConnectivityObserver(context) }
    val connectivity = remember { connectivityObserver.current() }

    ScreenList {
        item { PreviewBanner() }
        item { Text("System", style = MaterialTheme.typography.headlineSmall) }
        item {
            SummaryCard(
                title = "Compatibility: ${snapshot.system.compatibilityState.name}",
                detail = snapshot.system.message ?: "No system status message.",
            )
        }
        item {
            SummaryCard(
                title = "Backend reachable: ${snapshot.system.backendReachable}",
                detail = "Expected false in deterministic A0 preview mode.",
            )
        }
        item {
            SummaryCard(
                title = "Device connectivity: ${connectivityLabel(connectivity)}",
                detail = "Read from Android network capabilities only; this status check does not contact GPOS.",
            )
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
private fun PreviewBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("A0 Preview Data") },
            supportingContent = {
                Text("Deterministic local fixture • no live backend request • no canonical mutation")
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
