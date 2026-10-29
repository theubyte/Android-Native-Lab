package de.millione.schulranzen.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.MockAnswer
import de.millione.schulranzen.core.study.MockExam
import de.millione.schulranzen.core.study.MockResult
import de.millione.schulranzen.core.study.SelfGrade
import de.millione.schulranzen.core.study.TopicMatcher
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.StudyPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal fun gradeWord(grade: Int): String = when (grade) {
    1 -> "sehr gut"; 2 -> "gut"; 3 -> "befriedigend"; 4 -> "ausreichend"; 5 -> "mangelhaft"; else -> "ungenügend"
}

private sealed interface MockPhase {
    data object Setup : MockPhase
    data class Running(val questions: List<Card>, val seconds: Int?) : MockPhase
    data class Done(val result: MockResult, val weak: List<Card>) : MockPhase
}

/**
 * Mock exam for one exam: choose decks, number of questions and time;
 * answer with self-assessment; see an estimated grade and the weak topics.
 * Cards answered wrong are pulled forward into today's review.
 */
@Composable
internal fun MockExamPage(
    repo: StudyRepository,
    exam: Exam,
    decks: List<DeckCounts>,
    onBack: () -> Unit,
    onStudy: (deckId: Long?) -> Unit,
) {
    var phase by remember { mutableStateOf<MockPhase>(MockPhase.Setup) }
    val scope = rememberCoroutineScope()
    val accent = SubjectPalette.of(exam.colorIndex)
    val topics = remember(exam.topics) { exam.topics.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    val deckNames = decks.associate { it.id to it.name }

    when (val p = phase) {
        MockPhase.Setup -> MockSetup(repo, exam, decks, onBack) { qs, secs -> phase = MockPhase.Running(qs, secs) }
        is MockPhase.Running -> MockRun(
            exam, p.questions, p.seconds, accent,
            onAbort = { phase = MockPhase.Setup },
            onFinish = { grades ->
                val answers = p.questions.mapIndexed { i, card ->
                    val topic = TopicMatcher.topicOf("${card.front} ${card.back}", topics, deckNames[card.deckId] ?: exam.subject)
                    MockAnswer(topic, grades.getOrNull(i) ?: SelfGrade.SKIPPED)
                }
                val result = MockExam.evaluate(answers)
                val weak = p.questions.filterIndexed { i, _ -> (grades.getOrNull(i) ?: SelfGrade.SKIPPED) != SelfGrade.RIGHT }
                scope.launch { repo.saveMockRun(exam.id, result, weak.map { it.id }) }
                phase = MockPhase.Done(result, weak)
            },
        )
        is MockPhase.Done -> MockDone(
            exam, p.result, p.weak, onBack,
            onAgain = { phase = MockPhase.Setup },
            onStudyWeak = { onStudy(p.weak.map { it.deckId }.distinct().singleOrNull()) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockSetup(repo: StudyRepository, exam: Exam, decks: List<DeckCounts>, onBack: () -> Unit, onStart: (List<Card>, Int?) -> Unit) {
    val c = Ranzen.colors
    val scope = rememberCoroutineScope()
    val own = deckFor(exam, decks)
    val chosen = remember { mutableStateListOf<Long>().apply { own?.let { add(it.id) } } }
    var count by remember { mutableIntStateOf(10) }
    var minutes by remember { mutableStateOf<Int?>(MockExam.suggestedMinutes(10)) }
    var minutesTouched by remember { mutableStateOf(false) }
    val runs by remember(exam.id) { repo.mockRuns(exam.id) }.collectAsState(initial = emptyList())
    val available = decks.filter { it.id in chosen }.sumOf { it.total }

    StudyPage(header = {
        ScreenHeader("Probeprüfung", "${exam.subject} · ${ExamKind.of(exam.kind).label}", onBack, accent = SubjectPalette.of(exam.colorIndex))
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val setup: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(26.dp)) {
                        FieldLabel("Stapel")
                        if (decks.isEmpty()) EmptyNote("Noch keine Karten. Erst Karten schreiben, dann prüfen.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            decks.forEach { d ->
                                OptionPlate("${d.name} (${d.total})", d.id in chosen, {
                                    if (d.id in chosen) chosen.remove(d.id) else chosen.add(d.id)
                                }, lamp = SubjectPalette.of(d.colorIndex))
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        FieldLabel("Fragen")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15, 20, 30).forEach { n ->
                                OptionPlate("$n", count == n, {
                                    count = n
                                    if (!minutesTouched) minutes = MockExam.suggestedMinutes(n)
                                })
                            }
                        }
                        Text("$available Karten in den gewählten Stapeln", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(20.dp))
                        FieldLabel("Zeit")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(5, 10, 15, 20, 30, 45, 60, 90) + MockExam.suggestedMinutes(count)).distinct().sorted().forEach { m ->
                                OptionPlate("$m min", minutes == m, { minutes = m; minutesTouched = true })
                            }
                            OptionPlate("Ohne Zeit", minutes == null, { minutes = null; minutesTouched = true })
                        }
                        Spacer(Modifier.height(26.dp))
                        SignalButton("Probeprüfung starten", {
                            if (available == 0) return@SignalButton
                            scope.launch {
                                val qs = repo.mockQuestions(chosen.toList(), count)
                                if (qs.isNotEmpty()) onStart(qs, minutes?.let { it * 60 })
                            }
                        }, icon = StudyIcons.Play)
                    }
                }
            }
            val history: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
                        SectionHead("Bisherige Ergebnisse", runs.size)
                        if (runs.isEmpty()) EmptyNote("Noch keine Probeprüfung. Schwache Karten kommen öfter dran.")
                        runs.forEach { r ->
                            val day = Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).toLocalDate()
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(day.format(Fmt.dayDate), style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.width(110.dp))
                                Text("${r.percent.toInt()} %", style = Ranzen.type.mono, color = c.ink, modifier = Modifier.width(70.dp))
                                Text("Note ${r.grade} · ${gradeWord(r.grade)}", style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { setup() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { history() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { setup(); history() }
            }
        }
    }
}

@Composable
private fun MockRun(exam: Exam, questions: List<Card>, seconds: Int?, accent: Color, onAbort: () -> Unit, onFinish: (List<SelfGrade>) -> Unit) {
    val c = Ranzen.colors
    val grades = remember { mutableStateListOf<SelfGrade>() }
    var shown by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(seconds ?: 0) }
    var confirmAbort by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    val index = grades.size
    val card = questions.getOrNull(index)

    BackHandler(enabled = zoom == null) { if (confirmAbort) onAbort() else confirmAbort = true }
    LaunchedEffect(Unit) {
        if (seconds == null) return@LaunchedEffect
        while (left > 0) { delay(1000); left-- }
        onFinish(grades.toList())
    }
    LaunchedEffect(index) { if (index >= questions.size) onFinish(grades.toList()) }

    StudyPage(header = {
        ScreenHeader(
            "Probeprüfung · ${exam.subject}",
            "Frage ${minOf(index + 1, questions.size)} von ${questions.size}",
            onBack = { if (confirmAbort) onAbort() else confirmAbort = true },
            accent = accent,
        ) {
            if (confirmAbort) QuietButton("Wirklich abbrechen?", onAbort, color = c.signal)
            if (seconds != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = if (c.isDark) 0.10f else 0.88f)).padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        "%02d:%02d".format(left / 60, left % 60), style = Ranzen.type.monoLarge,
                        color = if (left < 60) c.signal else if (c.isDark) c.ink else c.paper,
                    )
                }
            }
        }
    }) {
        if (card == null) return@StudyPage
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Progress as a row of small lamps: answered ones coloured by the self-assessment.
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                questions.indices.forEach { i ->
                    val g = grades.getOrNull(i)
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                            when (g) {
                                SelfGrade.RIGHT -> SubjectPalette.of(3)
                                SelfGrade.PARTLY -> SubjectPalette.of(2)
                                SelfGrade.WRONG, SelfGrade.SKIPPED -> SubjectPalette.of(1)
                                null -> if (i == index) c.signal else c.line
                            },
                        ),
                    )
                }
            }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).widthIn(max = 1100.dp).fillMaxWidth()
                    .softRaise(14.dp, 1f, c.bevelLight, c.bevelShadow).clip(shape).background(c.raised)
                    .indexCard(accent, c.line.copy(alpha = 0.55f), headline = 64f)
                    .padding(horizontal = 36.dp, vertical = 20.dp),
            ) {
                Text("FRAGE ${index + 1}", style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.height(32.dp).padding(top = 6.dp))
                Spacer(Modifier.height(18.dp))
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (card.front.isNotBlank()) Text(card.front, style = Ranzen.type.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    card.frontImage?.let { img ->
                        StudyImage(img, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp).clickable(onClickLabel = "Bild groß") { zoom = img })
                    }
                    if (shown) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Spacer(Modifier.height(14.dp))
                        Text("LÖSUNG", style = Ranzen.type.mono, color = c.inkMuted)
                        Spacer(Modifier.height(6.dp))
                        if (card.back.isNotBlank()) {
                            Text(
                                card.back, style = Ranzen.type.heading, color = c.ink, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                        card.backImage?.let { img ->
                            StudyImage(img, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp).clickable(onClickLabel = "Bild groß") { zoom = img })
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.widthIn(max = 820.dp).fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
                if (!shown) {
                    SignalButton("Lösung zeigen", { shown = true }, icon = StudyIcons.Flip)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GradeKey("Falsch", SubjectPalette.of(1), Modifier.weight(1f)) { grades += SelfGrade.WRONG; shown = false }
                        GradeKey("Teilweise", SubjectPalette.of(2), Modifier.weight(1f)) { grades += SelfGrade.PARTLY; shown = false }
                        GradeKey("Richtig", SubjectPalette.of(3), Modifier.weight(1f)) { grades += SelfGrade.RIGHT; shown = false }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    zoom?.let { ImageZoom(it) { zoom = null } }
}

@Composable
private fun GradeKey(label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxHeight().softRaise(16.dp, 1f, c.bevelLight, c.bevelShadow).clip(shape).background(c.raised).border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(accent, size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Ranzen.type.label, color = c.ink)
    }
}

@Composable
private fun MockDone(exam: Exam, result: MockResult, weak: List<Card>, onBack: () -> Unit, onAgain: () -> Unit, onStudyWeak: () -> Unit) {
    val c = Ranzen.colors
    StudyPage(header = {
        ScreenHeader("Auswertung", "${exam.subject} · ${result.asked} Fragen", onBack, accent = SubjectPalette.of(exam.colorIndex))
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val summary: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(26.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Bottom) {
                            Counter("%d".format(result.grade), "Note (geschätzt)", accent = if (result.grade >= 5) c.signal else null)
                            Counter("%02d %%".format(result.percent.toInt()), "Punkte")
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "${gradeWord(result.grade).replaceFirstChar { it.titlecase(Locale.GERMANY) }} · " +
                                "${"%.1f".format(Locale.GERMANY, result.points)} von ${result.asked} Punkten",
                            style = Ranzen.type.heading, color = c.ink,
                        )
                        Text("Nach dem IHK-Notenschlüssel; Selbstbewertung, also nur eine Schätzung.", style = Ranzen.type.caption, color = c.inkMuted)
                        Spacer(Modifier.height(22.dp))
                        SectionHead("Themen", result.topics.size)
                        result.topics.forEach { t -> TopicBar(t.topic, t.percent, t.asked) }
                        if (result.weakTopics.isEmpty()) EmptyNote("Kein Thema unter 67 %. Stark!")
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (weak.isNotEmpty()) SignalButton("Schwache Karten lernen", onStudyWeak, icon = StudyIcons.Play)
                            QuietButton("Neue Probeprüfung", onAgain, color = c.ink)
                        }
                    }
                }
            }
            val weakPanel: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
                        SectionHead("Zum Wiederholen", weak.size)
                        if (weak.isEmpty()) EmptyNote("Alles richtig.")
                        else Text("Diese Karten sind ab heute wieder fällig.", style = Ranzen.type.caption, color = c.inkMuted)
                        weak.forEach { w ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Lamp(SubjectPalette.of(1), size = 7.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(w.front.ifBlank { "Bildkarte" }, style = Ranzen.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { summary() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { weakPanel() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { summary(); weakPanel() }
            }
        }
    }
}

@Composable
private fun TopicBar(topic: String, percent: Double, asked: Int) {
    val c = Ranzen.colors
    val color = when {
        percent >= MockExam.SOLID_PERCENT -> SubjectPalette.of(3)
        percent >= MockExam.PASS_PERCENT -> SubjectPalette.of(2)
        else -> SubjectPalette.of(1)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(topic, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${percent.toInt()} % · $asked", style = Ranzen.type.mono, color = c.inkMuted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth((percent / 100.0).toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}
