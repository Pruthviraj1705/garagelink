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
data class ScannedSaleItem(
    val name: String,
    val quantity: Int = 1,
    val price: Double? = null
)

@Serializable
data class ScannedSaleResponse(
    val items: List<ScannedSaleItem> = emptyList(),
    val error: String? = null,
    val limit: Int? = null
)

sealed class ScanSaleResult {
    data class Success(val items: List<ScannedSaleItem>) : ScanSaleResult()
    data class LimitReached(val limit: Int?) : ScanSaleResult()
    data class Failure(val message: String) : ScanSaleResult()
}

object SalesBillScanApi {
    private const val ENDPOINT = "${SupabaseConfig.URL}/functions/v1/scan-sale-bill"
    private const val SUPABASE_ANON_KEY = SupabaseConfig.ANON_KEY
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scanSaleBill(photoFile: File, garageId: String): ScanSaleResult = withContext(Dispatchers.IO) {
        try {
            val accessToken = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()
                ?: return@withContext ScanSaleResult.Failure("Not logged in")

            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                ?: return@withContext ScanSaleResult.Failure("Could not load captured image")

            val maxDim = 1024
            val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1.0f)
            val resized = if (scale < 1.0f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val imageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

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
                val parsed = try { json.decodeFromString<ScannedSaleResponse>(responseText) } catch (_: Exception) { null }
                return@withContext ScanSaleResult.LimitReached(parsed?.limit)
            }

            if (responseCode == 401) {
                return@withContext ScanSaleResult.Failure("Your session expired. Please log in again.")
            }

            if (responseCode !in 200..299) {
                return@withContext ScanSaleResult.Failure("Server error ($responseCode): $responseText")
            }

            val wrapper = json.decodeFromString<ScannedSaleResponse>(responseText)
            ScanSaleResult.Success(wrapper.items)
        } catch (e: Exception) {
            ScanSaleResult.Failure(e.message ?: "Scan failed. Check your connection and try again.")
        }
    }
}