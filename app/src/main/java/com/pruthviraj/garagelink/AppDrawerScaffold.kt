package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape

data class DrawerMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerScaffold(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    onSearchSubmit: () -> Unit = {},
    onClearSearch: () -> Unit = {},
    searchSuggestions: List<String> = emptyList(),
    onSuggestionSelected: (String) -> Unit = {},
    onTopGarages: () -> Unit,
    onNearestGarages: () -> Unit,
    onAboutUs: () -> Unit,
    onContactUs: () -> Unit,
    onSettings: () -> Unit,
    onSubscriptionPlans: () -> Unit,
    onReferrals: () -> Unit,
    onEmergencyContacts: () -> Unit,
    onAiAssistant: () -> Unit,
    onLogout: () -> Unit,
    unreadNotificationCount: Int = 0,
    onNotificationsClick: () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val menuItems = listOf(
        DrawerMenuItem("Profile", Icons.Filled.Person, onSettings),
        DrawerMenuItem("Top Garages", Icons.Filled.Star, onTopGarages),
        DrawerMenuItem("Nearest Garages", Icons.Filled.Place, onNearestGarages),
        DrawerMenuItem("Subscription Plans", Icons.Filled.CardMembership, onSubscriptionPlans),
        DrawerMenuItem("Refer & Earn", Icons.Filled.Redeem, onReferrals),
        DrawerMenuItem("Emergency Contacts", Icons.Filled.Emergency, onEmergencyContacts),
        DrawerMenuItem("AI Assistant", Icons.Filled.SmartToy, onAiAssistant),
        DrawerMenuItem("About Us", Icons.Filled.Info, onAboutUs),
        DrawerMenuItem("Contact Us", Icons.Filled.Call, onContactUs)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxHeight().padding(vertical = 24.dp)) {
                    Text("GarageLink", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(24.dp))
                    menuItems.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            selected = false,
                            onClick = { scope.launch { drawerState.close() }; item.onClick() },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(8.dp))
                    NavigationDrawerItem(
                        label = { Text("Logout") },
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; onLogout() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            BadgedBox(badge = {
                                if (unreadNotificationCount > 0) {
                                    Badge { Text(if (unreadNotificationCount > 9) "9+" else unreadNotificationCount.toString()) }
                                }
                            }) {
                                IconButton(onClick = onNotificationsClick) {
                                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                                }
                            }
                        }
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(searchPlaceholder) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = onClearSearch) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (searchSuggestions.isNotEmpty()) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Column {
                                    searchSuggestions.forEach { suggestion ->
                                        Text(
                                            suggestion,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSuggestionSelected(suggestion) }
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            floatingActionButton = floatingActionButton,
            bottomBar = bottomBar
        ) { padding -> content(padding) }
    }
}