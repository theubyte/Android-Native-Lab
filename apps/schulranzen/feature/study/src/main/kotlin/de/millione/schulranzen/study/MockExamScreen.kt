package de.millione.schulranzen.study

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.AnswerCheck
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.ClaudeApi
import de.millione.schulranzen.core.study.MatchRound
import de.millione.schulranzen.core.study.MockAnswer
import de.millione.schulranzen.core.study.MockExam
import de.millione.schulranzen.core.study.MockResult
import de.millione.schulranzen.core.study.Question
import de.millione.schulranzen.core.study.QuestionMaker
import de.millione.schulranzen.core.study.QuestionType
import de.millione.schulranzen.core.study.SelfGrade
import de.millione.schulranzen.core.study.TopicMatcher
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.rememberIsPhone
import de.millione.schulranzen.design.softRaise
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.data.MockAnswerRow
import de.millione.schulranzen.study.data.MockRun
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.StudyPage
import de.millione.schulranzen.study.ui.panelPad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

internal fun gradeWord(grade: Int): String = when (grade) {
    1 -> "sehr gut"; 2 -> "gut"; 3 -> "befriedigend"; 4 -> "ausreichend"; 5 -> "mangelhaft"; else -> "ungenügend"
}

private fun gradeOf(name: String): SelfGrade = runCatching { SelfGrade.valueOf(name) }.getOrDefault(SelfGrade.SKIPPED)

/** One question of a running exam with what the learner has put in so far. */
@Serializable
internal data class PaperItem(
    val type: QuestionType,
    val cardIds: List<Long>,
    val options: List<String> = emptyList(),
    val correct: Int = -1,
    val text: String = "",
    val answer: String = "",
    val rightOrder: List<Int> = emptyList(),
    val given: String = "",
    val choice: Int = -1,
    val matchSolved: List<Long> = emptyList(),
    val matchMissed: List<Long> = emptyList(),
) {
    val answered: Boolean
        get() = when (type) {
            QuestionType.CHOICE -> choice >= 0
            QuestionType.MATCH -> matchSolved.size == cardIds.size
            else -> given.isNotBlank()
        }
}

/**
 * The exam on paper, written out after every answer: a phone that closes the app in
 * the background must not cost the learner the exam. The clock is wall time, so it
 * goes on running like in a real exam.
 */
@Serializable
internal data class MockPaper(
    val examId: Long,
    val startedAt: Long,
    val limitSeconds: Int?,
    val items: List<PaperItem>,
)

internal object MockPaperStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(c: Context) = File(c.filesDir, "study/mock_active.json")

    suspend fun load(c: Context): MockPaper? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<MockPaper>(file(c).readText()) }.getOrNull()
    }

    suspend fun save(c: Context, p: MockPaper) = withContext(Dispatchers.IO) {
        val f = file(c)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(p))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    suspend fun clear(c: Context) = withContext(Dispatchers.IO) { file(c).delete() }
}

private fun Question.toItem(): PaperItem = when (this) {
    is Question.Flip -> PaperItem(QuestionType.FLIP, listOf(card.id))
    is Question.TypeIn -> PaperItem(QuestionType.TYPE, listOf(card.id))
    is Question.Choice -> PaperItem(QuestionType.CHOICE, listOf(card.id), options = options, correct = correct)
    is Question.Cloze -> PaperItem(QuestionType.CLOZE, listOf(card.id), text = text, answer = answer)
    is Question.Match -> PaperItem(QuestionType.MATCH, cards.map { it.id }, rightOrder = rightOrder)
}

/** A question of the exam after handing in, per card. A null [grade] is judged in the correction step. */
private data class Marked(
    val card: Card,
    val item: PaperItem,
    val given: String,
    val solution: String,
    val grade: SelfGrade?,
)

