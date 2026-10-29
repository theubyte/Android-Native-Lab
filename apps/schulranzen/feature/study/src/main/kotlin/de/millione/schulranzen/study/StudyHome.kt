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
import de.millione.schulranzen.core.study.PathStage
import de.millione.schulranzen.core.study.StageKind
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.study.data.StudyLink
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    data class Mock(val examId: Long) : Pg
    data class Assistant(val question: String) : Pg
}

private sealed interface HomeSheet {
    data object None : HomeSheet
    data class EditExam(val exam: Exam?) : HomeSheet
    data class EditDeck(val deck: DeckCounts?) : HomeSheet
    data class EditCard(val deckId: Long, val card: Card?) : HomeSheet
    data class QuickCard(val front: String, val back: String) : HomeSheet
    data class Suggest(val text: String, val deckId: Long?, val examId: Long? = null) : HomeSheet
    data object Settings : HomeSheet
}

/**
 * The study area: cards due today, upcoming exams, decks, and the way into
 * the timetable. Two columns on a landscape tablet, one column otherwise.
 * Deck, exam, mock exam, assistant and learning views open inside; back
 * returns step by step. [subjects] are the app's subjects, offered as quick
 * picks. [onOpenLink] is called when the learner taps marked exam material
 * (a notebook or book page); the host opens that page.
 */
@Composable
fun StudyHome(
    onBack: () -> Unit,
    onOpenTimetable: () -> Unit,
    subjects: List<StudySubject> = emptyList(),
    onOpenLink: (StudyLink) -> Unit = {},
) {
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
    val bookmarks by repo.bookmarks.collectAsState(initial = emptyList())
    val nextStep by repo.nextStep.collectAsState(initial = null)

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
                bookmarks = bookmarks,
                nextStep = nextStep,
                onOpenLink = onOpenLink,
                onAssistant = { push(Pg.Assistant("")) },
                onSettings = { sheet = HomeSheet.Settings },
                onSuggest = { sheet = HomeSheet.Suggest("", null) },
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
                        IconKey(StudyIcons.Wand, "Karten aus Text", { sheet = HomeSheet.Suggest("", p.id) })
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
                    ReviewSession(
                        repo, p.deckId, onDone = ::pop, onProgress = { d, l, u -> progress = Triple(d, l, u) }, undoSignal = undo,
                        onExplain = { push(Pg.Assistant(StudyAssistant.cardQuestion(it.front, it.back))) },
                    )
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
                        ) {
                            EditKey { sheet = HomeSheet.EditExam(exam) }
                            IconKey(StudyIcons.Test, "Probeprüfung", { push(Pg.Mock(exam.id)) })
                        }
                    }) {
                        val links by remember(exam.id) { repo.links(exam.id) }.collectAsState(initial = emptyList())
                        val path by remember(exam) { repo.path(exam) }.collectAsState(initial = null)
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
                                    links = links, path = path, repo = repo, onOpenLink = onOpenLink,
                                    onPathAction = { kind ->
                                        when (kind) {
                                            StageKind.CARDS -> scope.launch { push(Pg.DeckView(repo.deckForExam(exam.id))) }
                                            StageKind.LEARN, StageKind.FINAL -> deckFor(exam, decks)?.let { push(Pg.Review(it.id)) }
                                            StageKind.MOCK -> push(Pg.Mock(exam.id))
                                            StageKind.MATERIAL -> {}
                                        }
                                    },
                                    onSuggest = { sheet = HomeSheet.Suggest("", null, exam.id) },
                                )
                            }
                        }
                    }
                }
            }
            is Pg.Mock -> {
                val exam = exams.firstOrNull { it.id == p.examId }
                if (exam == null) LaunchedEffect(Unit) { if (stack.lastOrNull() == p) pop() }
                else MockExamPage(repo, exam, decks, onBack = ::pop, onStudy = { id -> stack.removeAt(stack.lastIndex); push(Pg.Review(id)) })
            }
            is Pg.Assistant -> StudyPage(header = {
                ScreenHeader("Erklärhilfe", "Claude · für Schule und Ausbildung", ::pop) {
                    IconKey(RanzenIcons.Settings, "Einstellungen", { sheet = HomeSheet.Settings })
                }
            }) {
                ExplainContent(
                    p.question,
                    onSaveCard = { q, a -> sheet = HomeSheet.QuickCard(q, a) },
                    onSuggest = { sheet = HomeSheet.Suggest(it, null) },
                    onSettings = { sheet = HomeSheet.Settings },
                    modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                )
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
            onSave = { front, back, frontImage, backImage, keepOpen ->
                scope.launch {
                    if (s.card == null) repo.addCard(s.deckId, front, back, frontImage, backImage)
                    else repo.updateCard(s.card, front, back, frontImage, backImage)
                }
                if (!keepOpen) close()
            },
        ) }
    }
    val quickSheet = latestNonNull(sheet as? HomeSheet.QuickCard)
    SheetHost(sheet is HomeSheet.QuickCard, close, width = 680.dp) {
        val s = quickSheet
        if (s != null) key(s) { QuickCardContent(s.front, s.back, onDone = close, onCancel = close) }
    }
    val suggestSheet = latestNonNull(sheet as? HomeSheet.Suggest)
    SheetHost(sheet is HomeSheet.Suggest, close, width = 760.dp) {
        val s = suggestSheet
        if (s != null) key(s) { SuggestCardsContent(s.text, preferDeck = s.deckId, preferExam = s.examId, onDone = close, onCancel = close) }
    }
    SheetHost(sheet is HomeSheet.Settings, close, width = 640.dp) {
        if (sheet is HomeSheet.Settings) AssistantSettingsContent(onClose = close)
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
    bookmarks: List<StudyLink>,
    nextStep: Pair<Exam, PathStage>?,
    onOpenLink: (StudyLink) -> Unit,
    onAssistant: () -> Unit,
    onSettings: () -> Unit,
    onSuggest: () -> Unit,
) {
    val c = Ranzen.colors
    StudyPage(header = {
        ScreenHeader("Lernen", today.format(Fmt.longDate), onBack) {
            IconKey(StudyIcons.Assistant, "Erklärhilfe", onAssistant)
            IconKey(StudyIcons.Wand, "Karten aus Text", onSuggest)
            IconKey(StudyIcons.Grid, "Stundenplan", onOpenTimetable)
            IconKey(RanzenIcons.Settings, "Einstellungen", onSettings)
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
                        nextStep?.let { (exam, stage) ->
                            Spacer(Modifier.height(18.dp))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).clickable { onExam(exam.id) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RanzenIcon(StudyIcons.Path, tint = SubjectPalette.of(exam.colorIndex))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("NÄCHSTER SCHRITT · ${exam.subject.uppercase()}", style = Ranzen.type.caption, color = c.inkMuted, maxLines = 1)
                                    Text(nextStepText(stage), style = Ranzen.type.body, color = c.ink, maxLines = 2)
                                }
                                ChevronIcon()
                            }
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
                        if (bookmarks.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            SectionHead("Merkliste", bookmarks.size)
                            BookmarkSection(bookmarks, upcoming, repo, onOpenLink)
                        }
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
