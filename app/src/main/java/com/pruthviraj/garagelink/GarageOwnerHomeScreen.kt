package com.pruthviraj.garagelink

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.Json

@Serializable
data class InventoryItemRow(
    val id: String,
    val part_name: String,
    val quantity: Int,
    val low_stock_threshold: Int = 5
)

@Serializable
data class NewInventoryItem(val garage_id: String, val part_name: String, val quantity: Int)

@Serializable
data class UpdateQuantity(val quantity: Int)

@Serializable
data class PromoRedemptionResult(
    val success: Boolean,
    val reason: String? = null,
    val code_type: String? = null,
    val reward_duration_days: Int? = null
)

enum class InventorySortOption(val label: String) {
    DEFAULT("Default"),
    QTY_ASC("Quantity: Low to High"),
    QTY_DESC("Quantity: High to Low"),
    NAME_ASC("Name: A-Z"),
    NAME_DESC("Name: Z-A")
}

// Feature flags: set either to false to hide that section behind a "Coming Soon" dialog.
private const val PROMO_REFERRALS_LIVE = true
private const val ANALYTICS_LIVE = true

@Composable
fun GarageOwnerHomeScreen(garageId: String, shopName: String, onLogout: () -> Unit) {
    var items by remember { mutableStateOf<List<InventoryItemRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<InventoryItemRow?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showSalesBillScanner by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showPromoDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(InventorySortOption.DEFAULT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var comingSoonMessage by remember { mutableStateOf<String?>(null) }
    var showAboutUs by remember { mutableStateOf(false) }
    var showContactUs by remember { mutableStateOf(false) }
    var showSubscriptionPlans by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showEmergencyContacts by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showReferrals by remember { mutableStateOf(false) }
    var showAnalytics by remember { mutableStateOf(false) }
    var showAiAssistant by remember { mutableStateOf(false) }
    var showTopGaragesReal by remember { mutableStateOf(false) }
    var showNearestGaragesReal by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }
    var isShowingCachedData by remember { mutableStateOf(false) }
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(garageId, refreshTrigger) {
        isLoading = true
        val prefs = context.getSharedPreferences("inventory_cache", android.content.Context.MODE_PRIVATE)
        val cacheKey = "items_$garageId"
        try {
            val fetched = postgrest.from("inventory_items")
                .select { filter { eq("garage_id", garageId) } }
                .decodeList<InventoryItemRow>()
            items = fetched
            isShowingCachedData = false
            prefs.edit().putString(cacheKey, Json.encodeToString(fetched)).apply()
        } catch (e: Exception) {
            val cachedJson = prefs.getString(cacheKey, null)
            if (cachedJson != null) {
                items = try {
                    Json.decodeFromString<List<InventoryItemRow>>(cachedJson)
                } catch (_: Exception) { emptyList() }
                isShowingCachedData = true
            } else {
                items = emptyList()
                isShowingCachedData = false
            }
        }
        isLoading = false
    }

    LaunchedEffect(refreshTrigger) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        unreadCount = if (userId != null) {
            try {
                postgrest.from("notifications")
                    .select { filter { eq("user_id", userId); eq("is_read", false) } }
                    .decodeList<NotificationRow>().size
            } catch (e: Exception) { 0 }
        } else 0
    }

    if (showScanner) {
        BackHandler { showScanner = false; refreshTrigger++ }
        OcrScanScreen(garageId = garageId, onBack = { showScanner = false; refreshTrigger++ })
        return
    }
    if (showSalesBillScanner) {
        BackHandler { showSalesBillScanner = false; refreshTrigger++ }
        SalesBillScanScreen(garageId = garageId, onBack = { showSalesBillScanner = false; refreshTrigger++ })
        return
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
    if (showSubscriptionPlans) {
        BackHandler { showSubscriptionPlans = false }
        SubscriptionBrochureScreen(garageId = garageId, onBack = { showSubscriptionPlans = false })
        return
    }
    if (showNotifications) {
        BackHandler { showNotifications = false }
        NotificationsScreen(onBack = { showNotifications = false }, onNotificationsChanged = { refreshTrigger++ })
        return
    }
    if (showEmergencyContacts) {
        BackHandler { showEmergencyContacts = false }
        EmergencyContactsScreen(onBack = { showEmergencyContacts = false })
        return
    }
    if (showProfile) {
        BackHandler { showProfile = false }
        ProfileScreen(
            garageId = garageId,
            onBack = { showProfile = false },
            onLogout = {
                scope.launch {
                    PushNotifications.unregisterCurrentToken(); try { SupabaseClientProvider.client.auth.signOut() } catch (_: Exception) {}
                    onLogout()
                }
            }
        )
        return
    }
    if (showTopGaragesReal) {
        BackHandler { showTopGaragesReal = false }
        TopGaragesScreen(onBack = { showTopGaragesReal = false })
        return
    }
    if (showNearestGaragesReal) {
        BackHandler { showNearestGaragesReal = false }
        NearestGaragesScreen(onBack = { showNearestGaragesReal = false })
        return
    }
    if (showReferrals) {
        BackHandler { showReferrals = false }
        ReferralsScreen(onBack = { showReferrals = false })
        return
    }

    if (showAnalytics) {
        BackHandler { showAnalytics = false }
        AnalyticsScreen(garageId = garageId, onBack = { showAnalytics = false })
        return
    }

    if (showAiAssistant) {
        BackHandler { showAiAssistant = false }
        AiAssistantScreen(garageId = garageId, onBack = { showAiAssistant = false })
        return
    }

    val filteredItems = (if (searchQuery.isBlank()) items
    else items.filter { it.part_name.contains(searchQuery, ignoreCase = true) }).let { list ->
        when (sortOption) {
            InventorySortOption.DEFAULT -> list
            InventorySortOption.QTY_ASC -> list.sortedBy { it.quantity }
            InventorySortOption.QTY_DESC -> list.sortedByDescending { it.quantity }
            InventorySortOption.NAME_ASC -> list.sortedBy { it.part_name.lowercase() }
            InventorySortOption.NAME_DESC -> list.sortedByDescending { it.part_name.lowercase() }
        }
    }

    val lowStockCount = items.count { it.quantity < it.low_stock_threshold }

    AppDrawerScaffold(
        title = shopName,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        searchPlaceholder = "Search your inventory",
        onTopGarages = { showTopGaragesReal = true },
        onNearestGarages = { showNearestGaragesReal = true },
        onAboutUs = { showAboutUs = true },
        onContactUs = { showContactUs = true },
        onSubscriptionPlans = { showSubscriptionPlans = true },
        onEmergencyContacts = { showEmergencyContacts = true },
        onAiAssistant = { showAiAssistant = true },
        onSettings = { showProfile = true },
        onReferrals = {
            if (PROMO_REFERRALS_LIVE) showReferrals = true
            else comingSoonMessage = "Refer & Earn is coming in a future update."
        },
        unreadNotificationCount = unreadCount,
        onNotificationsClick = { showNotifications = true },
        onLogout = {
            scope.launch {
                PushNotifications.unregisterCurrentToken(); try { SupabaseClientProvider.client.auth.signOut() } catch (_: Exception) {}
                onLogout()
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!isShowingCachedData) showAddDialog = true },
                containerColor = if (isShowingCachedData) MaterialTheme.colorScheme.surfaceVariant else FloatingActionButtonDefaults.containerColor
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
        bottomBar = {
            SponsorBannerPlaceholder()
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (isShowingCachedData) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Offline — showing cached inventory. Editing needs a connection.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Inventory (${items.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lowStockCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                "$lowStockCount low stock",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            InventorySortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (sortOption == option) {
                                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = { sortOption = option; showSortMenu = false }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Button(
                    onClick = { showScanner = true },
                    enabled = !isShowingCachedData,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) { Text("Scan Invoice") }
                OutlinedButton(
                    onClick = { showSalesBillScanner = true },
                    enabled = !isShowingCachedData,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Scan Sale") }
                OutlinedButton(
                    onClick = { showPromoDialog = true },
                    enabled = !isShowingCachedData,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Redeem Code") }
                OutlinedButton(
                    onClick = {
                        if (ANALYTICS_LIVE) showAnalytics = true
                        else comingSoonMessage = "Analytics is coming in a future update."
                    },
                    enabled = !isShowingCachedData,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Analytics") }
            }
            Spacer(Modifier.height(16.dp))
            when {
                isLoading -> Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filteredItems.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isBlank()) "No parts in stock yet." else "No matching parts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn {
                    items(filteredItems) { item ->
                        val isLow = item.quantity < item.low_stock_threshold
                        val avatarColor = if (isLow) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                        val onAvatarColor = if (isLow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        val pillColor = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer
                        val onPillColor = if (isLow) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer

                        Card(
                            onClick = { if (!isShowingCachedData) editItem = item },
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isShowingCachedData) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(avatarColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        item.part_name.trim().take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = onAvatarColor
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.part_name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (isLow) "Running low" else "In stock",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = pillColor
                                ) {
                                    Text(
                                        "${item.quantity}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = onPillColor,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
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

    if (showAddDialog) {
        var partName by remember { mutableStateOf("") }
        var quantity by remember { mutableStateOf("") }
        var isAdding by remember { mutableStateOf(false) }
        var addError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isAdding) { showAddDialog = false; addError = null } },
            title = { Text("Add Part") },
            text = {
                Column {
                    PartNameAutocompleteField(value = partName, onValueChange = { partName = it }, enabled = !isAdding)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, enabled = !isAdding)
                    addError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isAdding && partName.isNotBlank(),
                    onClick = {
                        val qty = quantity.toIntOrNull() ?: 0
                        isAdding = true
                        addError = null
                        scope.launch {
                            try {
                                postgrest.from("inventory_items").insert(NewInventoryItem(garageId, partName, qty))
                                refreshTrigger++
                                showAddDialog = false
                            } catch (e: Exception) {
                                addError = if (e.message?.contains("stock_limit_reached") == true) {
                                    "You've reached your plan's stock item limit. Upgrade your plan to add more parts."
                                } else {
                                    e.message ?: "Couldn't add this part. Try again."
                                }
                            }
                            isAdding = false
                        }
                    }
                ) { Text(if (isAdding) "Adding..." else "Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; addError = null }, enabled = !isAdding) { Text("Cancel") }
            }
        )
    }

    editItem?.let { item ->
        var quantity by remember { mutableStateOf(item.quantity.toString()) }
        var mutedUntilMillis by remember { mutableStateOf<Long?>(null) }
        var isMuteLoading by remember { mutableStateOf(true) }
        var isBusy by remember { mutableStateOf(false) }
        var actionError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(item.id) {
            mutedUntilMillis = try {
                val mute = postgrest.from("notification_mutes")
                    .select { filter { eq("garage_id", garageId); eq("inventory_item_id", item.id) } }
                    .decodeSingleOrNull<MuteRow>()
                mute?.let { parseIsoToMillis(it.muted_until) }?.takeIf { it > System.currentTimeMillis() }
            } catch (e: Exception) {
                android.util.Log.w("GarageOwnerHome", "Couldn't load mute status", e)
                null
            }
            isMuteLoading = false
        }

        AlertDialog(
            onDismissRequest = { if (!isBusy) editItem = null },
            title = { Text(item.part_name) },
            text = {
                Column {
                    OutlinedTextField(
                        quantity,
                        { quantity = it },
                        label = { Text("Quantity") },
                        enabled = !isBusy
                    )
                    actionError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("Low-stock notifications", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    if (isMuteLoading) {
                        Text("Checking...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val muted = mutedUntilMillis
                        if (muted != null) {
                            Text(
                                "Muted until ${formatMillisAsDate(muted)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = {
                                    isBusy = true
                                    actionError = null
                                    scope.launch {
                                        try {
                                            postgrest.from("notification_mutes").delete {
                                                filter { eq("garage_id", garageId); eq("inventory_item_id", item.id) }
                                            }
                                            mutedUntilMillis = null
                                        } catch (e: Exception) {
                                            android.util.Log.e("GarageOwnerHome", "Unmute failed", e)
                                            actionError = "Couldn't unmute. Check your connection and try again."
                                        }
                                        isBusy = false
                                    }
                                }
                            ) { Text("Unmute now") }
                        } else {
                            Text(
                                "You'll be notified when this part runs low",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                listOf(1 to "1d", 7 to "7d", 30 to "30d").forEach { (days, label) ->
                                    OutlinedButton(
                                        enabled = !isBusy,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        onClick = {
                                            val untilIso = isoTimestampFromNowPlus(days)
                                            isBusy = true
                                            actionError = null
                                            scope.launch {
                                                try {
                                                    postgrest.from("notification_mutes").upsert(
                                                        NewMute(garageId, item.id, untilIso)
                                                    ) { onConflict = "garage_id,inventory_item_id" }
                                                    mutedUntilMillis = System.currentTimeMillis() + days.toLong() * 86_400_000
                                                } catch (e: Exception) {
                                                    android.util.Log.e("GarageOwnerHome", "Mute failed", e)
                                                    actionError = "Couldn't mute notifications. Check your connection and try again."
                                                }
                                                isBusy = false
                                            }
                                        }
                                    ) { Text("Mute $label") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = {
                        val qty = quantity.toIntOrNull() ?: item.quantity
                        isBusy = true
                        actionError = null
                        scope.launch {
                            try {
                                postgrest.from("inventory_items")
                                    .update(UpdateQuantity(qty)) { filter { eq("id", item.id) } }
                                refreshTrigger++
                                editItem = null
                            } catch (e: Exception) {
                                android.util.Log.e("GarageOwnerHome", "Update quantity failed", e)
                                actionError = "Couldn't save the change. Check your connection and try again."
                            }
                            isBusy = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !isBusy,
                        onClick = {
                            isBusy = true
                            actionError = null
                            scope.launch {
                                try {
                                    postgrest.from("inventory_items").delete { filter { eq("id", item.id) } }
                                    refreshTrigger++
                                    editItem = null
                                } catch (e: Exception) {
                                    android.util.Log.e("GarageOwnerHome", "Delete item failed", e)
                                    actionError = "Couldn't delete this part. Check your connection and try again."
                                }
                                isBusy = false
                            }
                        }
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editItem = null }, enabled = !isBusy) { Text("Cancel") }
                }
            }
        )
    }

    if (showPromoDialog) {
        var code by remember { mutableStateOf("") }
        var isRedeeming by remember { mutableStateOf(false) }
        var resultMessage by remember { mutableStateOf<String?>(null) }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPromoDialog = false },
            title = { Text("Redeem Promo Code") },
            text = {
                Column {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        label = { Text("Promo code") },
                        singleLine = true,
                        enabled = !isRedeeming
                    )
                    resultMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.isNotBlank() && !isRedeeming,
                    onClick = {
                        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                        if (userId == null) {
                            resultMessage = "Not logged in"; isError = true
                            return@TextButton
                        }
                        isRedeeming = true
                        resultMessage = null
                        scope.launch {
                            try {
                                val result = postgrest.rpc("redeem_promo_code", buildJsonObject {
                                    put("p_code", code)
                                    put("p_user_id", userId)
                                }).decodeAs<PromoRedemptionResult>()

                                if (result.success) {
                                    isError = false
                                    resultMessage = when (result.code_type) {
                                        "garage_trial" -> "Trial activated! ${result.reward_duration_days ?: 0} days added."
                                        else -> "Code redeemed successfully!"
                                    }
                                    refreshTrigger++
                                } else {
                                    isError = true
                                    resultMessage = when (result.reason) {
                                        "invalid_code" -> "That code doesn't exist or isn't active."
                                        "expired" -> "This code has expired."
                                        "max_usage_reached" -> "This code has reached its usage limit."
                                        "already_redeemed_by_user" -> "You've already used this code."
                                        "trial_already_used" -> "You've already used your free trial."
                                        "not_authorized" -> "Couldn't verify your account for this."
                                        else -> "Couldn't redeem this code."
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("GarageOwnerHome", "Redeem promo failed", e)
                                isError = true
                                resultMessage = "Something went wrong. Please try again."
                            }
                            isRedeeming = false
                        }
                    }
                ) { Text(if (isRedeeming) "Redeeming..." else "Redeem") }
            },
            dismissButton = { TextButton(onClick = { showPromoDialog = false }) { Text("Close") } }
        )
    }
}

@Serializable
data class MuteRow(val muted_until: String)

@Serializable
data class NewMute(val garage_id: String, val inventory_item_id: String, val muted_until: String)

fun isoTimestampFromNowPlus(days: Int): String {
    val millis = System.currentTimeMillis() + days.toLong() * 86_400_000
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(millis))
}

fun parseIsoToMillis(iso: String): Long? = try {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    sdf.parse(iso.take(19))?.time
} catch (e: Exception) { null }

fun formatMillisAsDate(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("dd MMM, h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(millis))
}