private fun mark(item: PaperItem, cards: Map<Long, Card>): List<Marked> {
    if (item.type == QuestionType.MATCH) {
        return item.cardIds.mapNotNull { id ->
            val card = cards[id] ?: return@mapNotNull null
            val grade = when {
                id !in item.matchSolved -> SelfGrade.SKIPPED
                id in item.matchMissed -> SelfGrade.WRONG
                else -> SelfGrade.RIGHT
            }
            Marked(card, item, if (id in item.matchSolved) card.back else "", card.back, grade)
        }
    }
    val card = cards[item.cardIds.first()] ?: return emptyList()
    return listOf(
        when (item.type) {
            QuestionType.CHOICE -> Marked(
                card, item, item.options.getOrElse(item.choice) { "" }, card.back,
                if (item.choice < 0) SelfGrade.SKIPPED else if (item.choice == item.correct) SelfGrade.RIGHT else SelfGrade.WRONG,
            )
            QuestionType.TYPE -> Marked(card, item, item.given, card.back, if (item.given.isBlank()) SelfGrade.SKIPPED else AnswerCheck.check(item.given, card.back).verdict.grade)
            QuestionType.CLOZE -> Marked(card, item, item.given, item.answer, if (item.given.isBlank()) SelfGrade.SKIPPED else AnswerCheck.check(item.given, item.answer).verdict.grade)
            else -> Marked(card, item, item.given, card.back, null)
        },
    )
}

private sealed interface MockPhase {
    data object Setup : MockPhase
    data class Running(val paper: MockPaper, val cards: Map<Long, Card>) : MockPhase
    data class Correct(val paper: MockPaper, val marked: List<Marked>, val seconds: Int) : MockPhase
    data class Done(val answers: List<MockAnswerRow>, val result: MockResult, val seconds: Int, val previous: Double?) : MockPhase
}

/**
 * Mock exam under real conditions: pick decks, number of questions and time; then
 * mixed question kinds against the clock with no help and no feedback until handing in.
 * Then free answers are corrected, the result is shown by topic, and everything wrong
 * can be relearned as its own round. All runs stay in the history.
 */
