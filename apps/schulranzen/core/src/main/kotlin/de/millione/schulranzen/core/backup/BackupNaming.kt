package de.millione.schulranzen.core.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How often the automatic backup runs. */
enum class AutoInterval(val days: Int, val label: String) {
    OFF(0, "Aus"),
    DAILY(1, "Täglich"),
    WEEKLY(7, "Wöchentlich");

    companion object {
        fun parse(s: String?): AutoInterval = entries.firstOrNull { it.name == s } ?: OFF
    }
}

/**
 * File names of backups and which old ones to drop. Automatic backups get
 * their own prefix, so keeping "the last N" never touches a backup the user
 * saved by hand into the same folder. The time stamp in the name sorts
 * chronologically, so retention does not depend on file system times.
 */
object BackupNaming {
    const val MANUAL_PREFIX = "Schulranzen-Backup-"
    const val AUTO_PREFIX = "Schulranzen-Auto-"
    const val PLAIN_EXT = ".zip"
    /** Encrypted container ([BackupCrypto]). */
    const val ENCRYPTED_EXT = ".srbk"
    const val MIN_KEEP = 1
    const val MAX_KEEP = 50

    private val stamp = Regex("""\d{4}-\d{2}-\d{2}-\d{4,6}""")

    fun name(time: Long, encrypted: Boolean, auto: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat(if (auto) "yyyy-MM-dd-HHmmss" else "yyyy-MM-dd-HHmm", Locale.ROOT).apply { timeZone = zone }
        return (if (auto) AUTO_PREFIX else MANUAL_PREFIX) + f.format(Date(time)) + if (encrypted) ENCRYPTED_EXT else PLAIN_EXT
    }

    /** True for a name made by [name] with auto = true. */
    fun isAutoBackup(name: String): Boolean {
        if (!name.startsWith(AUTO_PREFIX)) return false
        val rest = name.removePrefix(AUTO_PREFIX)
        val core = rest.removeSuffix(PLAIN_EXT).removeSuffix(ENCRYPTED_EXT)
        return core != rest && stamp.matches(core)
    }

    /**
     * The automatic backups among [names] that go when only the newest
     * [keep] stay. Other files are never returned.
     */
    fun toDelete(names: Collection<String>, keep: Int): List<String> {
        val k = keep.coerceIn(MIN_KEEP, MAX_KEEP)
        return names.filter(::isAutoBackup).distinct().sortedDescending().drop(k)
    }
}
