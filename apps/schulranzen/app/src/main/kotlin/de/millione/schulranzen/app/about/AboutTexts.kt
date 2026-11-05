package de.millione.schulranzen.app.about

/** Help and notices. Plain data, so the wording lives in one place. */
internal object AboutTexts {
    data class Faq(val question: String, val answer: String)

    /** Calculators are simplified on purpose. */
    const val SHK_NOTICE =
        "Die SHK-Rechner, Tabellen und Formeln sind Lernhilfen mit vereinfachten Werten. " +
            "Für echte Anlagen gelten die aktuellen Normen, die Herstellerangaben und das Urteil einer Fachkraft."

    const val AI_NOTICE =
        "Die Erklärhilfe ist eine KI (Claude von Anthropic). Sie kann sich irren, also prüf wichtige Aussagen nach. " +
            "Schreib keine persönlichen Angaben in deine Fragen."

    val tabletFaq = listOf(
        Faq("Wie radiere ich?", "Halt die Seitentaste des Stifts beim Schreiben gedrückt. Unter „Mehr“ im Heft kannst du das ändern."),
        Faq("Die Seitentaste öffnet Air Command statt des Werkzeugrings", "Das macht Samsung, und Apps können es nicht abschalten. Im Heft unter „Mehr“ findest du den Weg zur Einstellung: „Air Command mit Stifttaste öffnen“ ausschalten."),
        Faq("Wie lege ich ein Buch neben das Heft?", "Im Heft: „Mehr“ und dann „Buch daneben öffnen“. Zieh am Trenner, um die Größe zu ändern."),
        Faq("Wie rechne ich im Heft?", "Kreis die Rechnung mit dem Stift ein. Das braucht einmalig das Handschrift-Modell."),
    )

    val phoneFaq = listOf(
        Faq("Warum kann ich auf dem Handy nicht schreiben?", "Schreiben und Rechnen mit dem Stift sind fürs Tablet gemacht. Auf dem Handy liest du, scannst und lernst."),
        Faq("Wie kommen Scans vom Handy ins Heft?", "Unter „Scannen“ wählst du das Heft oder legst ein neues an. Aufs Tablet kommen sie über „Sichern“."),
    )

    val commonFaq = listOf(
        Faq("Wo liegen meine Daten?", "Auf diesem Gerät. Die App hat kein Konto und keine eigene Cloud."),
        Faq("Wie komme ich auf ein neues Gerät?", "Unter „Sichern“ ein Backup anlegen, auf das neue Gerät bringen und dort wiederherstellen. Die automatische Android-Sicherung nimmt deine Hefte bewusst nicht mit."),
        Faq("Was passiert, wenn ich die App lösche?", "Dann sind alle Hefte und Bücher auf dem Gerät weg. Sichere vorher, wenn du sie behalten willst."),
        Faq("Ich habe das Backup-Passwort vergessen", "Ein verschlüsseltes Backup lässt sich ohne Passwort nicht öffnen. Auch wir können es nicht wiederherstellen."),
        Faq("Wozu lädt die App ein Modell herunter?", "Die Handschrifterkennung braucht einmalig ein Sprachmodell von Google, etwa 20 MB. Danach läuft sie ohne Internet."),
        Faq("Brauche ich die Erklärhilfe?", "Nein. Sie ist nur aktiv, wenn du einen eigenen API-Schlüssel von Anthropic einträgst. Alles andere geht ohne."),
        Faq("Wie bekomme ich Updates?", "Die App prüft höchstens einmal am Tag und meldet sich, wenn es eine neue Version gibt. Hier unter „Nach Updates suchen“ geht es auch von Hand. Installiert wird nur, wenn du zustimmst. Deine Hefte und Lerndaten bleiben dabei erhalten."),
        Faq("Die App ist abgestürzt. Was nun?", "Beim nächsten Start bietet sie dir an, einen Bericht zu teilen. Gesendet wird nichts von selbst. Du findest ihn auch hier unter „Bericht teilen“."),
    )
}

/** One run of a line in the privacy text, parsed without Android so it can be tested. */
internal data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

internal sealed interface Block {
    data class Title(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Paragraph(val spans: List<Span>) : Block
    data class Bullet(val spans: List<Span>) : Block
}

/** The small part of Markdown the privacy draft uses: headings, bullets, **bold** and `placeholders`. */
internal object MiniMarkdown {
    private val INLINE = Regex("""\*\*(.+?)\*\*|`(.+?)`""")

    fun inline(line: String): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in INLINE.findAll(line)) {
            if (m.range.first > at) out += Span(line.substring(at, m.range.first))
            val bold = m.groups[1]?.value
            out += if (bold != null) Span(bold, bold = true) else Span(m.groupValues[2], code = true)
            at = m.range.last + 1
        }
        if (at < line.length) out += Span(line.substring(at))
        return out
    }

    fun parse(text: String): List<Block> = text.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            line.startsWith("### ") -> Block.Heading(line.removePrefix("### "))
            line.startsWith("## ") -> Block.Title(line.removePrefix("## "))
            line.startsWith("- ") -> Block.Bullet(inline(line.removePrefix("- ")))
            else -> Block.Paragraph(inline(line))
        }
    }
}
