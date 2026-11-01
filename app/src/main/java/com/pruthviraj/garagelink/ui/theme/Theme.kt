package com.pruthviraj.garagelink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = GarageBlue40,
    onPrimary = Color.White,
    primaryContainer = GarageBlueContainer,
    onPrimaryContainer = GarageBlue40,
    secondary = GarageAmber40,
    onSecondary = Color.White,
    secondaryContainer = GarageAmberContainer,
    onSecondaryContainer = GarageAmber40,
    background = GarageNeutral95,
    onBackground = GarageNeutral10,
    surface = Color.White,
    onSurface = GarageNeutral10,
    error = GarageError,
    onError = Color.White,
    errorContainer = GarageErrorContainer,
    onErrorContainer = GarageError
)

private val DarkColors = darkColorScheme(
    primary = GarageBlue80,
    onPrimary = GarageBlue40,
    primaryContainer = GarageBlue40,
    onPrimaryContainer = GarageBlue80,
    secondary = GarageAmber80,
    onSecondary = GarageAmber40,
    secondaryContainer = GarageAmber40,
    onSecondaryContainer = GarageAmber80,
    background = GarageNeutral10,
    onBackground = GarageNeutral90,
    surface = Color(0xFF121316),
    onSurface = GarageNeutral90,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun GarageLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so your brand palette above always shows, instead of
    // Android 12+ pulling colors from the user's wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}