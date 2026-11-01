package com.pruthviraj.garagelink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ChatHistoryItem(val text: String, val isUser: Boolean)

@Serializable
data class AiAssistantRequest(
    val message: String,
    val imageBase64: String? = null,
    val role: String,
    val businessContext: String? = null,
    val history: List<ChatHistoryItem> = emptyList()
)

@Serializable
data class AiAssistantResponse(val reply: String? = null, val error: String? = null, val limit: Int? = null)

sealed class AiAssistantResult {
    data class Success(val reply: String) : AiAssistantResult()
    data class LimitReached(val limit: Int?) : AiAssistantResult()
    data class Failure(val message: String) : AiAssistantResult()
}

object AiAssistantApi {
    private const val ENDPOINT = "${SupabaseConfig.URL}/functions/v1/bright-action"
    // Still sent as the "apikey" header - that's Supabase's normal client-identification
    // header for the gateway, separate from user auth. It's fine that it's public; it was
    // never the thing protecting per-user usage limits, and still isn't.
    private const val SUPABASE_ANON_KEY = SupabaseConfig.ANON_KEY
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendMessage(
        context: android.content.Context,
        message: String,
        imageUri: Uri?,
        role: String,
        businessContext: String?,
        history: List<Pair<String, Boolean>> = emptyList()
    ): AiAssistantResult = withContext(Dispatchers.IO) {
        try {
            // The user's own session token. This is what lets the Edge Function verify who's
            // actually calling and enforce the daily message limit server-side - the anon key
            // alone (used previously) can't identify a caller at all.
            val accessToken = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()
                ?: return@withContext AiAssistantResult.Failure("Not logged in")

            val imageBase64 = imageUri?.let { uri ->
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val maxDim = 1024
                    val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0f)
                    val resized = if (scale < 1.0f) {
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                    } else bitmap
                    val stream = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                } else null
            }

            val historyItems = history.map { ChatHistoryItem(it.first, it.second) }
            val requestPayload = AiAssistantRequest(message, imageBase64, role, businessContext, historyItems)
            val bodyText = json.encodeToString(requestPayload)

            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = responseStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode == 429) {
                val parsed = try { json.decodeFromString<AiAssistantResponse>(responseText) } catch (_: Exception) { null }
                return@withContext AiAssistantResult.LimitReached(parsed?.limit)
            }

            if (responseCode == 401) {
                return@withContext AiAssistantResult.Failure("Your session expired. Please log in again.")
            }

            if (responseCode !in 200..299) {
                return@withContext AiAssistantResult.Failure("Server error ($responseCode): $responseText")
            }

            val parsed = json.decodeFromString<AiAssistantResponse>(responseText)
            if (parsed.reply != null) {
                AiAssistantResult.Success(parsed.reply)
            } else {
                AiAssistantResult.Failure(parsed.error ?: "No response received.")
            }
        } catch (e: Exception) {
            AiAssistantResult.Failure(e.message ?: "Couldn't reach the assistant. Check your connection.")
        }
    }
}