package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutUsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(16.dp))
        Text("About GarageLink", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "GarageLink connects drivers with trusted local garages, making it fast and simple to find the exact spare part you need, right when you need it.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        Text("For Drivers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Search for a part and instantly see which nearby garages have it in stock, how far away they are, and how to reach them — no more calling around or driving to a garage that doesn't have what you need.")
        Spacer(Modifier.height(16.dp))
        Text("For Garage Owners", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Manage your inventory in real time, get discovered by drivers actively searching for the parts you stock, and turn every search into a walk-in customer.")
        Spacer(Modifier.height(16.dp))
        Text(
            "Our goal is simple: make it effortless to connect the right part with the right person, wherever you are.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ContactUsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(16.dp))
        Text("Contact Us", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Email: support@garagelink.app")
        Text("Phone: +91 XXXXX XXXXX")
    }
}