@Composable
internal fun MockExamPage(
    repo: StudyRepository,
    exam: Exam,
    decks: List<DeckCounts>,
    onBack: () -> Unit,
    onRelearn: (List<Long>) -> Unit,
    onFromText: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<MockPhase>(MockPhase.Setup) }
    val topics = remember(exam.topics) { exam.topics.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    val deckNames = decks.associate { it.id to it.name }
    val accent = SubjectPalette.of(exam.colorIndex)

    fun finish(paper: MockPaper, marked: List<Marked>, seconds: Int) {
        val drafts = marked.map { m ->
            MockAnswerDraft(
                m.card.id,
                TopicMatcher.topicOf("${m.card.front} ${m.card.back}", topics, deckNames[m.card.deckId] ?: exam.subject),
                m.item.type.name,
                if (m.item.type == QuestionType.CLOZE) m.item.text else m.card.front.ifBlank { "Bildkarte" },
                m.solution, m.given, m.grade ?: SelfGrade.SKIPPED,
            )
        }
        val result = MockExam.evaluate(drafts.map { MockAnswer(it.topic, it.grade) })
        scope.launch {
            val before = repo.mockRuns(exam.id).now().firstOrNull()?.percent
            val run = repo.saveMockRun(exam.id, result, drafts, seconds, paper.limitSeconds)
            MockPaperStore.clear(context)
            phase = MockPhase.Done(repo.mockAnswers(run), result, seconds, before)
        }
    }

    fun handIn(paper: MockPaper, cards: Map<Long, Card>) {
        val used = ((System.currentTimeMillis() - paper.startedAt) / 1000).toInt()
        val seconds = paper.limitSeconds?.let { used.coerceAtMost(it) } ?: used
        val marked = paper.items.flatMap { mark(it, cards) }
        if (marked.any { it.grade == null }) phase = MockPhase.Correct(paper, marked, seconds)
        else finish(paper, marked, seconds)
    }

    when (val p = phase) {
        MockPhase.Setup -> MockSetup(
            repo, exam, decks, onBack, onFromText,
            onStart = { paper, cards -> phase = MockPhase.Running(paper, cards) },
            onOpenRun = { run ->
                scope.launch {
                    val answers = repo.mockAnswers(run.id)
                    val result = if (answers.isEmpty()) MockResult(run.asked, run.points, run.percent, run.grade, emptyList(), emptyList())
                    else MockExam.evaluate(answers.map { MockAnswer(it.topic, gradeOf(it.grade)) })
                    phase = MockPhase.Done(answers, result, run.seconds, null)
                }
            },
        )
        is MockPhase.Running -> MockRunning(
            exam, p.paper, p.cards, accent,
            onAbort = { scope.launch { MockPaperStore.clear(context) }; phase = MockPhase.Setup },
            onHandIn = { handIn(it, p.cards) },
        )
        is MockPhase.Correct -> MockCorrect(exam, p.marked, accent) { graded -> finish(p.paper, graded, p.seconds) }
        is MockPhase.Done -> MockDone(exam, p.result, p.answers, p.seconds, p.previous, onBack = { phase = MockPhase.Setup }, onRelearn = onRelearn)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockSetup(
    repo: StudyRepository,
    exam: Exam,
    decks: List<DeckCounts>,
    onBack: () -> Unit,
    onFromText: () -> Unit,
    onStart: (MockPaper, Map<Long, Card>) -> Unit,
    onOpenRun: (MockRun) -> Unit,
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val own = deckFor(exam, decks)
    val chosen = remember { mutableStateListOf<Long>().apply { own?.let { add(it.id) } } }
    var count by remember { mutableIntStateOf(10) }
    var minutes by remember { mutableStateOf<Int?>(MockExam.suggestedMinutes(10)) }
    var minutesTouched by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf<MockPaper?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val runs by remember(exam.id) { repo.mockRuns(exam.id) }.collectAsState(initial = emptyList())
    val available = decks.filter { it.id in chosen }.sumOf { it.total }

    LaunchedEffect(exam.id) { resume = MockPaperStore.load(context)?.takeIf { it.examId == exam.id } }

    StudyPage(header = {
        ScreenHeader("Probeprüfung", "${exam.subject} · ${ExamKind.of(exam.kind).label}", onBack, accent = SubjectPalette.of(exam.colorIndex))
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val setup: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(panelPad)) {
                        resume?.let { paper ->
                            Text("Eine Prüfung läuft noch", style = Ranzen.type.heading, color = c.ink)
                            Text("Die Zeit ist weitergelaufen, wie in einer echten Prüfung.", style = Ranzen.type.caption, color = c.inkMuted)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SignalButton("Weiterschreiben", {
                                    scope.launch {
                                        val cards = repo.cardsById(paper.items.flatMap { it.cardIds }).associateBy { it.id }
                                        onStart(paper, cards)
                                    }
                                })
                                Spacer(Modifier.width(8.dp))
                                QuietButton("Verwerfen", { scope.launch { MockPaperStore.clear(context); resume = null } }, color = c.signal)
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        Text(
                            "Wie im Ernst: gemischte Fragearten, Uhr läuft, keine Hilfe und keine Lösung bis zur Abgabe.",
                            style = Ranzen.type.body, color = c.inkMuted,
                        )
                        Spacer(Modifier.height(16.dp))
                        FieldLabel("Stapel")
                        if (decks.isEmpty()) EmptyNote("Noch keine Karten. Erst Karten schreiben oder aus Text erzeugen, dann prüfen.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            decks.forEach { d ->
                                OptionPlate("${d.name} (${d.total})", d.id in chosen, {
                                    if (d.id in chosen) chosen.remove(d.id) else chosen.add(d.id)
                                    hint = null
                                }, lamp = SubjectPalette.of(d.colorIndex))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        QuietButton("Fragen aus Heft- oder Buchtext erzeugen", onFromText, color = c.ink)
                        Spacer(Modifier.height(16.dp))
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
                        Spacer(Modifier.height(22.dp))
                        hint?.let { Text(it, style = Ranzen.type.body, color = c.signal, modifier = Modifier.padding(bottom = 10.dp)) }
                        SignalButton("Probeprüfung starten", {
                            if (available == 0) {
                                hint = if (chosen.isEmpty()) "Wähle mindestens einen Stapel." else "In den gewählten Stapeln sind noch keine Karten."
                                return@SignalButton
                            }
                            scope.launch {
                                val qs = repo.mockQuestions(chosen.toList(), count)
                                val pool = repo.cardsOf(chosen.toList()).map { it.quiz() }
                                val items = QuestionMaker.examMix(qs.map { it.quiz() }, pool, Random(System.currentTimeMillis())).map { it.toItem() }
                                if (items.isEmpty()) return@launch
                                val paper = MockPaper(exam.id, System.currentTimeMillis(), minutes?.let { it * 60 }, items)
                                MockPaperStore.save(context, paper)
                                onStart(paper, qs.associateBy { it.id })
                            }
                        }, icon = StudyIcons.Play)
                    }
                }
            }
            val history: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                        SectionHead("Verlauf", runs.size)
                        if (runs.isEmpty()) EmptyNote("Noch keine Probeprüfung. Schwache Karten kommen öfter dran.")
                        if (runs.size >= 2) {
                            MockTrend(runs)
                            Spacer(Modifier.height(10.dp))
                        }
                        runs.forEach { r ->
                            val day = Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).toLocalDate()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Auswertung öffnen") { onOpenRun(r) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(day.format(Fmt.shortDate), style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.width(64.dp))
                                Text("${r.percent.toInt()} %", style = Ranzen.type.mono, color = c.ink, modifier = Modifier.width(58.dp))
                                Text("Note ${r.grade} · ${gradeWord(r.grade)}", style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ChevronIcon()
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

/** The last runs as small columns, oldest left; the 50 and 67 % marks decide their colour. */
@Composable
private fun MockTrend(runs: List<MockRun>) {
    val c = Ranzen.colors
    val shown = runs.take(12).reversed()
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { r ->
            val color = when {
                r.percent >= MockExam.SOLID_PERCENT -> SubjectPalette.of(3)
                r.percent >= MockExam.PASS_PERCENT -> SubjectPalette.of(2)
                else -> SubjectPalette.of(1)
            }
            Box(Modifier.weight(1f).fillMaxHeight((r.percent / 100.0).toFloat().coerceIn(0.04f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(color))
        }
        repeat((12 - shown.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).height(1.dp).background(c.line)) }
    }
}

/** A matching block as it was left, when the learner comes back to it. */
private fun restoredRound(item: PaperItem, q: Question.Match): MatchRound {
    val round = MatchRound(q.cards)
    item.matchMissed.forEach { id ->
        val back = AnswerCheck.normalize(q.cards.first { it.id == id }.back)
        q.cards.firstOrNull { AnswerCheck.normalize(it.back) != back }?.let { round.pick(id, it.id) }
    }
    item.matchSolved.forEach { round.pick(it, it) }
    return round
}

@Composable
private fun MockRunning(exam: Exam, start: MockPaper, cards: Map<Long, Card>, accent: Color, onAbort: () -> Unit, onHandIn: (MockPaper) -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val phone = rememberIsPhone()
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<PaperItem>().apply { addAll(start.items) } }
    var index by remember { mutableIntStateOf(items.indexOfFirst { !it.answered }.coerceAtLeast(0)) }
    var confirmAbort by remember { mutableStateOf(false) }
    var confirmHandIn by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableIntStateOf(start.limitSeconds ?: 0) }
    var handed by remember { mutableStateOf(false) }

    fun current() = start.copy(items = items.toList())
    fun handIn() {
        // Timer and key can meet; an exam is handed in once.
        if (handed) return
        handed = true
        onHandIn(current())
    }

    BackHandler(enabled = zoom == null) { if (confirmAbort) onAbort() else confirmAbort = true }
    LaunchedEffect(Unit) {
        val limit = start.limitSeconds ?: return@LaunchedEffect
        while (true) {
            left = (limit - (System.currentTimeMillis() - start.startedAt) / 1000).toInt().coerceAtLeast(0)
            if (left == 0) { handIn(); return@LaunchedEffect }
            delay(500)
        }
    }

    StudyPage(header = {
        ScreenHeader(
            "Probeprüfung · ${exam.subject}",
            "Frage ${index + 1} von ${items.size}",
            onBack = { if (confirmAbort) onAbort() else confirmAbort = true },
            accent = accent,
        ) {
            if (confirmAbort) QuietButton("Wirklich abbrechen?", onAbort, color = c.signal)
            if (start.limitSeconds != null) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink.copy(alpha = if (c.isDark) 0.10f else 0.88f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        "%02d:%02d".format(left / 60, left % 60),
                        style = if (phone) Ranzen.type.mono else Ranzen.type.monoLarge,
                        color = if (left < 60) c.signal else if (c.isDark) c.ink else c.paper,
                    )
                }
            }
        }
    }) {
        val item = items.getOrNull(index) ?: return@StudyPage
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Answered or not, nothing about right or wrong: there is no help in an exam.
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.forEachIndexed { i, q ->
                    Box(
                        Modifier.weight(1f).height(28.dp).clickable(onClickLabel = "Frage ${i + 1}") { index = i; confirmHandIn = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.fillMaxWidth().height(if (i == index) 10.dp else 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == index) c.signal else if (q.answered) c.inkMuted else c.line))
                    }
                }
            }
            val card = cards[item.cardIds.first()]
            val update: (PaperItem) -> Unit = { changed ->
                items[index] = changed
                confirmHandIn = false
                val snapshot = current()
                scope.launch { MockPaperStore.save(context, snapshot) }
            }
            Column(Modifier.weight(1f).widthIn(max = 1100.dp).fillMaxWidth()) {
                when {
                    card == null -> EmptyNote("Diese Karte wurde inzwischen gelöscht. Weiter zur nächsten Frage.")
                    item.type == QuestionType.MATCH -> {
                        val q = remember(index) { Question.Match(item.cardIds.mapNotNull { cards[it]?.quiz() }, item.rightOrder) }
                        val round = remember(index) { restoredRound(item, q) }
                        Text("Ordne jeder Frage ihre Antwort zu.", style = Ranzen.type.heading, color = c.ink, modifier = Modifier.padding(bottom = 10.dp))
                        MatchBoard(
                            q, onDone = { _, _ -> }, modifier = Modifier.weight(1f, fill = false), initial = round,
                            onPick = { r -> update(items[index].copy(matchSolved = r.solvedIds.toList(), matchMissed = r.missed.toList())) },
                        )
                    }
                    else -> {
                        val text = if (item.type == QuestionType.CLOZE) card.front.ifBlank { "Ergänze" } + "\n\n" + item.text else card.front
                        QuestionCardFace("FRAGE ${index + 1}", card, Modifier.weight(1f), text = text, accent = accent, onZoom = { zoom = it })
                        Spacer(Modifier.height(10.dp))
                        if (item.type == QuestionType.CHOICE) {
                            ChoiceList(Question.Choice(card.quiz(), item.options, item.correct), item.choice, false, { update(item.copy(choice = it)) })
                        } else {
                            LineField(
                                item.given, { update(item.copy(given = it)) },
                                if (item.type == QuestionType.CLOZE) "Was gehört in die Lücke?" else "Deine Antwort",
                                singleLine = false,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(bottom = if (phone) 12.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) QuietButton("Zurück", { index--; confirmHandIn = false }, color = c.ink)
                Spacer(Modifier.weight(1f))
                val open = items.count { !it.answered }
                if (index < items.lastIndex) {
                    if (open == 0) QuietButton("Abgeben", ::handIn, color = c.ink)
                    Spacer(Modifier.width(8.dp))
                    SignalButton("Weiter", { index++; confirmHandIn = false })
                } else {
                    SignalButton(
                        when {
                            open == 0 -> "Abgeben"
                            confirmHandIn -> "Trotzdem abgeben"
                            else -> "Abgeben ($open offen)"
                        },
                        { if (open == 0 || confirmHandIn) handIn() else confirmHandIn = true },
                    )
                }
            }
        }
    }
    zoom?.let { ImageZoom(it) { zoom = null } }
}

/** After handing in: free answers are compared with the solution, one at a time. */
@Composable
private fun MockCorrect(exam: Exam, marked: List<Marked>, accent: Color, onDone: (List<Marked>) -> Unit) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assistant = remember { StudyAssistant(context.applicationContext) }
    val hasKey by assistant.key.present.collectAsState()
    val graded = remember { mutableStateListOf<Marked>().apply { addAll(marked) } }
    val pos = graded.indexOfFirst { it.grade == null }
    val total = marked.count { it.grade == null }
    var feedback by remember(pos) { mutableStateOf<String?>(null) }
    var suggestion by remember(pos) { mutableStateOf<SelfGrade?>(null) }
    var busy by remember(pos) { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }

    LaunchedEffect(pos) {
        if (pos < 0 && !submitted) { submitted = true; onDone(graded.toList()) }
    }
    BackHandler { /* The exam is handed in; only forward from here. */ }

    StudyPage(header = {
        ScreenHeader("Korrektur · ${exam.subject}", if (pos < 0) "Fertig" else "Noch ${graded.count { it.grade == null }} von $total", onBack = {}, accent = accent, showBack = false)
    }) {
        val m = graded.getOrNull(pos) ?: return@StudyPage
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            QuestionCardFace("ANTWORT PRÜFEN", m.card, Modifier.weight(1f), accent = accent, onZoom = { zoom = it }) {
                Spacer(Modifier.height(12.dp))
                Text("DEINE ANTWORT", style = Ranzen.type.mono, color = c.inkMuted)
                Text(m.given.ifBlank { "(nichts eingetragen)" }, style = Ranzen.type.body, color = c.ink, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                SolutionBlock(m.card, m.solution, onZoom = { zoom = it })
                feedback?.let { Text(it, style = Ranzen.type.body, color = c.ink, modifier = Modifier.padding(top = 10.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            if (hasKey && m.given.isNotBlank() && feedback == null) {
                QuietButton(if (busy) "Claude liest …" else "Von Claude bewerten lassen", {
                    if (busy) return@QuietButton
                    busy = true
                    scope.launch {
                        try {
                            val r = assistant.review(m.card.front, m.solution, m.given)
                            feedback = if (r == null) "Die Bewertung war nicht lesbar. Bewerte selbst." else r.feedback.ifBlank { "Ohne Anmerkung." }
                            suggestion = r?.grade
                        } catch (e: AssistantException) {
                            feedback = ClaudeApi.describe(e.error)
                        } finally { busy = false }
                    }
                }, color = c.ink)
            }
            Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().height(72.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("Falsch", SubjectPalette.of(1), SelfGrade.WRONG),
                    Triple("Teilweise", SubjectPalette.of(2), SelfGrade.PARTLY),
                    Triple("Richtig", SubjectPalette.of(3), SelfGrade.RIGHT),
                ).forEach { (label, col, g) ->
                    GradeKey(label, col, Modifier.weight(1f), suggested = suggestion == g) { graded[pos] = m.copy(grade = g) }
                }
            }
        }
    }
    zoom?.let { ImageZoom(it) { zoom = null } }
}

@Composable
private fun GradeKey(label: String, accent: Color, modifier: Modifier, suggested: Boolean = false, onClick: () -> Unit) {
    val c = Ranzen.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxHeight().softRaise(16.dp, 1f, c.bevelLight, c.bevelShadow).clip(shape).background(c.raised)
            .border(if (suggested) 2.dp else 1.dp, if (suggested) accent else c.line, shape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(accent, size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Ranzen.type.label, color = c.ink)
    }
}

@Composable
private fun MockDone(
    exam: Exam,
    result: MockResult,
    answers: List<MockAnswerRow>,
    seconds: Int,
    previous: Double?,
    onBack: () -> Unit,
    onRelearn: (List<Long>) -> Unit,
) {
    val c = Ranzen.colors
    val wrong = answers.filter { MockExam.needsRelearning(gradeOf(it.grade)) }
    val relearn = wrong.mapNotNull { it.cardId }.distinct()
    BackHandler(onBack = onBack)
    StudyPage(header = {
        ScreenHeader("Auswertung", "${exam.subject} · ${result.asked} Fragen", onBack, accent = SubjectPalette.of(exam.colorIndex))
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val summary: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(panelPad)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                            Counter("%d".format(result.grade), "Note", accent = if (result.grade >= 5) c.signal else null)
                            Counter("%02d %%".format(result.percent.toInt()), "Punkte")
                            if (seconds > 0) Counter("%d′".format((seconds + 59) / 60), "Zeit")
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "${gradeWord(result.grade).replaceFirstChar { it.titlecase(Locale.GERMANY) }} · " +
                                "${"%.1f".format(Locale.GERMANY, result.points)} von ${result.asked} Punkten",
                            style = Ranzen.type.heading, color = c.ink,
                        )
                        Text("Nach dem IHK-Notenschlüssel, aus deinen eigenen Karten. Die echte Prüfung kann anders fragen.", style = Ranzen.type.caption, color = c.inkMuted)
                        previous?.let { p ->
                            val d = (result.percent - p).toInt()
                            Text(
                                when {
                                    d > 0 -> "$d Prozentpunkte besser als beim letzten Mal."
                                    d < 0 -> "${-d} Prozentpunkte schwächer als beim letzten Mal."
                                    else -> "Genauso wie beim letzten Mal."
                                },
                                style = Ranzen.type.body, color = c.ink, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SectionHead("Nach Thema", result.topics.size)
                        result.topics.forEach { t -> TopicBar(t.topic, t.percent, t.asked) }
                        if (result.topics.isNotEmpty() && result.weakTopics.isEmpty()) EmptyNote("Kein Thema unter 67 %. Stark!")
                        Spacer(Modifier.height(18.dp))
                        if (relearn.isNotEmpty()) SignalButton("Nachlernen (${relearn.size})", { onRelearn(relearn) }, icon = StudyIcons.Play)
                        QuietButton("Zum Verlauf", onBack, color = c.ink)
                    }
                }
            }
            val wrongPanel: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = panelPad, vertical = 18.dp)) {
                        SectionHead("Zum Nacharbeiten", wrong.size)
                        if (wrong.isEmpty()) EmptyNote(if (answers.isEmpty()) "Für diesen Lauf sind keine Einzelantworten gespeichert." else "Alles richtig.")
                        else Text("Diese Karten waren nach der Prüfung wieder fällig.", style = Ranzen.type.caption, color = c.inkMuted)
                        wrong.forEach { w ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 7.dp)) { Lamp(if (gradeOf(w.grade) == SelfGrade.PARTLY) SubjectPalette.of(2) else SubjectPalette.of(1), size = 7.dp) }
                                    Spacer(Modifier.width(10.dp))
                                    Text(w.question, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f))
                                }
                                Text("Du: ${w.given.ifBlank { "keine Antwort" }}", style = Ranzen.type.caption, color = c.inkMuted, modifier = Modifier.padding(start = 17.dp, top = 2.dp))
                                Text(w.solution, style = Ranzen.type.label, color = c.ink, modifier = Modifier.padding(start = 17.dp, top = 2.dp))
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { summary() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { wrongPanel() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { summary(); wrongPanel() }
            }
        }
    }
}

@Composable
internal fun TopicBar(topic: String, percent: Double, asked: Int) {
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
