package de.millione.schulranzen.rechnen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One finished calculation in the calculator's tape. Keys are kept so it can be edited again. */
@Serializable
data class HistoryEntry(val display: List<String>, val engine: List<String>, val value: Double)

/** What the calculator remembers between openings: where it sat, how big, mode, last results. */
@Serializable
data class CalculatorPrefs(
    /** Position and size in dp; negative x means "not placed yet". */
    val x: Float = -1f,
    val y: Float = -1f,
    val width: Float = 380f,
    val height: Float = 600f,
    val radians: Boolean = false,
    val ans: Double = 0.0,
    val history: List<HistoryEntry> = emptyList(),
)

/**
 * The feature's own storage under filesDir/rechnen (not the app database).
 * Small JSON files, loaded once, written atomically off the main thread.
 */
class RechnenStore private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "rechnen")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _calculator = MutableStateFlow<CalculatorPrefs?>(null)
    /** Null until loaded from disk (a few milliseconds after first use). */
    val calculator: StateFlow<CalculatorPrefs?> = _calculator.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of favourite formulas. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    init {
        scope.launch {
            _calculator.value = read("calculator.json") { json.decodeFromString(CalculatorPrefs.serializer(), it) } ?: CalculatorPrefs()
            _favorites.value = read("favorites.json") { json.decodeFromString<List<String>>(it).toSet() } ?: emptySet()
        }
    }

    fun updateCalculator(change: (CalculatorPrefs) -> CalculatorPrefs) {
        val next = change(_calculator.value ?: CalculatorPrefs())
        _calculator.value = next
        scope.launch { write("calculator.json", json.encodeToString(CalculatorPrefs.serializer(), next)) }
    }

    fun toggleFavorite(formulaId: String) {
        _favorites.update { if (formulaId in it) it - formulaId else it + formulaId }
        val snapshot = _favorites.value.sorted()
        scope.launch { write("favorites.json", json.encodeToString(snapshot)) }
    }

    private fun <T> read(name: String, parse: (String) -> T): T? = try {
        val f = File(dir, name)
        if (f.exists()) parse(f.readText()) else null
    } catch (e: Exception) {
        null // A damaged file only costs preferences; start fresh.
    }

    private suspend fun write(name: String, text: String) = writeLock.withLock {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile private var instance: RechnenStore? = null

        /** One store per process so the calculator and the SHK screen share state. */
        fun get(context: Context): RechnenStore =
            instance ?: synchronized(this) { instance ?: RechnenStore(context).also { instance = it } }
    }
}
