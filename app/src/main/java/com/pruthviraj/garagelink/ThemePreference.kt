package com.pruthviraj.garagelink

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// null = follow system setting, true = force dark, false = force light.
// A simple top-level holder (same pattern as SupabaseClientProvider) so the
// theme can be read at MainActivity's top level and changed from deep inside
// ProfileScreen without threading a callback through every screen in between.
object ThemePreference {
    var isDarkMode by mutableStateOf<Boolean?>(null)
        private set

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "dark_mode_override"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isDarkMode = when (prefs.getInt(KEY_DARK_MODE, -1)) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    fun set(context: Context, value: Boolean) {
        isDarkMode = value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DARK_MODE, if (value) 1 else 0).apply()
    }
}