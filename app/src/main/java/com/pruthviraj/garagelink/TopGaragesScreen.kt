package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.postgrest

@Composable
fun TopGaragesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val postgrest = SupabaseClientProvider.client.postgrest

    var results by remember { mutableStateOf<List<TopGarageResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            results = postgrest.rpc("list_top_garages").decodeList<TopGarageResult>()
        } catch (e: Exception) {
            errorMessage = e.message ?: "Couldn't load garages. Try again."
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(8.dp))
        Text("Top Garages", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            errorMessage.isNotBlank() -> {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
            results.isEmpty() -> {
                Text("No garages listed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                LazyColumn {
                    itemsIndexed(results) { index, r ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("#${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Text(r.shop_name, style = MaterialTheme.typography.titleMedium)
                                        if (r.is_verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    r.plan_name?.let { plan ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(plan, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                if (!r.address.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(r.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!r.phone_number.isNullOrBlank()) {
                                        OutlinedButton(onClick = {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                                                data = android.net.Uri.parse("tel:${r.phone_number}")
                                            }
                                            context.startActivity(intent)
                                        }) { Text("Call") }
                                    }
                                    OutlinedButton(onClick = {
                                        val gmmIntentUri = android.net.Uri.parse("google.navigation:q=${r.latitude},${r.longitude}")
                                        val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri).apply {
                                            setPackage("com.google.android.apps.maps")
                                        }
                                        context.startActivity(mapIntent)
                                    }) { Text("Directions") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}