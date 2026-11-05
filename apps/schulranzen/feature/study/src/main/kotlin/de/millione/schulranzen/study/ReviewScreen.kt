package de.millione.schulranzen.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.study.AnswerCheck
import de.millione.schulranzen.core.study.AnswerResult
import de.millione.schulranzen.core.study.AnswerReview
import de.millione.schulranzen.core.study.AssistantException
import de.millione.schulranzen.core.study.ClaudeApi
import de.millione.schulranzen.core.study.Question
import de.millione.schulranzen.core.study.QuestionMaker
import de.millione.schulranzen.core.study.QuestionType
import de.millione.schulranzen.core.study.QuizCard
import de.millione.schulranzen.core.study.Rating
import de.millione.schulranzen.core.study.SelfGrade
import de.millione.schulranzen.core.study.Verdict
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.rememberIsPhone
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.OptionPlate
import de.millione.schulranzen.study.ui.StudyIcons
import kotlinx.coroutines.launch
import kotlin.random.Random

/** How cards are asked in a learning session. */
internal enum class StudyMode(val label: String) {
    FLIP("Umdrehen"), TYPE("Eintippen"), CHOICE("Auswahl"), CLOZE("Lücke"), MATCH("Zuordnen"), MIXED("Gemischt");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: FLIP
    }
}

/**
 * What a running session keeps. It lives outside the composition, so a detour to
 * the explanation helper does not start the round over.
 */
internal class ReviewMemory {
    val queue = mutableStateListOf<Card>()
    var pool by mutableStateOf<List<QuizCard>>(emptyList())
    var loaded by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var again by mutableIntStateOf(0)
    val history = mutableStateListOf<Pair<StudyRepository.Undo, Boolean>>()
    var mode by mutableStateOf(StudyMode.FLIP)
    var seed = 0
}

/** The rating that fits a checked answer, drawn as the suggested key. */
private fun suggested(r: AnswerResult?): Rating? = when (r?.verdict) {
    Verdict.RIGHT, Verdict.TYPO -> Rating.GOOD
    Verdict.PARTLY -> Rating.HARD
    Verdict.WRONG -> Rating.AGAIN
    else -> null
}

/**
 * Learning mode: one card at a time, asked the way the learner chose. "Nochmal" puts
 * the card at the end of today's round. The queue is read once at the start so the
 * order stays calm while answers are saved. [cardIds] asks exactly these cards in this
 * order (today's plan, a relearning round) instead of what is due in a deck.
 */
