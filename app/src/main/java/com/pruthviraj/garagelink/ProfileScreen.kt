package com.pruthviraj.garagelink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class ProfileInfo(val phone_number: String, val full_name: String?, val role: String)

@Serializable
data class GarageSubscriptionInfo(
    val subscription_status: String,
    val subscription_expiry: String?,
    val plan_name: String? = null
)

@Serializable
data class UpdateGarageName(val shop_name: String)

@Composable
fun ProfileScreen(garageId: String? = null, onBack: () -> Unit, onLogout: () -> Unit) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<ProfileInfo?>(null) }
    var subInfo by remember { mutableStateOf<GarageSubscriptionInfo?>(null) }
    var garageName by remember { mutableStateOf<String?>(null) }
    var editedGarageName by remember { mutableStateOf("") }
    var isSavingName by remember { mutableStateOf(false) }
    var nameSaveError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (userId != null) {
            profile = try {
                postgrest.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<ProfileInfo>()
            } catch (e: Exception) { null }
        }
        if (garageId != null) {
            subInfo = try {
                postgrest.from("garages")
                    .select(Columns.raw("subscription_status, subscription_expiry, subscription_plans(name)")) {
                        filter { eq("id", garageId) }
                    }
                    .decodeSingleOrNull<GarageSubscriptionInfo>()
            } catch (e: Exception) { null }
            garageName = try {
                postgrest.from("garages")
                    .select(Columns.list("shop_name")) { filter { eq("id", garageId) } }
                    .decodeSingleOrNull<Map<String, String>>()?.get("shop_name")
            } catch (e: Exception) { null }
            editedGarageName = garageName ?: ""
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Back to Home") }
        }
        Spacer(Modifier.height(4.dp))
        Text("Profile", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(20.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // Account card
            Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val initial = profile?.full_name?.trim()?.takeIf { it.isNotBlank() }?.take(1)?.uppercase()
                            if (initial != null) {
                                Text(initial, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                profile?.full_name?.takeIf { it.isNotBlank() } ?: (profile?.phone_number ?: "Account"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                if (profile?.role == "garage_owner") "Garage Owner" else "Driver",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    profile?.let {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it.phone_number, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }

            // Garage name (owners only)
            if (garageId != null) {
                Spacer(Modifier.height(16.dp))
                Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Garage name", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editedGarageName,
                            onValueChange = { editedGarageName = it },
                            singleLine = true,
                            enabled = !isSavingName,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        nameSaveError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                isSavingName = true
                                nameSaveError = null
                                scope.launch {
                                    try {
                                        postgrest.from("garages")
                                            .update(UpdateGarageName(editedGarageName)) { filter { eq("id", garageId) } }
                                        garageName = editedGarageName
                                    } catch (e: Exception) {
                                        nameSaveError = "Couldn't save. Please try again."
                                    }
                                    isSavingName = false
                                }
                            },
                            enabled = !isSavingName && editedGarageName.isNotBlank() && editedGarageName != garageName,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (isSavingName) "Saving..." else "Save") }
                    }
                }
            }

            // Subscription
            subInfo?.let { sub ->
                Spacer(Modifier.height(16.dp))
                Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CardMembership, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Subscription", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            val statusLower = sub.subscription_status.lowercase()
                            val (badgeColor, onBadgeColor) = when {
                                statusLower == "active" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                                statusLower == "trial" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                            }
                            Surface(shape = RoundedCornerShape(10.dp), color = badgeColor) {
                                Text(
                                    sub.subscription_status.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = onBadgeColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Plan", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sub.plan_name ?: "No active plan", fontWeight = FontWeight.SemiBold)
                        }
                        sub.subscription_expiry?.let {
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Renews / Expires", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it.take(10), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Appearance
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dark theme")
                        Switch(
                            checked = ThemePreference.isDarkMode ?: androidx.compose.foundation.isSystemInDarkTheme(),
                            onCheckedChange = { ThemePreference.set(context, it) }
                        )
                    }
                }
            }

            // Language
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Language", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("English", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("More languages coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Logout")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "GarageLink v1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}