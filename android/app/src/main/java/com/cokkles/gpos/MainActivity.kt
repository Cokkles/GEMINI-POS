package com.cokkles.gpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

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
            composable(home.route) { HomeScreen() }
            composable(briefing.route) { PlaceholderScreen(briefing) }
            composable(calendar.route) { PlaceholderScreen(calendar) }
            composable(tasks.route) { PlaceholderScreen(tasks) }
            composable(more.route) {
                MoreScreen(
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
            secondaryDestinations.forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination) }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Android A0 Foundation",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Independent mobile client • canonical backend • read automatically, mutate explicitly",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card {
                ListItem(
                    headlineContent = { Text("Backend integration") },
                    supportingContent = { Text("Not active in A0-B. No Windows Desktop or Helper dependency.") },
                )
            }
        }
        item {
            Card {
                ListItem(
                    headlineContent = { Text("Next foundation work") },
                    supportingContent = { Text("Authentication, versioned API boundary, cache freshness, background sync and notifications.") },
                )
            }
        }
    }
}

@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("More GPOS", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Additional first-class mobile surfaces",
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(secondaryDestinations) { destination ->
            Card(onClick = { onNavigate(destination.route) }) {
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
private fun PlaceholderScreen(destination: GposDestination) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Icon(destination.icon, contentDescription = null)
        Text(
            destination.title,
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            destination.subtitle,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "A0 placeholder — backend integration is intentionally not active yet.",
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