@Composable
internal fun ReviewSession(
    repo: StudyRepository,
    deckId: Long?,
    onDone: () -> Unit,
    onProgress: (done: Int, left: Int, canUndo: Boolean) -> Unit,
    undoSignal: Int,
    onExplain: ((Card) -> Unit)? = null,
    cardIds: List<Long>? = null,
    memory: ReviewMemory = remember { ReviewMemory() },
) {
    val c = Ranzen.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val assistant = remember { StudyAssistant(context.applicationContext) }
    val hasKey by assistant.key.present.collectAsState()
    val phone = rememberIsPhone()
    var preview by remember { mutableStateOf<Map<Rating, Int>>(emptyMap()) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var handledUndo by remember { mutableIntStateOf(undoSignal) }

    LaunchedEffect(deckId, cardIds) {
        if (memory.loaded) return@LaunchedEffect
        val cards = if (cardIds != null) repo.cardsById(cardIds) else repo.studyQueue(deckId)
        memory.queue.clear()
        memory.queue.addAll(cards)
        memory.mode = StudyMode.of(repo.studyMode())
        // Distractors come from the same deck as the card.
        memory.pool = repo.cardsOf(cards.map { it.deckId }.distinct()).map { it.quiz() }
        memory.loaded = true
    }
    val card = memory.queue.firstOrNull()
    LaunchedEffect(card?.id, card?.reps, card?.dueEpochDay) { preview = card?.let { repo.preview(it) } ?: emptyMap() }
    LaunchedEffect(memory.done, memory.queue.size, memory.history.size) { onProgress(memory.done, memory.queue.size, memory.history.isNotEmpty()) }

    var flipped by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var picked by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf<AnswerResult?>(null) }
    var review by remember { mutableStateOf<AnswerReview?>(null) }
    var reviewBusy by remember { mutableStateOf(false) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    fun resetCard() { flipped = false; typed = ""; picked = -1; result = null; review = null; reviewError = null }

    // Undo is triggered from the header key.
    LaunchedEffect(undoSignal) {
        if (undoSignal == handledUndo) return@LaunchedEffect
        handledUndo = undoSignal
        val (token, wasAgain) = memory.history.removeLastOrNull() ?: return@LaunchedEffect
        repo.undo(token)
        if (wasAgain) { memory.queue.removeAll { it.id == token.before.id }; memory.again-- }
        memory.queue.add(0, token.before)
        memory.done--
        resetCard()
    }

    var busy by remember { mutableStateOf(false) }
    fun rate(r: Rating) {
        val current = card ?: return
        // One answer per card, even if a key is tapped twice quickly.
        if (busy) return
        busy = true
        scope.launch {
            try {
                val token = repo.rate(current, r)
                memory.queue.removeAt(0)
                if (r == Rating.AGAIN) { memory.queue.add(token.after); memory.again++ }
                memory.history.add(token to (r == Rating.AGAIN))
                memory.done++
                resetCard()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!memory.loaded) return@Column
        if (card == null) {
            Finished(memory.done, memory.again, onDone)
            return@Column
        }
        // The way of asking, always in reach; the choice is remembered.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudyMode.entries.forEach { m ->
                OptionPlate(m.label, memory.mode == m, {
                    memory.mode = m
                    resetCard()
                    scope.launch { repo.saveStudyMode(m.name) }
                })
            }
        }

        // Matching takes several cards at once.
        val matchGroup = if (memory.mode == StudyMode.MATCH) {
            memory.queue.distinctBy { it.id }.map { it.quiz() }.filter(QuestionMaker::canMatch).take(5)
        } else emptyList()
        if (memory.mode == StudyMode.MATCH && matchGroup.size >= 3) {
            val q = remember(matchGroup.map { it.id }) { QuestionMaker.match(matchGroup, Random(memory.seed++)) }
            if (q != null) {
                Box(Modifier.weight(1f).widthIn(max = 900.dp).fillMaxWidth()) {
                    MatchBoard(q, onDone = { _, round ->
                        scope.launch {
                            for (qc in q.cards) {
                                val current = memory.queue.firstOrNull { it.id == qc.id } ?: continue
                                val first = round.firstTry(qc.id)
                                val token = repo.rate(current, if (first) Rating.GOOD else Rating.AGAIN)
                                memory.queue.removeAll { it.id == qc.id }
                                if (!first) { memory.queue.add(token.after); memory.again++ }
                                memory.history.add(token to !first)
                                memory.done++
                            }
                        }
                    })
                }
                Spacer(Modifier.height(16.dp))
                return@Column
            }
        }

        val question: Question = remember(card.id, memory.done, memory.mode) {
            val rnd = Random(memory.seed++)
            val quiz = card.quiz()
            when (memory.mode) {
                StudyMode.FLIP, StudyMode.MATCH -> Question.Flip(quiz)
                StudyMode.TYPE -> QuestionMaker.question(QuestionType.TYPE, quiz, memory.pool, rnd)
                StudyMode.CHOICE -> QuestionMaker.question(QuestionType.CHOICE, quiz, memory.pool, rnd)
                StudyMode.CLOZE -> QuestionMaker.question(QuestionType.CLOZE, quiz, memory.pool, rnd)
                StudyMode.MIXED -> QuestionMaker.question(listOf(QuestionType.TYPE, QuestionType.CHOICE, QuestionType.CLOZE).random(rnd), quiz, memory.pool, rnd)
            }
        }
        // A long explanation in typing mode is still written out, then judged by eye or by Claude.
        val freeAnswer = question is Question.Flip && memory.mode in setOf(StudyMode.TYPE, StudyMode.MIXED, StudyMode.CLOZE) && card.frontImage == null && card.backImage == null
        val checked = result != null || (question is Question.Flip && flipped)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (question is Question.Flip && !freeAnswer) {
                key(card.id, memory.done) { FlipCard(card, flipped, onFlip = { flipped = !flipped }, onZoom = { zoom = it }) }
            } else {
                val text = (question as? Question.Cloze)?.let { card.front.ifBlank { "Ergänze" } + "\n\n" + it.text } ?: card.front
                QuestionCardFace(if (checked) "ANTWORT" else "FRAGE", card, Modifier.fillMaxSize(), text = text, onZoom = { zoom = it }) {
                    if (checked) {
                        if (typed.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text("Deine Antwort: $typed", style = Ranzen.type.body, color = c.inkMuted, modifier = Modifier.fillMaxWidth())
                        }
                        SolutionBlock(card, onZoom = { zoom = it })
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        result?.let { VerdictLine(it, Modifier.widthIn(max = 820.dp)) }
        review?.let { r ->
            val v = when (r.grade) { SelfGrade.RIGHT -> Verdict.RIGHT; SelfGrade.PARTLY -> Verdict.PARTLY; else -> Verdict.WRONG }
            VerdictLine(AnswerResult(v), Modifier.widthIn(max = 820.dp))
            if (r.feedback.isNotBlank()) Text(r.feedback, style = Ranzen.type.body, color = c.ink, modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(bottom = 6.dp))
        }
        reviewError?.let { Text(it, style = Ranzen.type.caption, color = c.signal) }

        if (!checked) {
            when {
                question is Question.Choice -> ChoiceList(question, picked, false, { i ->
                    picked = i
                    typed = question.options[i]
                    result = AnswerResult(if (i == question.correct) Verdict.RIGHT else Verdict.WRONG)
                }, Modifier.widthIn(max = 820.dp))
                question is Question.TypeIn || question is Question.Cloze || freeAnswer -> Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                    LineField(typed, { typed = it }, if (question is Question.Cloze) "Was gehört in die Lücke?" else "Deine Antwort", singleLine = false)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuietButton("Weiß ich nicht", { if (freeAnswer) flipped = true else result = AnswerResult(Verdict.WRONG) })
                        Spacer(Modifier.weight(1f))
                        SignalButton(if (freeAnswer) "Lösung zeigen" else "Prüfen", {
                            when (question) {
                                is Question.TypeIn -> result = AnswerCheck.check(typed, card.back)
                                is Question.Cloze -> result = AnswerCheck.check(typed, question.answer)
                                else -> flipped = true
                            }
                        }, icon = StudyIcons.Flip)
                    }
                }
                else -> Box(Modifier.fillMaxWidth().height(if (phone) 72.dp else 84.dp), contentAlignment = Alignment.Center) {
                    SignalButton("Antwort zeigen", { flipped = true }, icon = StudyIcons.Flip)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                if (onExplain != null) QuietButton("Erklären lassen", { onExplain(card) }, color = c.ink)
                if (freeAnswer && typed.isNotBlank() && hasKey && review == null) {
                    QuietButton(if (reviewBusy) "Claude liest …" else "Von Claude bewerten", {
                        if (reviewBusy) return@QuietButton
                        reviewBusy = true
                        reviewError = null
                        scope.launch {
                            try {
                                review = assistant.review(card.front, card.back, typed)
                                if (review == null) reviewError = "Die Bewertung war nicht lesbar. Bewerte selbst."
                            } catch (e: AssistantException) {
                                reviewError = ClaudeApi.describe(e.error)
                            } finally { reviewBusy = false }
                        }
                    }, color = c.ink)
                }
            }
            Spacer(Modifier.height(8.dp))
            val hint = suggested(result)
                ?: review?.grade?.let { when (it) { SelfGrade.RIGHT -> Rating.GOOD; SelfGrade.PARTLY -> Rating.HARD; else -> Rating.AGAIN } }
            RatingRow(preview, hint, ::rate)
        }
        Spacer(Modifier.height(if (phone) 12.dp else 24.dp))
    }
    zoom?.let { ImageZoom(it) { zoom = null } }
}
