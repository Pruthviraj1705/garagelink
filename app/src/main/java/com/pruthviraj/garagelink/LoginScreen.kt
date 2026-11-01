package com.pruthviraj.garagelink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.launch

// Maps raw Supabase auth error text (which includes URLs, headers, HTTP method - all
// meant for debugging, not users) down to one short, calm line. Anything unrecognized
// falls through to a generic message rather than ever showing the raw dump.
private fun friendlyAuthError(raw: String?): String {
    if (raw == null) return "Something went wrong. Please try again."
    return when {
        raw.contains("rate_limit", ignoreCase = true) ->
            "You're requesting codes too fast. Please wait a moment and try again."
        raw.contains("sms_send_failed", ignoreCase = true) ->
            "Couldn't send the code. Please check the number and try again."
        raw.contains("otp", ignoreCase = true) && raw.contains("expired", ignoreCase = true) ->
            "That code has expired. Please request a new one."
        raw.contains("otp", ignoreCase = true) && raw.contains("invalid", ignoreCase = true) ->
            "That code isn't right. Please check and try again."
        raw.contains("network", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) || raw.contains("unable to resolve", ignoreCase = true) ->
            "Couldn't connect. Please check your internet and try again."
        else -> "Something went wrong. Please try again."
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val auth = SupabaseClientProvider.client.auth

    fun formattedPhone() = "+91$phoneNumber"

    Box(modifier = Modifier.fillMaxSize()) {
        // Hero header - deep blue gradient, holds the brand moment. Fixed height rather
        // than weight() so the overlapping card below has a consistent amount to sit on.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Welcome to GarageLink",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "We'll help you find it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
        }

        // Form card - overlaps the header by 32dp so it reads as one deliberate
        // composition rather than a header stacked flush on top of a form.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 268.dp)
        ) {
            Card(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 40.dp)
                ) {
                    Text(
                        if (!otpSent) "Log in with your phone number" else "Verify your number",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!otpSent) "We'll text you a one-time code" else "Enter the code sent to +91 $phoneNumber",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))

                    if (!otpSent) {
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { input ->
                                val digitsOnly = input.filter { it.isDigit() }
                                if (digitsOnly.length <= 10) phoneNumber = digitsOnly
                            },
                            label = { Text("Phone number") },
                            placeholder = { Text("9999999999") },
                            prefix = { Text("+91 ") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        auth.signInWith(OTP) {
                                            phone = formattedPhone()
                                        }
                                        otpSent = true
                                    } catch (e: Exception) {
                                        errorMessage = friendlyAuthError(e.message)
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = phoneNumber.length == 10 && !isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(if (isLoading) "Sending..." else "Send OTP", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        OutlinedTextField(
                            value = otpCode,
                            onValueChange = { otpCode = it },
                            label = { Text("Enter OTP") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        auth.verifyPhoneOtp(
                                            type = OtpType.Phone.SMS,
                                            phone = formattedPhone(),
                                            token = otpCode
                                        )
                                        onLoginSuccess()
                                    } catch (e: Exception) {
                                        errorMessage = friendlyAuthError(e.message)
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = otpCode.isNotBlank() && !isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(if (isLoading) "Verifying..." else "Verify & Login", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { otpSent = false; otpCode = ""; errorMessage = null }) {
                            Text("Change phone number")
                        }
                    }

                    errorMessage?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}