package de.millione.schulranzen.core.notebook

import java.io.FileNotFoundException
import java.io.IOException

/** An error whose message is already written for the user. */
class UserError(message: String) : Exception(message)

/** Turns exceptions into sentences for the user, instead of "EIO" or nothing. */
object UserMessages {
    /** [what] names the action: "Speichern", "PDF-Export", … */
    fun of(t: Throwable, what: String): String = when {
        t is UserError -> t.message.orEmpty()
        t is OutOfMemoryError -> "$what: zu wenig Arbeitsspeicher. Schließe andere Apps und versuch es noch einmal."
        noSpace(t) -> "$what: Der Speicher des Tablets ist voll. Mach Platz und versuch es noch einmal."
        t is SecurityException -> "$what: Kein Zugriff auf die Datei."
        t is FileNotFoundException -> "$what: Die Datei ist nicht mehr da."
        else -> "$what hat nicht geklappt. Versuch es noch einmal."
    }

    /** Opening a PDF: passwords and broken files are the usual reasons. */
    fun ofPdf(t: Throwable): String = when {
        t is UserError -> t.message.orEmpty()
        t is OutOfMemoryError || noSpace(t) -> of(t, "PDF")
        t is SecurityException || chain(t).any { it.message?.contains("password", ignoreCase = true) == true } ->
            "Das PDF ist mit einem Passwort geschützt. Speichere es zuerst ohne Passwort."
        t is FileNotFoundException -> "Die PDF-Datei ist nicht mehr da."
        t is IOException || t is IllegalArgumentException -> "Die Datei ist kein gültiges PDF oder sie ist beschädigt."
        else -> "Das PDF konnte nicht gelesen werden."
    }

    private fun chain(t: Throwable) = generateSequence(t) { it.cause }.take(8)

    private fun noSpace(t: Throwable) = chain(t).any {
        val m = it.message.orEmpty()
        m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }
}
