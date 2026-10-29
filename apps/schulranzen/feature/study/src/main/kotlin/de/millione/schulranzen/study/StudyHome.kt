package de.millione.schulranzen.study

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.Deck
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.ui.Counter
import de.millione.schulranzen.study.ui.EmptyNote
import de.millione.schulranzen.study.ui.Fmt
import de.millione.schulranzen.study.ui.IconKey
import de.millione.schulranzen.study.ui.ScreenHeader
import de.millione.schulranzen.study.ui.SectionHead
import de.millione.schulranzen.study.ui.SheetHost
import de.millione.schulranzen.study.ui.StudyIcons
import de.millione.schulranzen.study.ui.StudyPage
import de.millione.schulranzen.study.ui.latestNonNull
import kotlinx.coroutines.launch
import java.time.LocalDate

private sealed interface Pg {
    data object Home : Pg
    data class DeckView(val id: Long) : Pg
    data class Review(val deckId: Long?) : Pg
    data class ExamView(val id: Long) : Pg
}

private sealed interface HomeSheet {
    data object None : HomeSheet
    data class EditExam(val exam: Exam?) : HomeSheet
    data class EditDeck(val deck: DeckCounts?) : HomeSheet
    data class EditCard(val deckId: Long, val card: Card?) : HomeSheet
}

/**
 * The study area: cards due today, upcoming exams, decks, and the way into
 * the timetable. Two columns on a landscape tablet, one column otherwise.
 * Deck, exam and learning views open inside; back returns step by step.
 * [subjects] are the app's subjects, offered as quick picks.
 */
