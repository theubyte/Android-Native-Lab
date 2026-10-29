package de.millione.schulranzen.study

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.RetroSwitch
import de.millione.schulranzen.study.ui.SheetActions
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Karte aus Ausschnitt": a cut-out from a notebook or book becomes the
 * picture of a new card. The picture goes on the front (question) or back
 * (answer), the learner writes the other side and chooses the deck or exam.
 * With a [source], the place can also be kept as exam material. The file is
 * copied; the host may delete it after [onDone] or [onCancel].
 */
@Composable
fun CardFromClipSheet(image: File, source: StudyLinkDraft?, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val exams by repo.nextExams.collectAsState(initial = null)
    val decks by repo.decks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val c = Ranzen.colors

    var onBack by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<CardTarget?>(null) }
    var alsoMark by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exams, decks) { if (target == null && exams != null) target = defaultTarget(exams.orEmpty(), decks) }

    StudyOverlay(onCancel, width = 680.dp) {
        Text("Karte aus Ausschnitt", style = Ranzen.type.title, color = c.ink)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { FileImage(image, Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPlate("Bild vorne (Frage)", !onBack, { onBack = false })
            OptionPlate("Bild hinten (Antwort)", onBack, { onBack = true })
        }
        Spacer(Modifier.height(20.dp))
        FieldLabel(if (onBack) "Vorderseite · Frage" else "Rückseite · Antwort")
        LineField(
            text, { text = it },
            if (onBack) "z. B. Wie ist ein Membran-Ausdehnungsgefäß aufgebaut?" else "z. B. Membran-Ausdehnungsgefäß: Gaspolster, Membran, Wasserraum",
            singleLine = false, minLines = 2, style = if (onBack) Ranzen.type.heading else Ranzen.type.body,
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel("Text zum Bild (optional)")
        LineField(caption, { caption = it }, if (onBack) "z. B. Schnittbild MAG" else "z. B. Was zeigt das Bild?")
        Spacer(Modifier.height(20.dp))
        CardTargetPicker(exams.orEmpty(), decks, target) { target = it }
        if (source != null && target is CardTarget.ToExam) {
            Spacer(Modifier.height(12.dp))
            RetroSwitch("Stelle auch als Prüfungsstoff merken", alsoMark, { alsoMark = it }, hint = source.label)
        }
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, style = Ranzen.type.caption, color = c.signal) }
        SheetActions(
            primary = if (saving) "Speichert …" else "Karte anlegen",
            onPrimary = {
                if (saving) return@SheetActions
                if (onBack && text.isBlank() && caption.isBlank()) { error = "Bitte eine Frage für die Vorderseite schreiben."; return@SheetActions }
                saving = true
                scope.launch {
                    runCatching {
                        val deckId = repo.resolveDeck(target)
                        val front = if (onBack) text else caption
                        val back = if (onBack) caption else text
                        repo.addCard(deckId, front, back, image, imageOnBack = onBack)
                        val t = target
                        if (source != null && alsoMark && t is CardTarget.ToExam) repo.addLink(t.examId, source)
                    }.onSuccess { onDone() }.onFailure { saving = false; error = "Die Karte ließ sich nicht speichern." }
                }
            },
            onCancel = onCancel,
        )
    }
}
