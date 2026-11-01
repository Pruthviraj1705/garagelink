package com.pruthviraj.garagelink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class NotificationRow(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val is_read: Boolean,
    val created_at: String
)

@Serializable
data class MarkRead(val is_read: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, onNotificationsChanged: () -> Unit) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<NotificationRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        notifications = if (userId != null) {
            try {
                postgrest.from("notifications")
                    .select { filter { eq("user_id", userId) }; order("created_at", Order.DESCENDING) }
                    .decodeList<NotificationRow>()
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        isLoading = false
    }

    val unreadCount = notifications.count { !it.is_read }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Notifications", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            if (unreadCount > 0) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(
                        "$unreadCount new",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (notifications.isNotEmpty()) {
            Text(
                "Swipe left to delete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))

        when {
            isLoading -> Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            notifications.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.NotificationsNone,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("No notifications yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "We'll let you know about low stock and expiring items here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            else -> LazyColumn {
                items(notifications, key = { it.id }) { n ->
                    val typeLower = n.type.lowercase()
                    val icon = when {
                        typeLower.contains("low_stock") || typeLower.contains("stock") -> Icons.Filled.Inventory2
                        typeLower.contains("expir") -> Icons.Filled.Schedule
                        else -> Icons.Filled.Notifications
                    }
                    val timestamp = parseIsoToMillis(n.created_at)?.let { formatMillisAsDate(it) }

                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch {
                                    try {
                                        postgrest.from("notifications").delete { filter { eq("id", n.id) } }
                                        notifications = notifications.filter { it.id != n.id }
                                        onNotificationsChanged()
                                    } catch (_: Exception) {}
                                }
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        modifier = Modifier.padding(vertical = 5.dp),
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(end = 20.dp)
                                )
                            }
                        }
                    ) {
                        Card(
                            onClick = {
                                if (!n.is_read) {
                                    scope.launch {
                                        try {
                                            postgrest.from("notifications").update(MarkRead(true)) { filter { eq("id", n.id) } }
                                            notifications = notifications.map { if (it.id == n.id) it.copy(is_read = true) else it }
                                            onNotificationsChanged()
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (n.is_read) 0.dp else 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (n.is_read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (n.is_read) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = if (n.is_read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        n.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = if (n.is_read) FontWeight.Normal else FontWeight.Bold
                                        ),
                                        color = if (n.is_read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        n.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (n.is_read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                    if (timestamp != null) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            timestamp,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}