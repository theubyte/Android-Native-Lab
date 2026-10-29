package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.ChatTurn
import de.millione.schulranzen.core.study.ClaudeApi
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.StudyIcons
import kotlinx.coroutines.launch

/**
 * The explanation helper as a conversation: ask, get a school-level answer,
 * ask again. Each answer can become a card or a set of card proposals.
 * Without a key it explains how to get one and nothing else happens.
 */
@Composable
internal fun ExplainContent(
    initialQuestion: String,
    onSaveCard: (question: String, answer: String) -> Unit,
    onSuggest: (text: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val assistant = remember { StudyAssistant(context.applicationContext) }
    val hasKey by assistant.key.present.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Ranzen.colors
    val turns = remember { mutableStateListOf<ChatTurn>() }
    var input by remember { mutableStateOf(initialQuestion) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    fun ask() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            try {
                val answer = assistant.explain(q, turns.toList())
                turns += ChatTurn("user", q)
                turns += ChatTurn("assistant", answer)
                input = ""
            } catch (e: AssistantException) {
                error = ClaudeApi.describe(e.error)
            } finally { busy = false }
        }
    }
    LaunchedEffect(turns.size, busy) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier) {
        if (!hasKey) {
            PaperPanel(Modifier.fillMaxWidth(), corner = 20.dp) {
                Column(Modifier.padding(24.dp)) {
                    Text("Erklärhilfe einrichten", style = Ranzen.type.heading, color = c.ink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Die Erklärhilfe fragt Claude (Anthropic). Dafür braucht es einen eigenen API-Schlüssel. " +
                            "Alles andere in „Lernen“ funktioniert auch ohne.",
                        style = Ranzen.type.body, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(16.dp))
                    SignalButton("Schlüssel eintragen", onSettings)
                }
            }
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (turns.isEmpty() && !busy) EmptyNote("Frag nach einem Begriff, einer Formel oder einer Aufgabe. Zum Beispiel: „Wie funktioniert ein Mischer im Heizkreis?“")
            turns.forEachIndexed { i, t ->
                if (t.role == "user") Bubble(t.text, mine = true)
                else {
                    Bubble(t.text, mine = false)
                    val question = turns.getOrNull(i - 1)?.text.orEmpty()
                    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuietButton("Als Karte speichern", { onSaveCard(question, t.text) }, color = c.ink)
                        QuietButton("Karten daraus", { onSuggest(t.text) }, color = c.ink)
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Lamp(c.signal, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text("CLAUDE DENKT …", style = Ranzen.type.mono, color = c.inkMuted)
            }
            error?.let { Text(it, style = Ranzen.type.body, color = c.signal, modifier = Modifier.padding(8.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        PaperPanel(Modifier.fillMaxWidth(), corner = 18.dp) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LineField(input, { input = it }, if (turns.isEmpty()) "Deine Frage" else "Nachfragen …", Modifier.weight(1f), singleLine = false)
                Spacer(Modifier.width(12.dp))
                SignalButton(if (busy) "…" else "Fragen", ::ask)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.widthIn(max = 820.dp).clip(shape)
                .background(if (mine) c.raised else c.paper)
                .border(1.dp, if (mine) c.line else c.line.copy(alpha = 0.5f), shape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            SelectionContainer { Text(text, style = Ranzen.type.body, color = if (mine) c.inkMuted else c.ink) }
        }
    }
}

/**
 * "Erklären lassen" over any screen of the host, e.g. with text the
 * learner selected in a notebook. Saving a card or card proposals happens
 * inside the sheet.
 */
@Composable
fun ExplainSheet(question: String, onClose: () -> Unit) {
    var mode by remember { mutableStateOf<Pair<String, String>?>(null) }
    var suggest by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    StudyOverlay(onClose, width = 900.dp) {
        when {
            settings -> AssistantSettingsContent(onClose = { settings = false })
            mode != null -> QuickCardContent(mode!!.first, mode!!.second, onDone = { mode = null }, onCancel = { mode = null })
            suggest != null -> SuggestCardsContent(suggest!!, preferDeck = null, onDone = { suggest = null }, onCancel = { suggest = null })
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RanzenIcon(StudyIcons.Assistant, tint = Ranzen.colors.signal)
                    Spacer(Modifier.width(10.dp))
                    Text("Erklärhilfe", style = Ranzen.type.title, color = Ranzen.colors.ink, modifier = Modifier.weight(1f))
                    QuietButton("Schließen", onClose)
                }
                Spacer(Modifier.height(16.dp))
                ExplainContent(
                    question, onSaveCard = { q, a -> mode = q to a }, onSuggest = { suggest = it }, onSettings = { settings = true },
                    modifier = Modifier.heightIn(min = 320.dp, max = 620.dp),
                )
            }
        }
    }
}

/** Saves one question/answer pair as a card in a chosen deck or exam. */
@Composable
internal fun QuickCardContent(front: String, back: String, onDone: () -> Unit, onCancel: () -> Unit, preferDeck: Long? = null) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val exams by repo.nextExams.collectAsState(initial = null)
    val decks by repo.decks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf(front) }
    var b by remember { mutableStateOf(back) }
    var target by remember { mutableStateOf<CardTarget?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(exams, decks) { if (target == null && exams != null) target = defaultTarget(exams.orEmpty(), decks, preferDeck) }
    Text("Als Karte speichern", style = Ranzen.type.title, color = Ranzen.colors.ink)
    Spacer(Modifier.height(18.dp))
    FieldLabel("Vorderseite · Frage")
    LineField(f, { f = it }, "Frage", singleLine = false, style = Ranzen.type.heading)
    Spacer(Modifier.height(16.dp))
    FieldLabel("Rückseite · Antwort (gern kürzen)")
    LineField(b, { b = it }, "Antwort", singleLine = false, minLines = 3)
    Spacer(Modifier.height(18.dp))
    CardTargetPicker(exams.orEmpty(), decks, target) { target = it }
    SheetActions(
        primary = if (saving) "Speichert …" else "Speichern",
        onPrimary = {
            if (saving || f.isBlank()) return@SheetActions
            saving = true
            scope.launch { repo.addCard(repo.resolveDeck(target), f, b); onDone() }
        },
        onCancel = onCancel,
    )
}

/** Settings of the study area: the API key for the explanation helper. */
@Composable
internal fun AssistantSettingsContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val key = remember { AssistantKey(context.applicationContext) }
    val present by key.present.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Ranzen.colors
    var masked by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(present) { masked = if (present) key.masked() else null }

    Text("Einstellungen · Erklärhilfe", style = Ranzen.type.title, color = c.ink)
    Spacer(Modifier.height(8.dp))
    Text(
        "Die Erklärhilfe nutzt Claude von Anthropic (Modell ${ClaudeApi.MODEL}). Einen API-Schlüssel gibt es unter " +
            "console.anthropic.com; die Kosten laufen über dieses Konto. Fragen und Kartentexte werden dafür an Anthropic gesendet. " +
            "Der Schlüssel wird verschlüsselt nur auf diesem Gerät gespeichert.",
        style = Ranzen.type.body, color = c.inkMuted,
    )
    Spacer(Modifier.height(20.dp))
    FieldLabel("API-Schlüssel")
    if (present) Text("Gespeichert: ${masked ?: "…"}", style = Ranzen.type.mono, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
    BasicTextField(
        input, { input = it; status = null },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = Ranzen.type.mono.copy(color = c.ink),
        cursorBrush = SolidColor(c.signal),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp)
            .drawBehind { drawLine(c.line, Offset(0f, size.height + 4.dp.toPx()), Offset(size.width, size.height + 4.dp.toPx()), 1.5.dp.toPx()) },
        decorationBox = { inner ->
            Box { if (input.isEmpty()) Text(if (present) "Neuen Schlüssel einfügen" else "sk-ant-…", style = Ranzen.type.mono, color = c.inkFaint); inner() }
        },
    )
    status?.let { (ok, msg) ->
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(if (ok) de.millione.schulranzen.design.SubjectPalette.of(3) else c.signal, size = 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(msg, style = Ranzen.type.body, color = c.ink)
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (present) QuietButton("Schlüssel löschen", { key.clear(); status = false to "Schlüssel gelöscht." }, color = c.signal)
        if (present) QuietButton(if (busy) "Prüft …" else "Verbindung prüfen", {
            if (busy) return@QuietButton
            busy = true
            scope.launch {
                status = try {
                    StudyAssistant(context.applicationContext).explain("Antworte nur mit: OK")
                    true to "Verbindung klappt."
                } catch (e: AssistantException) {
                    false to ClaudeApi.describe(e.error)
                } finally { busy = false }
            }
        }, color = c.ink)
    }
    SheetActions(
        primary = "Speichern",
        onPrimary = {
            val k = input.trim()
            if (k.isEmpty()) { onClose(); return@SheetActions }
            if (!k.startsWith("sk-")) { status = false to "Das sieht nicht nach einem API-Schlüssel aus (beginnt mit „sk-“)."; return@SheetActions }
            scope.launch { key.save(k); input = ""; status = true to "Gespeichert." }
        },
        onCancel = onClose,
        cancelLabel = "Zurück",
    )
}
