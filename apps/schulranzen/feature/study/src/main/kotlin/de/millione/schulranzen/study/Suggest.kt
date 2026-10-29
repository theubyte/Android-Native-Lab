package de.millione.schulranzen.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.CardSuggester
import de.millione.schulranzen.core.study.CardSuggestion
import de.millione.schulranzen.core.study.ClaudeApi
import de.millione.schulranzen.core.study.SuggestionKind
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.SheetActions
import de.millione.schulranzen.study.ui.StudyIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Karten vorschlagen": proposals for [text] (from a notebook page, a book
 * page, an explanation …). Offline proposals come at once; with an API key
 * Claude can make better ones. The learner edits, ticks and saves them.
 * An empty [text] shows a field to paste text into.
 */
@Composable
fun SuggestCardsSheet(text: String, onDone: () -> Unit, onCancel: () -> Unit) {
    StudyOverlay(onCancel, width = 760.dp) { SuggestCardsContent(text, preferDeck = null, onDone = onDone, onCancel = onCancel) }
}

private class Draft(front: String, back: String, val kind: SuggestionKind) {
    var front by mutableStateOf(front)
    var back by mutableStateOf(back)
    var selected by mutableStateOf(true)
}

@Composable
internal fun SuggestCardsContent(initialText: String, preferDeck: Long?, onDone: () -> Unit, onCancel: () -> Unit, preferExam: Long? = null) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val assistant = remember { StudyAssistant(context.applicationContext) }
    val hasKey by assistant.key.present.collectAsState()
    val exams by repo.nextExams.collectAsState(initial = null)
    val decks by repo.decks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val c = Ranzen.colors

    var text by remember { mutableStateOf(initialText) }
    val drafts = remember { mutableStateListOf<Draft>() }
    var analysed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<CardTarget?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun show(list: List<CardSuggestion>) {
        drafts.clear(); drafts.addAll(list.map { Draft(it.front, it.back, it.kind) }); analysed = true
    }
    fun offline() = scope.launch { show(withContext(Dispatchers.Default) { CardSuggester.suggest(text) }) }

    LaunchedEffect(Unit) { if (initialText.isNotBlank()) offline() }
    LaunchedEffect(exams, decks) { if (target == null && exams != null) target = defaultTarget(exams.orEmpty(), decks, preferDeck, preferExam) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        RanzenIcon(StudyIcons.Wand, tint = c.signal)
        Spacer(Modifier.width(10.dp))
        Text("Karten vorschlagen", style = Ranzen.type.title, color = c.ink)
    }
    Spacer(Modifier.height(18.dp))
    if (initialText.isBlank() || !analysed) {
        FieldLabel("Text")
        LineField(text, { text = it }, "Text einfügen, z. B. aus dem Heft oder Buch", singleLine = false, minLines = 5)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalButton("Vorschläge machen", { if (text.isNotBlank()) offline() })
        }
    }
    if (analysed) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${drafts.count { it.selected }} von ${drafts.size} ausgewählt", style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.weight(1f))
            if (hasKey) {
                QuietButton(if (busy) "Claude denkt …" else "Mit Claude vorschlagen", {
                    if (busy || text.isBlank()) return@QuietButton
                    busy = true; error = null
                    scope.launch {
                        try {
                            val list = assistant.suggestCards(text)
                            if (list.isEmpty()) error = "Claude hat keine Karten gefunden." else show(list)
                        } catch (e: AssistantException) {
                            error = ClaudeApi.describe(e.error)
                        } finally { busy = false }
                    }
                }, color = c.ink)
            }
        }
        if (!hasKey) Text("Mit einem API-Schlüssel (Lernen → Einstellungen) schlägt Claude bessere Karten vor.", style = Ranzen.type.caption, color = c.inkMuted)
        error?.let { Text(it, style = Ranzen.type.caption, color = c.signal, modifier = Modifier.padding(top = 6.dp)) }
        if (drafts.isEmpty()) EmptyNote("Keine Karten gefunden. Gut eignen sich Sätze wie „Ein MAG ist …“, „Begriff: Erklärung“ oder Aufzählungen.")
        drafts.forEach { d -> DraftRow(d) }
        Spacer(Modifier.height(18.dp))
        CardTargetPicker(exams.orEmpty(), decks, target) { target = it }
    }
    val chosen = drafts.count { it.selected && it.front.isNotBlank() }
    SheetActions(
        primary = when { saving -> "Speichert …"; chosen == 1 -> "1 Karte anlegen"; else -> "$chosen Karten anlegen" },
        onPrimary = {
            if (saving || chosen == 0) return@SheetActions
            saving = true
            scope.launch {
                val deckId = repo.resolveDeck(target)
                drafts.filter { it.selected && it.front.isNotBlank() }.forEach { repo.addCard(deckId, it.front, it.back) }
                onDone()
            }
        },
        onCancel = onCancel,
    )
}

@Composable
private fun DraftRow(d: Draft) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (d.selected) "Abwählen" else "Auswählen") { d.selected = !d.selected },
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.size(24.dp).clip(shape).background(if (d.selected) c.signal else c.raised).border(1.dp, if (d.selected) c.signal else c.line, shape),
                contentAlignment = Alignment.Center,
            ) { if (d.selected) RanzenIcon(RanzenIcons.Check, tint = c.onSignal, size = 18.dp) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).alpha(if (d.selected) 1f else 0.5f)) {
            LineField(d.front, { d.front = it }, "Frage", singleLine = false, style = Ranzen.type.label)
            LineField(d.back, { d.back = it }, "Antwort", singleLine = false, style = Ranzen.type.body)
        }
    }
}
