package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
data class InventoryMatchCandidate(
    val id: String,
    val part_name: String,
    val part_name_normalized: String,
    val quantity: Int
)

data class ReviewRow(
    val parsed: ParsedInvoiceRow,
    val matchedItem: InventoryMatchCandidate?,
    val matchScore: Double,
    val confirmed: Boolean = false,
    val editedName: String,
    val editedQty: String
)

@Composable
fun InvoiceReviewScreen(
    garageId: String,
    rawText: String,
    structuredRows: List<ParsedInvoiceRow>? = null,
    onDone: () -> Unit
) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }
    var saveSummary by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }

    LaunchedEffect(Unit) {
        val inventory = try {
            postgrest.from("inventory_items")
                .select { filter { eq("garage_id", garageId) } }
                .decodeList<InventoryMatchCandidate>()
        } catch (_: Exception) { emptyList() }

        val parsedRows = structuredRows ?: InvoiceLineParser.parse(rawText)
        val candidateNames = inventory.map { it.part_name_normalized }

        rows = parsedRows.map { parsed ->
            val (bestMatchName, score) = FuzzyMatcher.findBestMatch(parsed.itemNameGuess, candidateNames)
            val matchedItem = inventory.find { it.part_name_normalized == bestMatchName }
            val confidentMatch = if (score >= 0.6) matchedItem else null
            ReviewRow(
                parsed = parsed,
                matchedItem = confidentMatch,
                matchScore = score,
                editedName = confidentMatch?.part_name ?: parsed.itemNameGuess,
                editedQty = parsed.quantityGuess.toString()
            )
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Review Scanned Items", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("Confirm matches before adding to inventory", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    Text(
                        text = "Recognized Text",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = rawText.ifBlank { "No text detected in scan." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            val allSelected = rows.all { it.confirmed }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked -> rows = rows.map { it.copy(confirmed = checked) } }
                )
                Text("Include all items")
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(rows) { index, row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Scanned: \"${row.parsed.rawText}\"", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(4.dp))
                            if (row.matchedItem != null) {
                                Text("Matched: ${row.matchedItem.part_name} (${(row.matchScore * 100).toInt()}% confident)")
                            } else {
                                Text("No confident match — will add as new item", color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = row.editedName,
                                onValueChange = { newVal ->
                                    rows = rows.toMutableList().apply {
                                        this[index] = this[index].copy(editedName = newVal)
                                    }
                                },
                                label = { Text("Item name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = row.editedQty,
                                onValueChange = { newVal ->
                                    rows = rows.toMutableList().apply {
                                        this[index] = this[index].copy(editedQty = newVal)
                                    }
                                },
                                label = { Text("Quantity to add") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = row.confirmed,
                                    onCheckedChange = { checked ->
                                        rows = rows.toMutableList().apply {
                                            this[index] = this[index].copy(confirmed = checked)
                                        }
                                    }
                                )
                                Text("Include this item")
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
                enabled = !isSaving && rows.any { it.confirmed },
                onClick = {
                    isSaving = true
                    scope.launch {
                        var successCount = 0
                        val failures = mutableListOf<String>()

                        rows.filter { it.confirmed }.forEach { row ->
                            val qtyToAdd = row.editedQty.toIntOrNull() ?: 0
                            try {
                                if (row.matchedItem != null) {
                                    val newQty = row.matchedItem.quantity + qtyToAdd
                                    postgrest.from("inventory_items")
                                        .update(UpdateQuantity(newQty)) { filter { eq("id", row.matchedItem.id) } }
                                } else {
                                    postgrest.from("inventory_items")
                                        .insert(NewInventoryItem(garageId, row.editedName, qtyToAdd))
                                }
                                successCount++
                            } catch (e: Exception) {
                                val friendlyReason = when {
                                    e.message?.contains("stock_limit_reached") == true ->
                                        "reached your plan's stock item limit"
                                    e.message?.contains("duplicate") == true || e.message?.contains("unique") == true ->
                                        "an item with this name already exists"
                                    else -> e.message ?: "couldn't save (check your connection)"
                                }
                                failures.add("${row.editedName}: $friendlyReason")
                            }
                        }

                        isSaving = false
                        saveSummary = successCount to failures
                        if (failures.isEmpty()) {
                            onDone()
                        }
                    }
                }
            ) { Text(if (isSaving) "Saving..." else "Add to Inventory") }
        }
    }

    saveSummary?.let { (successCount, failures) ->
        if (failures.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { saveSummary = null },
                title = { Text("Some items didn't save") },
                text = {
                    Column {
                        if (successCount > 0) {
                            Text("$successCount item${if (successCount == 1) "" else "s"} added successfully.")
                            Spacer(Modifier.height(8.dp))
                        }
                        Text("Failed:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        failures.forEach { msg ->
                            Text("• $msg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { saveSummary = null }) { Text("OK") }
                },
                dismissButton = {
                    if (successCount > 0) {
                        TextButton(onClick = { saveSummary = null; onDone() }) { Text("Done") }
                    }
                }
            )
        }
    }
}