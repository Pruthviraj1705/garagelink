package com.pruthviraj.garagelink

import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.activity.compose.BackHandler
import io.github.jan.supabase.postgrest.query.Columns

@Serializable
data class DriverPlanPriceInfo(val subscription_plans: PlanPriceOnly?)

@Serializable
data class PlanPriceOnly(val price_inr: Double)

@Serializable
data class SearchUsageResult(
    val success: Boolean,
    val reason: String? = null,
    val limit: Int? = null,
    val unlimited: Boolean? = null,
    val remaining: Int? = null
)

@Serializable
data class GarageSearchResult(
    val garage_id: String,
    val shop_name: String,
    val address: String?,
    val phone_number: String?,
    val latitude: Double,
    val longitude: Double,
    val matched_part_name: String,
    val quantity: Int,
    val distance_km: Double
)

@Serializable
data class TopGarageResult(
    val garage_id: String,
    val shop_name: String,
    val address: String?,
    val phone_number: String?,
    val latitude: Double,
    val longitude: Double,
    val is_verified: Boolean,
    val plan_name: String?,
    val plan_price_inr: Double
)

@Serializable
data class NearbyGarageResult(
    val garage_id: String,
    val shop_name: String,
    val address: String?,
    val phone_number: String?,
    val latitude: Double,
    val longitude: Double,
    val is_verified: Boolean,
    val distance_km: Double
)

private val popularParts = listOf(
    "Spark plug", "Brake pad", "Clutch cable", "Battery",
    "Air filter", "Headlight bulb", "Wiper blade", "Engine oil"
)

