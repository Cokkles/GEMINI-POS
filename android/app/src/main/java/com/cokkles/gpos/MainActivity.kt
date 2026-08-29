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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

private val destinations = listOf(
    GposDestination("home", "Home", "Executive overview", Icons.Outlined.Home),
    GposDestination("briefing", "Briefing", "Latest canonical HORIZON briefing", Icons.Outlined.Description),
    GposDestination("calendar", "Calendar", "Agenda and schedule", Icons.Outlined.Event),
    GposDestination("tasks", "Tasks", "Canonical and pending actions", Icons.Outlined.CheckCircle),
    GposDestination("followups", "Follow-ups", "Pending and overdue follow-ups", Icons.Outlined.Notifications),
    GposDestination("finances", "Finances", "Canonical SENTINEL-FIN summary", Icons.Outlined.AccountBalanceWallet),
    GposDestination("aegis", "Ask AEGIS", "GPOS conversational surface", Icons.Outlined.Forum),
    GposDestination("system", "System", "Status, compatibility and settings", Icons.Outlined.Settings),
)

@Composable
private fun GposApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        destinations.forEach { destination ->
            composable(destination.route) {
                DestinationScreen(
                    destination = destination,
                    showDirectory = destination.route == "home",
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationScreen(
    destination: GposDestination,
    showDirectory: Boolean,
    onNavigate: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPOS")
                        Text(destination.title, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { innerPadding ->
        if (showDirectory) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "Android A0 Foundation",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Independent mobile client • canonical backend • read automatically, mutate explicitly",
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(destinations.filterNot { it.route == "home" }) { item ->
                    Card(onClick = { onNavigate(item.route) }) {
                        ListItem(
                            headlineContent = { Text(item.title) },
                            supportingContent = { Text(item.subtitle) },
                            leadingContent = { Icon(item.icon, contentDescription = null) },
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
    }
}
