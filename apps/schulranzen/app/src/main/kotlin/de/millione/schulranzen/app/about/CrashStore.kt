package de.millione.schulranzen.app.about

import java.io.File

/** The build and device a report came from; no user data. */
internal data class ReportHeader(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val device: String,
    val android: String,
)

/** Report text. Plain Kotlin, so it is tested without a device. */
internal object CrashFormat {
    /** A runaway recursion can produce megabytes; the top of the trace is what matters. */
    const val MAX_CHARS = 200_000

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val API_KEY = Regex("""sk-[A-Za-z0-9_-]{8,}""")
    private val BEARER = Regex("""(?i)bearer\s+\S+""")

    /** Exception messages may quote a server login or key. The user sees the rest before sharing. */
    fun scrub(text: String): String = text
        .replace(API_KEY, "[Schlüssel entfernt]")
        .replace(BEARER, "[Anmeldung entfernt]")
        .replace(EMAIL, "[E-Mail entfernt]")

    fun report(header: ReportHeader, kind: String, whenText: String, detail: String): String {
        val body = scrub(detail)
        val cut = if (body.length > MAX_CHARS) body.take(MAX_CHARS) + "\n[gekürzt]" else body
        return buildString {
            append("Schulranzen ").append(header.versionName)
            append(" (").append(header.versionCode).append(", ").append(header.buildType).append(")\n")
            append("Gerät: ").append(header.device).append('\n')
            append("Android: ").append(header.android).append('\n')
            append("Art: ").append(kind).append('\n')
            append("Zeit: ").append(whenText).append("\n\n")
            append(cut).append('\n')
        }
    }
}

/**
 * Reports as files, newest last. A report is "open" until the user answered
 * the offer at the next start; it stays readable and shareable afterwards.
 * Lives in noBackupFilesDir, so it never travels inside a user's backup.
 */
internal class CrashStore(private val dir: File, private val keep: Int = 5) {
    fun write(text: String, nowMillis: Long): File {
        dir.mkdirs()
        val target = File(dir, "$PREFIX$nowMillis$OPEN")
        // Atomic, so a process dying mid-write never leaves half a report.
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        prune()
        return target
    }

    private fun reports(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith(PREFIX) && !it.name.endsWith(".tmp") }
            .sortedBy { stamp(it) }

    private fun stamp(f: File): Long = f.name.removePrefix(PREFIX).substringBefore('.').toLongOrNull() ?: 0L

    private fun prune() { reports().dropLast(keep).forEach { it.delete() } }

    /** The newest report the user has not answered yet. */
    fun open(): File? = reports().lastOrNull { it.name.endsWith(OPEN) }

    fun latest(): File? = reports().lastOrNull()

    fun count(): Int = reports().size

    /** The offer was answered: no more asking, but the reports stay in "Über". */
    fun markAllSeen() {
        for (f in reports()) if (f.name.endsWith(OPEN)) f.renameTo(File(dir, f.name.removeSuffix(OPEN) + SEEN))
    }

    fun deleteAll() { reports().forEach { it.delete() } }

    private companion object {
        const val PREFIX = "crash-"
        const val OPEN = ".open.txt"
        const val SEEN = ".seen.txt"
    }
}
