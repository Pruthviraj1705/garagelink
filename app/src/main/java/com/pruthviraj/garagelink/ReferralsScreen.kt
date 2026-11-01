package com.pruthviraj.garagelink

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ReferralRedeemResult(
    val success: Boolean,
    val reason: String? = null,
    val reward_days: Int? = null
)

@Composable
fun ReferralsScreen(onBack: () -> Unit) {
    val postgrest = SupabaseClientProvider.client.postgrest
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    // The custom blue color extracted from your image
    val brandBlue = Color(0xFF24548D)

    var myCode by remember { mutableStateOf<String?>(null) }
    var isLoadingCode by remember { mutableStateOf(true) }
    var enteredCode by remember { mutableStateOf("") }
    var isRedeeming by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        myCode = if (userId != null) {
            try {
                postgrest.rpc("get_or_create_referral_code", buildJsonObject {
                    put("p_user_id", userId)
                }).data.trim('"')
            } catch (e: Exception) { null }
        } else null
        isLoadingCode = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("< Back") }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(brandBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CardGiftcard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Refer & Earn", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Share your code — when a friend redeems it, you both benefit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        // Updated Referral Card with Custom Blue
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = brandBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Your referral code",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f) // Made text white for contrast
                )
                Spacer(Modifier.height(10.dp))
                if (isLoadingCode) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else if (myCode != null) {
                    Text(
                        myCode!!,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        color = Color.White // Made code white
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(myCode!!)) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), // Added white border
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Use my GarageLink referral code $myCode and we both get bonus days! Download GarageLink to get started.")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share your code"))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White, // Solid white button
                                contentColor = brandBlue      // Blue text to match card
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                } else {
                    Text(
                        "Couldn't load your code. Try again later.",
                        color = MaterialTheme.colorScheme.errorContainer, // Softer error color on dark bg
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Have a code?", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { enteredCode = it.uppercase() },
                    label = { Text("Enter referral code") },
                    singleLine = true,
                    enabled = !isRedeeming,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = enteredCode.isNotBlank() && !isRedeeming,
                    onClick = {
                        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                        if (userId == null) {
                            resultMessage = "Not logged in"; isError = true
                            return@Button
                        }
                        isRedeeming = true
                        resultMessage = null
                        scope.launch {
                            try {
                                val result = postgrest.rpc("redeem_referral_code", buildJsonObject {
                                    put("p_code", enteredCode)
                                    put("p_referred_user_id", userId)
                                }).decodeAs<ReferralRedeemResult>()

                                if (result.success) {
                                    isError = false
                                    resultMessage = "Code redeemed! Your friend earned ${result.reward_days ?: 7} bonus days."
                                } else {
                                    isError = true
                                    resultMessage = when (result.reason) {
                                        "invalid_code" -> "That code doesn't exist."
                                        "self_referral" -> "You can't redeem your own code."
                                        "already_referred" -> "You've already redeemed a referral code."
                                        "not_authorized" -> "Couldn't verify your account for this."
                                        else -> "Couldn't redeem this code."
                                    }
                                }
                            } catch (e: Exception) {
                                isError = true
                                resultMessage = "Something went wrong. Please try again."
                            }
                            isRedeeming = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (isRedeeming) "Redeeming..." else "Redeem") }

                resultMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}