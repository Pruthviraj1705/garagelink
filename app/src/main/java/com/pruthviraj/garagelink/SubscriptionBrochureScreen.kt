package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SubscriptionPlan(
    val id: String,
    val name: String,
    val price_inr: Double,
    val duration_days: Int,
    val features: List<String>
)

@Serializable
data class PurchaseResult(
    val success: Boolean,
    val reason: String? = null,
    val new_expiry: String? = null,
    val plan_name: String? = null
)

@Composable
fun SubscriptionBrochureScreen(garageId: String? = null, onBack: () -> Unit) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()
    val targetRole = if (garageId != null) "garage_owner" else "driver"

    var plans by remember { mutableStateOf<List<SubscriptionPlan>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPlan by remember { mutableStateOf<SubscriptionPlan?>(null) }
    var isPurchasing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        plans = try {
            postgrest.from("subscription_plans")
                .select { filter { eq("target_role", targetRole); eq("is_active", true) } }
                .decodeList<SubscriptionPlan>()
                .sortedBy { it.price_inr }
        } catch (e: Exception) { emptyList() }
        isLoading = false
    }

    // Paid plans, sorted cheapest-first as loaded - the middle one (or the only paid
    // one, if there are just two plans total) gets a "Most Popular" badge to draw the
    // eye, since this screen's whole job is nudging toward an upgrade.
    val paidPlans = plans.filter { it.price_inr > 0.0 }
    val popularPlanId = when {
        paidPlans.size >= 2 -> paidPlans[paidPlans.size / 2].id
        paidPlans.size == 1 -> paidPlans[0].id
        else -> null
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(4.dp))
        Text("Subscription Plans", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(
            if (garageId != null) "Choose the plan that fits your garage" else "Choose the plan that fits how you drive",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(plans) { plan ->
                    val isPopular = plan.id == popularPlanId
                    val isFree = plan.price_inr == 0.0

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isPopular) 6.dp else 1.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPopular) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            if (isPopular) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondary
                                ) {
                                    Text(
                                        "MOST POPULAR",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            Text(
                                plan.name,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (isPopular) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (isFree) "Free" else "₹${plan.price_inr.toInt()}",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isPopular) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                )
                                if (!isFree) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "/ ${if (plan.duration_days >= 365) "year" else "month"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isPopular) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            plan.features.forEach { feature ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = if (isPopular) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        feature,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isPopular) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = { selectedPlan = plan },
                                enabled = !isFree,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(if (isFree) "Current default" else "Purchase")
                            }
                        }
                    }
                }
            }
        }
    }

    selectedPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!isPurchasing) { selectedPlan = null; resultMessage = null } },
            title = { Text("Confirm Purchase") },
            text = {
                Column {
                    Text(
                        "${plan.name} — ₹${plan.price_inr.toInt()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is a manual/placeholder payment flow — no real payment gateway is connected yet. Confirming will activate your subscription directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    resultMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isPurchasing,
                    onClick = {
                        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                        if (userId == null) {
                            resultMessage = "Not logged in"; isError = true
                            return@TextButton
                        }
                        isPurchasing = true
                        resultMessage = null
                        scope.launch {
                            try {
                                val result = if (garageId != null) {
                                    postgrest.rpc("purchase_subscription_manual", buildJsonObject {
                                        put("p_garage_id", garageId)
                                        put("p_plan_id", plan.id)
                                        put("p_user_id", userId)
                                    }).decodeAs<PurchaseResult>()
                                } else {
                                    postgrest.rpc("purchase_driver_subscription_manual", buildJsonObject {
                                        put("p_plan_id", plan.id)
                                        put("p_user_id", userId)
                                    }).decodeAs<PurchaseResult>()
                                }

                                if (result.success) {
                                    isError = false
                                    resultMessage = "${result.plan_name} activated!"
                                } else {
                                    isError = true
                                    resultMessage = when (result.reason) {
                                        "invalid_plan" -> "This plan is no longer available."
                                        "not_authorized" -> "Couldn't verify your account for this purchase."
                                        else -> "Purchase failed. Try again."
                                    }
                                }
                            } catch (e: Exception) {
                                isError = true
                                resultMessage = "Something went wrong. Please try again."
                            }
                            isPurchasing = false
                        }
                    }
                ) { Text(if (isPurchasing) "Processing..." else "Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { selectedPlan = null; resultMessage = null }) { Text("Cancel") }
            }
        )
    }
}