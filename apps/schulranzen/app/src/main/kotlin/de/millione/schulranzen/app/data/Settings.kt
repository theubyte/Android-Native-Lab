package de.millione.schulranzen.app.data

import android.content.Context
import de.millione.schulranzen.design.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small per-device preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(ThemeMode.entries.getOrElse(prefs.getInt("theme", 0)) { ThemeMode.SYSTEM })
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putInt("theme", mode.ordinal).apply()
        _theme.value = mode
    }

    var buttonErases: Boolean
        get() = prefs.getBoolean("buttonErases", true)
        set(v) = prefs.edit().putBoolean("buttonErases", v).apply()

    var fingerDraws: Boolean
        get() = prefs.getBoolean("fingerDraws", false)
        set(v) = prefs.edit().putBoolean("fingerDraws", v).apply()

    var penColor: Int
        get() = prefs.getInt("penColor", 0xFF1C1B19.toInt())
        set(v) = prefs.edit().putInt("penColor", v).apply()

    var penSize: Float
        get() = prefs.getFloat("penSize", 3f)
        set(v) = prefs.edit().putFloat("penSize", v).apply()

    var markerColor: Int
        get() = prefs.getInt("markerColor", 0xFFF2D04B.toInt())
        set(v) = prefs.edit().putInt("markerColor", v).apply()

    var markerSize: Float
        get() = prefs.getFloat("markerSize", 18f)
        set(v) = prefs.edit().putFloat("markerSize", v).apply()
}
