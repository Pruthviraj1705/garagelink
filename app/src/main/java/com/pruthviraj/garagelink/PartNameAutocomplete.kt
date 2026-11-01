package com.pruthviraj.garagelink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.clickable

@Serializable
data class PartNameSuggestion(val part_name: String)

@Composable
fun PartNameAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Part name",
    enabled: Boolean = true
) {
    val postgrest = SupabaseClientProvider.client.postgrest
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    // Waits for a pause in typing before querying, so every keystroke doesn't fire a network call
    LaunchedEffect(value) {
        if (value.trim().length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = try {
            postgrest.rpc("search_part_name_suggestions", buildJsonObject {
                put("p_query", value)
                put("p_limit", 6)
            }).decodeList<PartNameSuggestion>().map { it.part_name }
        } catch (e: Exception) { emptyList() }
        showSuggestions = suggestions.isNotEmpty()
    }

    Column {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                showSuggestions = true
            },
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (showSuggestions && suggestions.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        Text(
                            suggestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(suggestion)
                                    showSuggestions = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}