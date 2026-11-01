package com.pruthviraj.garagelink

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

object PushNotifications {
    const val CHANNEL_ID = "garagelink_default"
    private const val TAG = "Push"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GarageLink alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Low-stock, expiry and other GarageLink alerts" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /** Saves this device's token against the signed-in user. Safe to call repeatedly. */
    suspend fun saveToken(token: String) {
        try {
            if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return
            SupabaseClientProvider.client.postgrest.rpc(
                "register_device_token",
                buildJsonObject {
                    put("p_token", token)
                    put("p_platform", "android")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Token registration failed", e)
        }
    }

    suspend fun registerCurrentToken() {
        val token = fetchToken() ?: return
        saveToken(token)
    }

    /** Call BEFORE auth.signOut() so a signed-out phone stops receiving this user's pushes. */
    suspend fun unregisterCurrentToken() {
        try {
            val token = fetchToken() ?: return
            SupabaseClientProvider.client.postgrest.rpc(
                "unregister_device_token",
                buildJsonObject { put("p_token", token) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Token unregister failed", e)
        }
    }

    fun saveTokenAsync(token: String) {
        scope.launch { saveToken(token) }
    }
}

class GarageLinkMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushNotifications.saveTokenAsync(token)
    }

    // Called only while the app is in the foreground (in the background, the system
    // shows the notification itself using the default channel + icon from the manifest).
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        PushNotifications.createChannel(this)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, PushNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        NotificationManagerCompat.from(this)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}

/** Put once inside the Authenticated branch of AppRoot: PushSetup(userId) */
@Composable
fun PushSetup(userId: String) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result not needed; the token is registered either way */ }

    LaunchedEffect(userId) {
        if (userId.isBlank()) return@LaunchedEffect
        PushNotifications.createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PushNotifications.registerCurrentToken()
    }
}
