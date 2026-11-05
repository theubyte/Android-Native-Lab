package de.millione.schulranzen.core.notebook

import java.text.Collator
import java.util.Locale

/** How the library lists notebooks. Stored by name. */
enum class NotebookSort(val label: String) {
    RECENT("Zuletzt benutzt"),
    TITLE("Name"),
    CREATED("Neu angelegt");

    fun next(): NotebookSort = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: RECENT
    }
}

object NotebookNames {
    private val collator: Collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.SECONDARY }
    private val parts = Regex("\\d+|\\D+")

    /** German order (Ä beside A, case ignored), numbers by value: "Heft 2" before "Heft 10". */
    val titleOrder: Comparator<String> = Comparator { a, b ->
        val pa = parts.findAll(a.trim()).map { it.value }.toList()
        val pb = parts.findAll(b.trim()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val nx = x.toBigIntegerOrNull(); val ny = y.toBigIntegerOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else synchronized(collator) { collator.compare(x, y) }
            if (c != 0) return@Comparator c
        }
        pa.size - pb.size
    }

    /** "Mathe" to "Mathe (Kopie)", then "Mathe (Kopie 2)": a free name among [existing]. */
    fun copyTitle(title: String, existing: Collection<String>): String {
        val base = title.trim().replace(Regex("\\s*\\(Kopie( \\d+)?\\)$"), "").ifEmpty { "Heft" }
        val taken = existing.mapTo(HashSet()) { it.trim() }
        var candidate = "$base (Kopie)"
        var n = 2
        while (candidate in taken) candidate = "$base (Kopie ${n++})"
        return candidate
    }

    /** A title as a file name that every system accepts. */
    fun fileName(title: String, extension: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).trim()
        return clean.ifEmpty { "Heft" } + "." + extension
    }
}

/** Which pictures nobody needs any more. */
object ImageRefs {
    /**
     * The [candidates] (file names) that occur in none of [references]
     * (object files and background names of everything that remains). Only
     * pictures of what was just deleted are candidates, so a picture that
     * another feature put into the folder is never touched.
     */
    fun orphans(candidates: Set<String>, references: Sequence<String>): Set<String> {
        val left = candidates.toMutableSet()
        for (text in references) {
            if (left.isEmpty()) break
            left.removeAll { text.contains(it) }
        }
        return left
    }
}
