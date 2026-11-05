package de.millione.schulranzen.study

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.CardCsv
import de.millione.schulranzen.core.study.CsvCard
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.study.data.DeckCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A deck larger than this is no card file. */
private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

private suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
        if (bytes.size > MAX_IMPORT_BYTES) throw IOException("zu groß")
        decode(bytes)
    } ?: throw IOException("nicht lesbar")
}

private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** UTF-8 first; older German Excel files are Windows-1252, which UTF-8 rejects. */
internal fun decode(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    String(bytes, charset("windows-1252"))
}

/**
 * Import and export of one deck as a text file, compatible with the usual
 * tab-separated card format. Only text travels: the learning state stays here,
 * imported cards start new, and a card already in the deck is left alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportExportContent(repo: StudyRepository, deck: DeckCounts, onClose: () -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<CsvCard>?>(null) }
    var note by remember { mutableStateOf("") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = null
            try {
                val parsed = withContext(Dispatchers.Default) { CardCsv.parse(readText(context, uri)) }
                val cards = CardCsv.dropTitleRow(parsed.cards)
                if (cards.isEmpty()) {
                    message = "In der Datei wurden keine Karten gefunden. Erwartet wird eine Karte je Zeile: Frage, Tabulator, Antwort."
                } else {
                    pending = cards
                    note = listOfNotNull(
                        parsed.skipped.takeIf { it > 0 }?.let { "$it Zeilen übersprungen" },
                        parsed.mediaDropped.takeIf { it > 0 }?.let { "$it Karten mit Bild oder Ton, nur der Text kommt mit" },
                    ).joinToString(" · ")
                }
            } catch (_: IOException) {
                message = "Die Datei ließ sich nicht lesen."
            }
        }
    }
    val saveTsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri != null) scope.launch { message = writeDeck(context, repo, deck, uri, CardCsv::write) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { message = writeDeck(context, repo, deck, uri, CardCsv::writeCsv) }
    }

    Text("Importieren und exportieren", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(6.dp))
    Text("Stapel „${deck.name}“ · ${deck.total} Karten", style = Ranzen.type.mono, color = c.inkMuted)
    Spacer(Modifier.height(14.dp))
    Text(
        "Textdateien mit einer Karte je Zeile: Vorderseite und Rückseite durch Tabulator, Semikolon oder Komma getrennt, " +
            "wie sie Karteikarten-Programme und Tabellen ausgeben. Nur Text wandert mit, Bilder und der Lernstand bleiben hier.",
        style = Ranzen.type.body, color = c.inkMuted,
    )
    Spacer(Modifier.height(16.dp))
    val cards = pending
    if (cards != null) {
        Text("${cards.size} Karten gefunden", style = Ranzen.type.heading, color = c.ink)
        if (note.isNotEmpty()) Text(note, style = Ranzen.type.caption, color = c.inkMuted)
        cards.take(3).forEach { Text("• ${it.front.take(60)}", style = Ranzen.type.body, color = c.ink) }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalButton("Importieren", {
                scope.launch {
                    val r = repo.importCards(deck.id, cards)
                    pending = null
                    message = "${r.added} Karten importiert" + if (r.duplicates > 0) ", ${r.duplicates} waren schon da." else "."
                }
            })
            QuietButton("Abbrechen", { pending = null })
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalButton("Datei importieren", { open.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) })
        }
        Spacer(Modifier.height(10.dp))
        Column {
            QuietButton("Als TSV exportieren (Karteikarten-Programme)", { saveTsv.launch(fileName(deck.name, "tsv")) }, color = c.ink)
            QuietButton("Als CSV exportieren (Excel)", { saveCsv.launch(fileName(deck.name, "csv")) }, color = c.ink)
        }
    }
    message?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = Ranzen.type.body, color = c.ink)
    }
    Spacer(Modifier.height(16.dp))
    QuietButton("Fertig", onClose, color = c.ink)
}

private fun fileName(deck: String, ext: String): String =
    deck.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().ifEmpty { "Stapel" } + "." + ext

private suspend fun writeDeck(context: Context, repo: StudyRepository, deck: DeckCounts, uri: Uri, write: (List<Pair<String, String>>) -> String): String {
    val pairs = repo.deckCards(deck.id).map { it.front to it.back }
    return try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(write(pairs).toByteArray(Charsets.UTF_8)) } ?: throw IOException("nicht schreibbar")
        }
        "${pairs.size} Karten gespeichert."
    } catch (_: IOException) {
        "Die Datei ließ sich nicht speichern."
    }
}