@Composable
fun StudyHome(onBack: () -> Unit, onOpenTimetable: () -> Unit, subjects: List<StudySubject> = emptyList()) {
    val context = LocalContext.current
    val repo = remember { StudyRepository(context.applicationContext) }
    val stack = remember { mutableStateListOf<Pg>(Pg.Home) }
    var sheet by remember { mutableStateOf<HomeSheet>(HomeSheet.None) }
    val scope = rememberCoroutineScope()

    val today by repo.today.collectAsState(initial = LocalDate.now())
    val decks by repo.decks.collectAsState(initial = emptyList())
    val exams by repo.exams.collectAsState(initial = emptyList())
    val overview by repo.overview.collectAsState(initial = StudyOverview(0, 0, 0, 0))
    val knownStudy by repo.knownSubjects.collectAsState(initial = emptyList())
    val known = remember(subjects, knownStudy) { (subjects + knownStudy).distinctBy { it.name.lowercase() } }

    fun push(p: Pg) { stack.add(p) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    BackHandler(enabled = stack.size > 1 && sheet == HomeSheet.None) { pop() }

    val page = stack.last()
    AnimatedContent(
        page,
        transitionSpec = { (fadeIn() + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.98f)) togetherWith fadeOut() },
        label = "page",
    ) { p ->
        when (p) {
            Pg.Home -> HomePage(
                today, overview, decks, exams,
                onBack = ::pop, onOpenTimetable = onOpenTimetable,
                onStudyAll = { push(Pg.Review(null)) },
                onDeck = { push(Pg.DeckView(it)) },
                onStudyDeck = { push(Pg.Review(it)) },
                onExam = { push(Pg.ExamView(it)) },
                onNewExam = { sheet = HomeSheet.EditExam(null) },
                onNewDeck = { sheet = HomeSheet.EditDeck(null) },
                repo = repo,
            )
            is Pg.DeckView -> {
                val deck = decks.firstOrNull { it.id == p.id }
                val cards by remember(p.id) { repo.cards(p.id) }.collectAsState(initial = emptyList())
                StudyPage(header = {
                    ScreenHeader(
                        deck?.name ?: "Stapel",
                        deck?.let { "${it.total} Karten · ${it.due} fällig" },
                        ::pop, accent = deck?.let { SubjectPalette.of(it.colorIndex) },
                    ) {
                        if (deck != null) EditKey { sheet = HomeSheet.EditDeck(deck) }
                        PlusKey("Neue Karte") { sheet = HomeSheet.EditCard(p.id, null) }
                        if ((deck?.due ?: 0) > 0) SignalButton("Lernen", { push(Pg.Review(p.id)) }, icon = StudyIcons.Play)
                    }
                }) {
                    DeckCards(cards, deck?.let { SubjectPalette.of(it.colorIndex) } ?: Ranzen.colors.signal, today,
                        onEdit = { sheet = HomeSheet.EditCard(p.id, it) }, onNew = { sheet = HomeSheet.EditCard(p.id, null) })
                }
            }
            is Pg.Review -> {
                val deck = p.deckId?.let { id -> decks.firstOrNull { it.id == id } }
                var progress by remember { mutableStateOf(Triple(0, 0, false)) }
                var undo by remember { mutableIntStateOf(0) }
                val (done, left, canUndo) = progress
                StudyPage(header = {
                    ScreenHeader(
                        deck?.name ?: "Alle Stapel",
                        if (left > 0) "Beantwortet $done · offen $left" else "Runde beendet",
                        ::pop, accent = deck?.let { SubjectPalette.of(it.colorIndex) },
                    ) {
                        if (canUndo) IconKey(RanzenIcons.Undo, "Letzte Antwort zurücknehmen", { undo++ })
                    }
                }) {
                    ReviewSession(repo, p.deckId, onDone = ::pop, onProgress = { d, l, u -> progress = Triple(d, l, u) }, undoSignal = undo)
                }
            }
            is Pg.ExamView -> {
                val exam = exams.firstOrNull { it.id == p.id }
                if (exam == null) {
                    LaunchedEffect(Unit) { if (stack.lastOrNull() == p) pop() }
                } else {
                    val date = LocalDate.ofEpochDay(exam.dateEpochDay)
                    StudyPage(header = {
                        ScreenHeader(
                            "${ExamKind.of(exam.kind).label} · ${exam.subject}",
                            "${date.format(Fmt.dayDate)} · ${Fmt.countdown(today, date)}",
                            ::pop, accent = SubjectPalette.of(exam.colorIndex),
                        ) { EditKey { sheet = HomeSheet.EditExam(exam) } }
                    }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val wide = maxWidth >= 840.dp
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                                ExamDetail(
                                    exam, today, deckFor(exam, decks), wide,
                                    onStudyDeck = { push(Pg.Review(it)) },
                                    onCreateDeck = {
                                        scope.launch {
                                            val id = repo.saveDeck(Deck(name = exam.subject, colorIndex = exam.colorIndex, subjectId = exam.subjectId))
                                            push(Pg.DeckView(id))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val close = { sheet = HomeSheet.None }
    val examSheet = latestNonNull(sheet as? HomeSheet.EditExam)
    val deckSheet = latestNonNull(sheet as? HomeSheet.EditDeck)
    val cardSheet = latestNonNull(sheet as? HomeSheet.EditCard)
    SheetHost(sheet is HomeSheet.EditExam, close, width = 620.dp) {
        val s = examSheet
        if (s != null) key(s) { ExamSheet(
            s.exam, today, known, onCancel = close,
            onDelete = s.exam?.let { e -> { scope.launch { repo.deleteExam(e.id) }; close() } },
            onSave = { e ->
                scope.launch {
                    val id = repo.saveExam(e)
                    if (s.exam == null) push(Pg.ExamView(id))
                }
                close()
            },
        ) }
    }
    SheetHost(sheet is HomeSheet.EditDeck, close) {
        val s = deckSheet
        if (s != null) key(s) { DeckSheet(
            s.deck, known, onCancel = close,
            onDelete = s.deck?.let { d ->
                {
                    scope.launch { repo.deleteDeck(d.id) }
                    close()
                    if (stack.lastOrNull() == Pg.DeckView(d.id)) pop()
                }
            },
            onSave = { name, color, subjectId ->
                scope.launch {
                    val old = s.deck
                    val id = repo.saveDeck(Deck(id = old?.id ?: 0, name = name, colorIndex = color, subjectId = subjectId,
                        createdAt = old?.id?.let { repo.deck(it)?.createdAt } ?: System.currentTimeMillis()))
                    if (old == null) push(Pg.DeckView(id))
                }
                close()
            },
        ) }
    }
    SheetHost(sheet is HomeSheet.EditCard, close) {
        val s = cardSheet
        if (s != null) key(s) { CardSheet(
            s.card, onCancel = close,
            onDelete = s.card?.let { card -> { scope.launch { repo.deleteCard(card) }; close() } },
            onSave = { front, back, keepOpen ->
                scope.launch {
                    if (s.card == null) repo.addCard(s.deckId, front, back) else repo.updateCardText(s.card, front, back)
                }
                if (!keepOpen) close()
            },
        ) }
    }
}

@Composable
private fun HomePage(
    today: LocalDate,
    overview: StudyOverview,
    decks: List<DeckCounts>,
    exams: List<Exam>,
    onBack: () -> Unit,
    onOpenTimetable: () -> Unit,
    onStudyAll: () -> Unit,
    onDeck: (Long) -> Unit,
    onStudyDeck: (Long) -> Unit,
    onExam: (Long) -> Unit,
    onNewExam: () -> Unit,
    onNewDeck: () -> Unit,
    repo: StudyRepository,
) {
    val c = Ranzen.colors
    StudyPage(header = {
        ScreenHeader("Lernen", today.format(Fmt.longDate), onBack) {
            IconKey(StudyIcons.Grid, "Stundenplan", onOpenTimetable)
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val todayPanel: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(26.dp)) {
                        Text("Heute", style = Ranzen.type.heading, color = c.ink)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            Counter("%03d".format(overview.dueToday), "Fällig", accent = if (overview.dueToday > 0) c.signal else null)
                            Counter("%03d".format(overview.reviewedToday), "Gelernt")
                            Counter("%02d".format(overview.streak), if (overview.streak == 1) "Tag Serie" else "Tage Serie")
                        }
                        Spacer(Modifier.height(22.dp))
                        when {
                            overview.dueToday > 0 -> SignalButton("Jetzt lernen", onStudyAll, icon = StudyIcons.Play)
                            overview.totalCards == 0 -> EmptyNote("Leg einen Stapel an und schreib die ersten Karten.")
                            else -> EmptyNote("Alles wiederholt. Morgen geht es weiter.")
                        }
                    }
                }
            }
            val deckPanel: @Composable () -> Unit = {
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
                        SectionHead("Stapel", decks.size) { QuietButton("+ Stapel", onNewDeck, color = c.ink) }
                        if (decks.isEmpty()) EmptyNote("Noch keine Stapel. Am besten einen je Fach oder Lernfeld.")
                        decks.forEach { d -> DeckRow(d, onOpen = { onDeck(d.id) }, onStudy = { onStudyDeck(d.id) }) }
                    }
                }
            }
            val examPanel: @Composable () -> Unit = {
                var showPast by remember { mutableStateOf(false) }
                val upcoming = exams.filter { it.dateEpochDay >= today.toEpochDay() }
                val past = exams.filter { it.dateEpochDay < today.toEpochDay() }.reversed()
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
                        SectionHead("Prüfungen", upcoming.size) { QuietButton("+ Prüfung", onNewExam, color = c.ink) }
                        if (upcoming.isEmpty()) EmptyNote("Keine Prüfung geplant.")
                        upcoming.forEach { e -> ExamRow(e, today) { onExam(e.id) } }
                        if (past.isNotEmpty()) {
                            QuietButton(if (showPast) "Vergangene ausblenden" else "Vergangene (${past.size})", { showPast = !showPast })
                            if (showPast) past.forEach { e -> ExamRow(e, today) { onExam(e.id) } }
                        }
                    }
                }
            }
            val timetablePanel: @Composable () -> Unit = {
                val slots by repo.todaysLessons.collectAsState(initial = emptyList())
                val minute = rememberMinuteOfDay()
                PaperPanel(Modifier.fillMaxWidth(), corner = 22.dp) {
                    Column(Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
                        SectionHead("Stundenplan", null) { QuietButton("Öffnen", onOpenTimetable, color = c.ink) }
                        val blocks = blocksOf(slots)
                        if (blocks.isEmpty()) EmptyNote("Heute kein Unterricht eingetragen.")
                        blocks.forEach { b ->
                            val now = b.start != null && b.end != null && minute in b.start until b.end
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(b.label, style = Ranzen.type.mono, color = if (now) c.signal else c.inkMuted, modifier = Modifier.width(64.dp))
                                Box(Modifier.width(4.dp).height(26.dp).background(SubjectPalette.of(b.lesson.colorIndex)))
                                Spacer(Modifier.width(12.dp))
                                Text(b.lesson.subject, style = Ranzen.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(
                                    listOfNotNull(b.start?.let(Timetable::formatTime), b.lesson.room.takeIf { it.isNotBlank() }).joinToString(" · "),
                                    style = Ranzen.type.mono, color = c.inkMuted,
                                )
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        todayPanel(); deckPanel()
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        examPanel(); timetablePanel()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    todayPanel(); examPanel(); deckPanel(); timetablePanel()
                }
            }
        }
    }
}
