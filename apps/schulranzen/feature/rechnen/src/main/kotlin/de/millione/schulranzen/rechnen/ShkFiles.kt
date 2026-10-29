package de.millione.schulranzen.rechnen

import android.content.Context
import de.millione.schulranzen.core.shk.AufmassProject
import de.millione.schulranzen.core.shk.MaterialSettings
import de.millione.schulranzen.core.shk.Schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val shkJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Writes [text] to [target] via a temporary file and rename, so a crash never leaves half a file. */
internal fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) error("Datei konnte nicht gespeichert werden")
    }
}

/**
 * One JSON file per document in [dir], kept in memory as a sorted list.
 * Changes show at once; writing is debounced per document and runs on IO.
 */
class JsonDocs<T>(
    private val dir: File,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val sortKey: (T) -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val pending = HashMap<String, Job>()

    private val _items = MutableStateFlow<List<T>?>(null)
    /** Null until loaded; newest first. */
    val items: StateFlow<List<T>?> = _items.asStateFlow()

    init {
        scope.launch {
            val loaded = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
                runCatching { shkJson.decodeFromString(serializer, f.readText()) }.getOrNull() // damaged files are skipped
            }
            _items.value = loaded.sortedByDescending(sortKey)
        }
    }

    fun get(id: String?): T? = _items.value?.firstOrNull { idOf(it) == id }

    fun put(item: T) {
        val id = idOf(item)
        _items.update { list -> (listOf(item) + list.orEmpty().filter { idOf(it) != id }).sortedByDescending(sortKey) }
        synchronized(pending) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch {
                delay(350)
                lock.withLock { runCatching { writeAtomic(File(dir, "$id.json"), shkJson.encodeToString(serializer, item)) } }
            }
        }
    }

    fun delete(id: String) {
        _items.update { list -> list.orEmpty().filter { idOf(it) != id } }
        synchronized(pending) { pending.remove(id)?.cancel() }
        scope.launch { lock.withLock { File(dir, "$id.json").delete() } }
    }
}

/**
 * The SHK documents: Aufmaß projects under filesDir/rechnen/aufmass, schemata
 * under filesDir/rechnen/schema, material list settings in filesDir/rechnen/material.json.
 */
class ShkFiles private constructor(context: Context) {
    private val base = File(context.applicationContext.filesDir, "rechnen")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val aufmass = JsonDocs(File(base, "aufmass"), AufmassProject.serializer(), { it.id }, { it.updatedAt })
    val schemas = JsonDocs(File(base, "schema"), Schema.serializer(), { it.id }, { it.updatedAt })

    private val _material = MutableStateFlow(MaterialSettings())
    val material: StateFlow<MaterialSettings> = _material.asStateFlow()

    init {
        scope.launch {
            val f = File(base, "material.json")
            if (f.exists()) runCatching { _material.value = shkJson.decodeFromString(MaterialSettings.serializer(), f.readText()) }
        }
    }

    fun updateMaterial(change: (MaterialSettings) -> MaterialSettings) {
        val next = change(_material.value)
        _material.value = next
        scope.launch { runCatching { writeAtomic(File(base, "material.json"), shkJson.encodeToString(MaterialSettings.serializer(), next)) } }
    }

    companion object {
        @Volatile private var instance: ShkFiles? = null
        fun get(context: Context): ShkFiles =
            instance ?: synchronized(this) { instance ?: ShkFiles(context).also { instance = it } }

        fun newId(): String = UUID.randomUUID().toString().substring(0, 13)
    }
}
