package com.pruthviraj.garagelink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AiUsageInfo(val limit: Int? = null, val used: Int = 0, val unlimited: Boolean = false)

@Serializable
data class LowStockCheckRow(val part_name: String, val quantity: Int, val low_stock_threshold: Int)

data class ChatMessage(val text: String, val isUser: Boolean, val hadImage: Boolean = false)

@Composable
fun AiAssistantScreen(garageId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val role = if (garageId != null) "garage_owner" else "driver"

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<AiUsageInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAttachOptions by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selectedImageUri = uri
    }

    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) selectedImageUri = pendingCameraUri
    }

    fun launchCamera() {
        val photoFile = File(context.cacheDir, "ai_chat_photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        pendingCameraUri = uri
        cameraCapture.launch(uri)
    }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (userId != null) {
            usage = try {
                postgrest.rpc("get_ai_message_usage", buildJsonObject { put("p_user_id", userId) })
                    .decodeAs<AiUsageInfo>()
            } catch (e: Exception) { null }
        }
    }

    suspend fun fetchLowStockContext(): String? {
        if (garageId == null) return null
        return try {
            val rows = postgrest.from("inventory_items")
                .select(Columns.list("part_name", "quantity", "low_stock_threshold")) {
                    filter { eq("garage_id", garageId) }
                }
                .decodeList<LowStockCheckRow>()
                .filter { it.quantity < it.low_stock_threshold }

            if (rows.isEmpty()) "No parts are currently low on stock."
            else rows.joinToString("\n") { "${it.part_name}: ${it.quantity} left (threshold ${it.low_stock_threshold})" }
        } catch (e: Exception) { null }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isSending) return
        val imageUri = selectedImageUri

        messages = messages + ChatMessage(text, isUser = true, hadImage = imageUri != null)
        inputText = ""
        selectedImageUri = null
        isSending = true
        errorMessage = null

        scope.launch {
            val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
            if (userId == null) {
                errorMessage = "Not logged in"
                isSending = false
                return@launch
            }

            val businessContext = if (role == "garage_owner") fetchLowStockContext() else null
            val history = messages.dropLast(1).takeLast(6).map { it.text to it.isUser }

            when (val result = AiAssistantApi.sendMessage(context, text, imageUri, role, businessContext, history)) {
                is AiAssistantResult.Success -> {
                    messages = messages + ChatMessage(result.reply, isUser = false)
                    usage = usage?.let { it.copy(used = it.used + 1) }
                }
                is AiAssistantResult.LimitReached -> {
                    errorMessage = "You've reached your daily AI Assistant limit (${result.limit ?: 5} messages). Upgrade your plan for more."
                    usage = usage?.copy(used = usage?.limit ?: 0)
                }
                is AiAssistantResult.Failure -> {
                    errorMessage = result.message
                }
            }
            isSending = false
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
            usage?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (!it.unlimited && it.used >= (it.limit ?: 0))
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        if (it.unlimited) "Unlimited" else "${it.used}/${it.limit} today",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (!it.unlimited && it.used >= (it.limit ?: 0))
                            MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (role == "garage_owner") "Business Assistant" else "AI Assistant",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    "Powered by Gemini",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (role == "garage_owner")
                            "Ask about restocking, pricing, or your business performance."
                        else
                            "Describe a vehicle problem, or attach a photo of a part to identify it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                itemsIndexed(messages) { _, msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        if (!msg.isUser) {
                            Box(
                                modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Card(
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                bottomEnd = if (msg.isUser) 4.dp else 16.dp
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.isUser) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.widthIn(max = 260.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (msg.hadImage) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.PhotoCamera,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (msg.isUser) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Photo attached",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (msg.isUser) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (msg.isUser) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (msg.isUser) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (isSending) {
                    item {
                        Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }

        errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        selectedImageUri?.let {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Photo attached", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { selectedImageUri = null }) { Text("Remove") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showAttachOptions = true }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach photo")
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text(if (role == "garage_owner") "Ask about your business..." else "Ask about a vehicle problem...") },
                enabled = !isSending,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { sendMessage() },
                enabled = !isSending && inputText.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }

    if (showAttachOptions) {
        AlertDialog(
            onDismissRequest = { showAttachOptions = false },
            title = { Text("Add a photo") },
            text = {
                Column {
                    TextButton(onClick = { showAttachOptions = false; launchCamera() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Take Photo")
                    }
                    TextButton(onClick = { showAttachOptions = false; galleryPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose from Gallery")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAttachOptions = false } ) { Text("Cancel") } }
        )
    }
}