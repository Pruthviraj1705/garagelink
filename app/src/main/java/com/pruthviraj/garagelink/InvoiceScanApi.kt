package com.pruthviraj.garagelink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ScannedInvoiceItem(
    val name: String,
    val quantity: Int = 1,
    val price: Double? = null
)

@Serializable
data class ScannedInvoiceResponse(
    val items: List<ScannedInvoiceItem> = emptyList(),
    val error: String? = null,
    val limit: Int? = null
)

sealed class ScanInvoiceResult {
    data class Success(val items: List<ScannedInvoiceItem>) : ScanInvoiceResult()
    data class LimitReached(val limit: Int?) : ScanInvoiceResult()
    data class Failure(val message: String) : ScanInvoiceResult()
}

object InvoiceScanApi {
    private const val ENDPOINT = "https://eceuvkvairdbhddyebmw.supabase.co/functions/v1/scan-invoice"
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVjZXV2a3ZhaXJkYmhkZHllYm13Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODU2OTA4MDEsImV4cCI6MjEwMTI2NjgwMX0.WYNjKG4pS4AJaNV9d1oOrYrqD3Fv708jzypH5-hdtj4"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scanInvoice(photoFile: File, garageId: String): ScanInvoiceResult = withContext(Dispatchers.IO) {
        try {
            val accessToken = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()
                ?: return@withContext ScanInvoiceResult.Failure("Not logged in")

            // Load and resize camera image (max 1024px) to reduce payload size from ~11MB to ~150KB
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                ?: return@withContext ScanInvoiceResult.Failure("Could not load captured image")

            val maxDim = 1024
            val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0f)
            val resized = if (scale < 1.0f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val imageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            // garageId now goes in the body - the Edge Function passes it straight through to
            // check_and_log_invoice_scan, whose own auth.uid()-based ownership check is what
            // actually protects this now.
            val requestBody = """{"garageId":"$garageId","imageBase64":"$imageBase64"}"""

            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = responseStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode == 429) {
                val parsed = try { json.decodeFromString<ScannedInvoiceResponse>(responseText) } catch (_: Exception) { null }
                return@withContext ScanInvoiceResult.LimitReached(parsed?.limit)
            }

            if (responseCode == 401) {
                return@withContext ScanInvoiceResult.Failure("Your session expired. Please log in again.")
            }

            if (responseCode !in 200..299) {
                return@withContext ScanInvoiceResult.Failure("Server error ($responseCode): $responseText")
            }

            val wrapper = json.decodeFromString<ScannedInvoiceResponse>(responseText)
            ScanInvoiceResult.Success(wrapper.items)
        } catch (e: Exception) {
            ScanInvoiceResult.Failure(e.message ?: "Scan failed. Check your connection and try again.")
        }
    }
}