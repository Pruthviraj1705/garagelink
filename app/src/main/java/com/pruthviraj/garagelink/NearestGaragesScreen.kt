package com.pruthviraj.garagelink

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun NearestGaragesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val postgrest = SupabaseClientProvider.client.postgrest
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var results by remember { mutableStateOf<List<NearbyGarageResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchAndSearch(fusedLocationClient, postgrest, scope,
            onSuccess = { results = it; isLoading = false },
            onError = { errorMessage = it; isLoading = false })
        else { errorMessage = "Location permission is needed to find nearby garages"; isLoading = false }
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fetchAndSearch(fusedLocationClient, postgrest, scope,
                onSuccess = { results = it; isLoading = false },
                onError = { errorMessage = it; isLoading = false })
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(8.dp))
        Text("Nearest Garages", style = MaterialTheme.typography.headlineSmall)
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
                Text("No garages found nearby.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                LazyColumn {
                    items(results) { r ->
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
                                        Text(r.shop_name, style = MaterialTheme.typography.titleMedium)
                                        if (r.is_verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Verified,
                                                contentDescription = "Verified",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        "${"%.1f".format(r.distance_km)} km",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
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

@SuppressLint("MissingPermission")
private fun fetchAndSearch(
    fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
    postgrest: io.github.jan.supabase.postgrest.Postgrest,
    scope: kotlinx.coroutines.CoroutineScope,
    onSuccess: (List<NearbyGarageResult>) -> Unit,
    onError: (String) -> Unit
) {
    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
        .addOnSuccessListener { loc ->
            if (loc == null) {
                onError("Couldn't get your location. Try again.")
                return@addOnSuccessListener
            }
            scope.launch {
                try {
                    val results = postgrest.rpc("list_nearby_garages", buildJsonObject {
                        put("p_user_lat", loc.latitude)
                        put("p_user_lng", loc.longitude)
                        put("p_radius_km", 50)
                    }).decodeList<NearbyGarageResult>()
                    onSuccess(results)
                } catch (e: Exception) {
                    onError(e.message ?: "Search failed. Check your connection and try again.")
                }
            }
        }
        .addOnFailureListener {
            onError("Location lookup failed.")
        }
}