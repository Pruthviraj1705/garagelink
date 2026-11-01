package com.pruthviraj.garagelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pruthviraj.garagelink.ui.theme.GarageLinkTheme
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRow(val id: String, val role: String)

@Serializable
data class GarageRow(val id: String, val shop_name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.google.android.gms.ads.MobileAds.initialize(this) {}
        ThemePreference.init(this)
        setContent {
            val systemDark = isSystemInDarkTheme()
            GarageLinkTheme(darkTheme = ThemePreference.isDarkMode ?: systemDark) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val auth = SupabaseClientProvider.client.auth
    val postgrest = SupabaseClientProvider.client.postgrest
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("session_cache", android.content.Context.MODE_PRIVATE) }

    val sessionStatus by auth.sessionStatus.collectAsState()
    var profileRole by remember { mutableStateOf<String?>(null) }
    var garageInfo by remember { mutableStateOf<GarageRow?>(null) }
    var checkedProfile by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(sessionStatus, refreshTrigger) {
        val status = sessionStatus
        if (status is SessionStatus.Authenticated) {
            val userId = status.session.user?.id
            if (userId != null) {
                try {
                    val fetchedRole = postgrest.from("profiles")
                        .select(Columns.list("id", "role")) {
                            filter { eq("id", userId) }
                        }
                        .decodeSingleOrNull<ProfileRow>()?.role
                    profileRole = fetchedRole
                    prefs.edit().putString("cached_role_$userId", fetchedRole).apply()

                    if (fetchedRole == "garage_owner") {
                        val fetchedGarage = postgrest.from("garages")
                            .select(Columns.list("id", "shop_name")) {
                                filter { eq("owner_id", userId) }
                            }
                            .decodeSingleOrNull<GarageRow>()
                        garageInfo = fetchedGarage
                        if (fetchedGarage != null) {
                            prefs.edit()
                                .putString("cached_garage_id_$userId", fetchedGarage.id)
                                .putString("cached_garage_name_$userId", fetchedGarage.shop_name)
                                .apply()
                        }
                    }
                } catch (e: Exception) {
                    val cachedRole = prefs.getString("cached_role_$userId", null)
                    profileRole = cachedRole
                    if (cachedRole == "garage_owner") {
                        val cachedGarageId = prefs.getString("cached_garage_id_$userId", null)
                        val cachedGarageName = prefs.getString("cached_garage_name_$userId", null)
                        garageInfo = if (cachedGarageId != null && cachedGarageName != null) {
                            GarageRow(id = cachedGarageId, shop_name = cachedGarageName)
                        } else null
                    }
                }
            }
            checkedProfile = true
        } else {
            checkedProfile = false
            profileRole = null
            garageInfo = null
        }
    }

    when (val status = sessionStatus) {
        is SessionStatus.Initializing -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> {
            LoginScreen(onLoginSuccess = {})
        }
        is SessionStatus.Authenticated -> {
            val userId = status.session.user?.id ?: ""
            PushSetup(userId)
            if (!checkedProfile) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (profileRole == null) {
                RoleSelectionScreen(
                    userId = userId,
                    phoneNumber = status.session.user?.phone ?: "",
                    onRoleSelected = { refreshTrigger++ }
                )
            } else if (profileRole == "garage_owner" && garageInfo == null) {
                GarageSetupScreen(
                    userId = userId,
                    onGarageCreated = { refreshTrigger++ }
                )
            } else {
                when (profileRole) {
                    "garage_owner" -> GarageOwnerHomeScreen(
                        garageId = garageInfo?.id ?: "",
                        shopName = garageInfo?.shop_name ?: "",
                        onLogout = {}
                    )
                    "driver" -> DriverHomeScreen(onLogout = {})
                    else -> Text("Unknown role: $profileRole")
                }
            }
        }
    }
}