@Composable
fun DriverHomeScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val postgrest = SupabaseClientProvider.client.postgrest
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var query by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var results by remember { mutableStateOf<List<GarageSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    var comingSoonMessage by remember { mutableStateOf<String?>(null) }
    var showAboutUs by remember { mutableStateOf(false) }
    var showContactUs by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showEmergencyContacts by remember { mutableStateOf(false) }
    var showNearestGarages by remember { mutableStateOf(false) }
    var showTopGarages by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showReferrals by remember { mutableStateOf(false) }
    var showSubscriptionPlans by remember { mutableStateOf(false) }
    var showAiAssistant by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isPaidDriver by remember { mutableStateOf(false) }
    var searchesSinceAd by remember { mutableStateOf(0) }
    var nextAdThreshold by remember { mutableStateOf((2..4).random()) }

    LaunchedEffect(Unit) { InterstitialAdManager.preload(context) }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        isPaidDriver = if (userId != null) {
            try {
                val price = postgrest.from("profiles")
                    .select(Columns.raw("subscription_plans(price_inr)")) { filter { eq("id", userId) } }
                    .decodeSingleOrNull<DriverPlanPriceInfo>()?.subscription_plans?.price_inr
                (price ?: 0.0) > 0.0
            } catch (e: Exception) { false }
        } else false
    }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            searchSuggestions = emptyList()
        } else {
            kotlinx.coroutines.delay(300)
            searchSuggestions = try {
                postgrest.rpc("search_part_name_suggestions", buildJsonObject {
                    put("p_query", query)
                    put("p_limit", 6)
                }).decodeList<PartNameSuggestion>().map { it.part_name }
            } catch (e: Exception) { emptyList() }
        }
    }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        unreadCount = if (userId != null) {
            try {
                postgrest.from("notifications")
                    .select { filter { eq("user_id", userId); eq("is_read", false) } }
                    .decodeList<NotificationRow>().size
            } catch (e: Exception) { 0 }
        } else 0
    }

    if (showAboutUs) {
        BackHandler { showAboutUs = false }
        AboutUsScreen(onBack = { showAboutUs = false })
        return
    }
    if (showContactUs) {
        BackHandler { showContactUs = false }
        ContactUsScreen(onBack = { showContactUs = false })
        return
    }
    if (showNotifications) {
        BackHandler { showNotifications = false }
        NotificationsScreen(onBack = { showNotifications = false }, onNotificationsChanged = {})
        return
    }
    if (showEmergencyContacts) {
        BackHandler { showEmergencyContacts = false }
        EmergencyContactsScreen(onBack = { showEmergencyContacts = false })
        return
    }
    if (showNearestGarages) {
        BackHandler { showNearestGarages = false }
        NearestGaragesScreen(onBack = { showNearestGarages = false })
        return
    }
    if (showTopGarages) {
        BackHandler { showTopGarages = false }
        TopGaragesScreen(onBack = { showTopGarages = false })
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        ProfileScreen(
            onBack = { showSettings = false },
            onLogout = {
                scope.launch {
                    PushNotifications.unregisterCurrentToken(); try { SupabaseClientProvider.client.auth.signOut() } catch (_: Exception) {}
                    onLogout()
                }
            }
        )
        return
    }

    if (showSubscriptionPlans) {
        BackHandler { showSubscriptionPlans = false }
        SubscriptionBrochureScreen(onBack = { showSubscriptionPlans = false })
        return
    }

    if (showAiAssistant) {
        BackHandler { showAiAssistant = false }
        AiAssistantScreen(garageId = null, onBack = { showAiAssistant = false })
        return
    }

    if (showReferrals) {
        BackHandler { showReferrals = false }
        ReferralsScreen(onBack = { showReferrals = false })
        return
    }

    @SuppressLint("MissingPermission")
    fun fetchLocation(onReady: () -> Unit) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    latitude = loc.latitude
                    longitude = loc.longitude
                    onReady()
                } else errorMessage = "Couldn't get your location. Try again."
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) fetchLocation {} else errorMessage = "Location permission is needed to find nearby garages" }

    fun search() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        fun runSearch() {
            val lat = latitude; val lng = longitude
            if (lat == null || lng == null) { errorMessage = "No location yet"; return }
            isSearching = true
            errorMessage = ""
            scope.launch {
                try {
                    val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                    if (userId != null) {
                        val usageCheck = postgrest.rpc("check_and_log_search", buildJsonObject {
                            put("p_user_id", userId)
                        }).decodeAs<SearchUsageResult>()

                        if (!usageCheck.success) {
                            errorMessage = "You've used all ${usageCheck.limit ?: 10} free searches today. Upgrade your plan for unlimited searches."
                            isSearching = false
                            return@launch
                        }
                    }

                    results = postgrest.rpc("search_nearby_garages_with_part", buildJsonObject {
                        put("p_part_query", query)
                        put("p_user_lat", lat)
                        put("p_user_lng", lng)
                        put("p_radius_km", 50)
                    }).decodeList<GarageSearchResult>().sortedBy { it.distance_km }

                    try {
                        postgrest.rpc("log_search_demand", buildJsonObject {
                            put("p_query", query)
                            put("p_lat", lat)
                            put("p_lng", lng)
                        })
                    } catch (_: Exception) { }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Search failed. Check your connection and try again."
                }
                isSearching = false
                hasSearched = true

                if (!isPaidDriver) {
                    searchesSinceAd++
                    if (searchesSinceAd >= nextAdThreshold) {
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            InterstitialAdManager.showIfReady(activity) {}
                        }
                        searchesSinceAd = 0
                        nextAdThreshold = (2..4).random()
                    }
                }
            }
        }

        if (hasPermission) {
            if (latitude == null) fetchLocation { runSearch() } else runSearch()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun clearSearch() {
        query = ""
        hasSearched = false
        results = emptyList()
        errorMessage = ""
        searchSuggestions = emptyList()
    }

    AppDrawerScaffold(
        title = "Find a Garage",
        searchQuery = query,
        onSearchQueryChange = { query = it },
        searchPlaceholder = "Part name (e.g. spark plug)",
        onSearchSubmit = { search() },
        onClearSearch = { clearSearch() },
        searchSuggestions = searchSuggestions,
        onSuggestionSelected = { suggestion ->
            query = suggestion
            searchSuggestions = emptyList()
            search()
        },
        onTopGarages = { maybeShowAdThenNavigate(context, isPaidDriver) { showTopGarages = true } },
        onNearestGarages = { maybeShowAdThenNavigate(context, isPaidDriver) { showNearestGarages = true } },
        onAboutUs = { showAboutUs = true },
        onContactUs = { showContactUs = true },
        onSubscriptionPlans = { showSubscriptionPlans = true },
        onEmergencyContacts = { showEmergencyContacts = true },
        onAiAssistant = { showAiAssistant = true },
        onSettings = { showSettings = true },
        onReferrals = { maybeShowAdThenNavigate(context, isPaidDriver) { showReferrals = true } },
        unreadNotificationCount = unreadCount,
        onNotificationsClick = { showNotifications = true },
        onLogout = {
            scope.launch {
                PushNotifications.unregisterCurrentToken(); try { SupabaseClientProvider.client.auth.signOut() } catch (_: Exception) {}
                onLogout()
            }
        },
        bottomBar = {
            if (isPaidDriver) SponsorBannerPlaceholder() else AdBanner()
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Button(onClick = { search() }, enabled = query.isNotBlank() && !isSearching, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSearching) "Searching..." else "Search")
            }

            if (errorMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            when {
                isSearching -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                hasSearched && results.isEmpty() && errorMessage.isBlank() -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No garages nearby have that part in stock.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                results.isNotEmpty() -> {
                    Text(
                        "${results.size} garage${if (results.size == 1) "" else "s"} found",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(results) { r ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(r.shop_name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${"%.1f".format(r.distance_km)} km",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("${r.matched_part_name} · Qty available: ${r.quantity}", style = MaterialTheme.typography.bodyMedium)
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
                else -> {
                    // Idle state - shown before the driver has searched anything. Previously
                    // this branch didn't exist at all, so the space under the search button
                    // was just empty.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Quick actions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(
                                onClick = { maybeShowAdThenNavigate(context, isPaidDriver) { showNearestGarages = true } },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.weight(1f).height(100.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(14.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        "Nearest Garages",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "By distance from you",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Card(
                                onClick = { maybeShowAdThenNavigate(context, isPaidDriver) { showTopGarages = true } },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                modifier = Modifier.weight(1f).height(100.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(14.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        "Top Garages",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Highest rated nearby",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Popular searches",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(popularParts) { part ->
                                SuggestionChip(
                                    onClick = { query = part; search() },
                                    label = { Text(part) }
                                )
                            }
                        }

                        Spacer(Modifier.height(28.dp))
                        Text(
                            "How it works",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            "1" to "Search the part you need",
                            "2" to "Compare stock and distance",
                            "3" to "Call or get directions"
                        ).forEach { (num, text) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        num,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    comingSoonMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { comingSoonMessage = null },
            title = { Text("Coming Soon") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { comingSoonMessage = null }) { Text("OK") } }
        )
    }
}

fun maybeShowAdThenNavigate(context: android.content.Context, isPaidDriver: Boolean, navigate: () -> Unit) {
    val activity = context as? android.app.Activity
    if (!isPaidDriver && activity != null) {
        InterstitialAdManager.showIfReady(activity, onDone = navigate)
    } else {
        navigate()
    }
}