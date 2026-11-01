package com.pruthviraj.garagelink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class NewProfile(
    val id: String,
    val phone_number: String,
    val role: String
)

// Flip to true when driver-side ships - re-enables the "I'm a Driver" card below.
private const val DRIVER_SIDE_LIVE = false

@Composable
fun RoleSelectionScreen(userId: String, phoneNumber: String, onRoleSelected: () -> Unit) {
    var isSaving by remember { mutableStateOf(false) }
    var savingRole by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDriverComingSoon by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val postgrest = SupabaseClientProvider.client.postgrest

    fun selectRole(role: String) {
        isSaving = true
        savingRole = role
        errorMessage = null
        scope.launch {
            try {
                postgrest.from("profiles").insert(
                    NewProfile(id = userId, phone_number = phoneNumber, role = role)
                )
                onRoleSelected()
            } catch (e: Exception) {
                errorMessage = "Couldn't save your choice. Please try again."
            } finally {
                isSaving = false
                savingRole = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Welcome! Who are you?",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose how you'll use GarageLink",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        RoleCard(
            title = "I'm a Garage Owner",
            subtitle = "Manage inventory and reach nearby drivers",
            icon = Icons.Filled.Build,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
            isSaving = isSaving && savingRole == "garage_owner",
            enabled = !isSaving,
            comingSoon = false,
            onClick = { selectRole("garage_owner") }
        )
        Spacer(Modifier.height(16.dp))
        RoleCard(
            title = "I'm a Driver",
            subtitle = if (DRIVER_SIDE_LIVE) "Find parts in stock at garages near you" else "Launching soon - garage owners can join now",
            icon = Icons.Filled.DirectionsCar,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            onContainerColor = MaterialTheme.colorScheme.onSecondaryContainer,
            isSaving = isSaving && savingRole == "driver",
            enabled = !isSaving && DRIVER_SIDE_LIVE,
            comingSoon = !DRIVER_SIDE_LIVE,
            onClick = {
                if (DRIVER_SIDE_LIVE) selectRole("driver") else showDriverComingSoon = true
            }
        )

        errorMessage?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 2
            )
        }
    }

    if (showDriverComingSoon) {
        AlertDialog(
            onDismissRequest = { showDriverComingSoon = false },
            title = { Text("Driver side is on its way") },
            text = { Text("We're launching with garage owners first. The driver experience is coming in an update soon.") },
            confirmButton = { TextButton(onClick = { showDriverComingSoon = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    onContainerColor: androidx.compose.ui.graphics.Color,
    isSaving: Boolean,
    enabled: Boolean,
    comingSoon: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (comingSoon) containerColor.copy(alpha = 0.5f) else containerColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).background(onContainerColor.copy(alpha = if (comingSoon) 0.08f else 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = onContainerColor)
                } else {
                    Icon(icon, contentDescription = null, tint = onContainerColor.copy(alpha = if (comingSoon) 0.6f else 1f), modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = onContainerColor.copy(alpha = if (comingSoon) 0.7f else 1f)
                    )
                    if (comingSoon) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = onContainerColor.copy(alpha = 0.15f)) {
                            Text(
                                "COMING SOON",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = onContainerColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainerColor.copy(alpha = if (comingSoon) 0.6f else 0.8f)
                )
            }
        }
    }
}