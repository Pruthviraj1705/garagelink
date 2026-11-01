package com.pruthviraj.garagelink

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class EmergencyCountry(
    val code: String,
    val name: String,
    val police: String,
    val ambulance: String,
    val fire: String,
    val general: String
)

@Serializable
data class EmergencyData(
    val countries: List<EmergencyCountry>,
    val default_fallback: EmergencyCountry
)

fun loadEmergencyData(context: android.content.Context): EmergencyData {
    val jsonText = context.assets.open("emergency_numbers.json").bufferedReader().use { it.readText() }
    return Json { ignoreUnknownKeys = true }.decodeFromString(jsonText)
}

fun isNetworkAvailable(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun EmergencyContactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val emergencyData = remember { loadEmergencyData(context) }
    var selectedCountry by remember { mutableStateOf<EmergencyCountry?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var detectionMessage by remember { mutableStateOf<String?>(null) }
    var showCountryPicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) { detectionMessage = "Location permission denied — pick your country manually below." } }

    @SuppressLint("MissingPermission")
    fun detectByGps() {
        if (!isNetworkAvailable(context)) {
            detectionMessage = "No internet connection — GPS country detection needs network. Pick your country manually below."
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        isDetecting = true
        detectionMessage = null
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                isDetecting = false
                if (loc == null) {
                    detectionMessage = "Couldn't get your location. Pick your country manually below."
                    return@addOnSuccessListener
                }
                try {
                    @Suppress("DEPRECATION")
                    val geocoder = android.location.Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    val countryCode = addresses?.firstOrNull()?.countryCode
                    val match = emergencyData.countries.find { it.code == countryCode }
                    if (match != null) {
                        selectedCountry = match
                    } else {
                        detectionMessage = "Detected a country not in our curated list — showing universal fallback numbers. Pick a specific country below if listed."
                        selectedCountry = emergencyData.default_fallback
                    }
                } catch (e: Exception) {
                    detectionMessage = "Couldn't detect your country automatically. Pick it manually below."
                }
            }
            .addOnFailureListener {
                isDetecting = false
                detectionMessage = "Location lookup failed. Pick your country manually below."
            }
    }

    LaunchedEffect(Unit) { detectByGps() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(8.dp))
        Text("Emergency Contacts", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Numbers are stored on your device and work offline. Detecting your country needs a connection; you can also pick one manually anytime.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (isDetecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Detecting your location...", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }

        detectionMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(onClick = { showCountryPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedCountry?.let { "Country: ${it.name}" } ?: "Select country manually")
        }
        Spacer(Modifier.height(16.dp))

        selectedCountry?.let { country ->
            LazyColumn {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(country.name, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            EmergencyNumberRow(context, "Police", country.police)
                            EmergencyNumberRow(context, "Ambulance", country.ambulance)
                            EmergencyNumberRow(context, "Fire", country.fire)
                            EmergencyNumberRow(context, "General Emergency", country.general)
                        }
                    }
                }
            }
        }
    }

    if (showCountryPicker) {
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            title = { Text("Select Country") },
            text = {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(emergencyData.countries.sortedBy { it.name }) { c ->
                        TextButton(
                            onClick = {
                                selectedCountry = c
                                detectionMessage = null
                                showCountryPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(c.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCountryPicker = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmergencyNumberRow(context: android.content.Context, label: String, number: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(number, style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:$number")
            }
            context.startActivity(intent)
        }) { Text("Call") }
    }
}