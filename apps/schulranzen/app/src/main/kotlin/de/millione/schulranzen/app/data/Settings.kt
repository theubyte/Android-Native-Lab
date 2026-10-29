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

    /** Pen held still at the end of a stroke straightens it into a shape. */
    var shapes: Boolean
        get() = prefs.getBoolean("shapes", true)
        set(v) = prefs.edit().putBoolean("shapes", v).apply()

    /** A loop drawn around a calculation solves it (needs the handwriting model). */
    var circleSolve: Boolean
        get() = prefs.getBoolean("circleSolve", true)
        set(v) = prefs.edit().putBoolean("circleSolve", v).apply()

    /** All existing pages went through the handwriting index once. */
    var handwritingIndexedAll: Boolean
        get() = prefs.getBoolean("hwIndexedAll", false)
        set(v) = prefs.edit().putBoolean("hwIndexedAll", v).apply()

    /** The Air Command hint was read once (Samsung). */
    var airHintSeen: Boolean
        get() = prefs.getBoolean("airHintSeen", false)
        set(v) = prefs.edit().putBoolean("airHintSeen", v).apply()

    var markerSize: Float
        get() = prefs.getFloat("markerSize", 18f)
        set(v) = prefs.edit().putFloat("markerSize", v).apply()
}
