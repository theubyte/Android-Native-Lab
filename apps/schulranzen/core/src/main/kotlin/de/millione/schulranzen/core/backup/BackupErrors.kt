package de.millione.schulranzen.core.backup

import java.io.FileNotFoundException
import java.io.IOException

/** Turns any failure into one German sentence for the screen; never the raw English text of the system. */
object BackupErrors {
    private val technical = Regex("""\s*\[[^\]]*]$""")

    fun isDiskFull(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        return m.contains("ENOSPC") || m.contains("No space", ignoreCase = true)
    }

    fun message(e: Throwable): String = when {
        e is WrongPasswordException -> "Falsches Passwort. Bitte noch einmal versuchen."
        e is BackupException -> e.message ?: "Fehler beim Backup."
        // The system's own words in brackets help the connection test, not here.
        e is SyncException -> (e.message ?: "Serverfehler.").replace(technical, "")
        e is SyncConflictException -> "Ein anderes Gerät gleicht gerade ab. Bitte gleich noch einmal versuchen."
        e is SecurityException -> "Kein Zugriff auf die Datei oder den Ordner. Bitte neu auswählen."
        isDiskFull(e) -> "Kein Speicherplatz mehr frei. Bitte Platz schaffen und erneut versuchen. An den vorhandenen Daten hat sich nichts geändert."
        e is FileNotFoundException -> "Datei oder Ordner nicht gefunden. Bitte neu auswählen."
        e is IOException -> "Die Datei konnte nicht gelesen oder geschrieben werden. Bitte erneut versuchen."
        e is OutOfMemoryError -> "Zu wenig Arbeitsspeicher. Bitte andere Apps schließen und erneut versuchen."
        else -> "Unerwarteter Fehler (${e.javaClass.simpleName}). Bitte erneut versuchen."
    }
}
