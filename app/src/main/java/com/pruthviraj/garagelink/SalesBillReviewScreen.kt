package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch

data class SaleReviewRow(
    val scanned: ScannedSaleItem,
    val matchedItem: InventoryMatchCandidate?,
    val matchScore: Double,
    val confirmed: Boolean,
    val editedQty: String
)

@Composable
fun SalesBillReviewScreen(
    garageId: String,
    scannedItems: List<ScannedSaleItem>,
    onDone: () -> Unit
) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<SaleReviewRow>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val inventory = try {
            postgrest.from("inventory_items")
                .select { filter { eq("garage_id", garageId) } }
                .decodeList<InventoryMatchCandidate>()
        } catch (_: Exception) { emptyList() }

        val candidateNames = inventory.map { it.part_name_normalized }

        rows = scannedItems.map { scanned ->
            val (bestMatchName, score) = FuzzyMatcher.findBestMatch(scanned.name, candidateNames)
            val matchedItem = inventory.find { it.part_name_normalized == bestMatchName }
            val confidentMatch = if (score >= 0.6) matchedItem else null
            SaleReviewRow(
                scanned = scanned,
                matchedItem = confidentMatch,
                matchScore = score,
                // Only auto-confirm items with a confident inventory match -
                // unmatched items need the owner to actively find and pick one.
                confirmed = confidentMatch != null,
                editedQty = scanned.quantity.toString()
            )
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review Sale", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(
            "Confirm what to deduct from stock",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No items found on this bill.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(rows) { index, row ->
                    val qty = row.editedQty.toIntOrNull() ?: 0
                    val insufficientStock = row.matchedItem != null && qty > row.matchedItem.quantity

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                row.matchedItem == null -> MaterialTheme.colorScheme.errorContainer
                                insufficientStock -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Scanned: \"${row.scanned.name}\"", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(4.dp))

                            if (row.matchedItem != null) {
                                Text(
                                    "Matched: ${row.matchedItem.part_name} · ${row.matchedItem.quantity} in stock (${(row.matchScore * 100).toInt()}% confident)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                if (insufficientStock) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Only ${row.matchedItem.quantity} in stock - deducting will floor at 0",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            } else {
                                Text(
                                    "No matching inventory item — can't deduct this. Skipped.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = row.editedQty,
                                onValueChange = { newVal ->
                                    rows = rows.toMutableList().apply { this[index] = this[index].copy(editedQty = newVal) }
                                },
                                label = { Text("Quantity sold") },
                                enabled = row.matchedItem != null,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = row.confirmed,
                                    enabled = row.matchedItem != null,
                                    onCheckedChange = { checked ->
                                        rows = rows.toMutableList().apply { this[index] = this[index].copy(confirmed = checked) }
                                    }
                                )
                                Text("Deduct this item")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Button(
                enabled = !isSaving && rows.any { it.confirmed && it.matchedItem != null },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                onClick = {
                    isSaving = true
                    scope.launch {
                        rows.filter { it.confirmed && it.matchedItem != null }.forEach { row ->
                            val matched = row.matchedItem!!
                            val qtyToDeduct = row.editedQty.toIntOrNull() ?: 0
                            val newQty = (matched.quantity - qtyToDeduct).coerceAtLeast(0)
                            try {
                                postgrest.from("inventory_items")
                                    .update(mapOf("quantity" to newQty)) { filter { eq("id", matched.id) } }
                            } catch (_: Exception) { }
                        }
                        isSaving = false
                        onDone()
                    }
                }
            ) { Text(if (isSaving) "Saving..." else "Deduct from Inventory") }
        }
    }
}