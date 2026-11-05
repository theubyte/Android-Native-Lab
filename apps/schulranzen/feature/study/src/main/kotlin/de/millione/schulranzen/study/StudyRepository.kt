package de.millione.schulranzen.study

import android.content.Context
import androidx.room.withTransaction
import de.millione.schulranzen.core.study.Attempt
import de.millione.schulranzen.core.study.CardSchedule
import de.millione.schulranzen.core.study.CsvCard
import de.millione.schulranzen.core.study.ErrorBook
import de.millione.schulranzen.core.study.ErrorEntry
import de.millione.schulranzen.core.study.MockPoint
import de.millione.schulranzen.core.study.PlanCard
import de.millione.schulranzen.core.study.PlanExam
import de.millione.schulranzen.core.study.QuizCard
import de.millione.schulranzen.core.study.Readiness
import de.millione.schulranzen.core.study.ReadinessForecast
import de.millione.schulranzen.core.study.ReadinessInput
import de.millione.schulranzen.core.study.SelfGrade
import de.millione.schulranzen.core.study.StatAnswer
import de.millione.schulranzen.core.study.StudyStatistics
import de.millione.schulranzen.core.study.StudySummary
import de.millione.schulranzen.core.study.TodayPlan
import de.millione.schulranzen.core.study.TodayPlanner
import de.millione.schulranzen.core.study.TopicMatcher
import de.millione.schulranzen.core.study.CardSuggester
import de.millione.schulranzen.core.study.CardSuggestion
import de.millione.schulranzen.core.study.LearningPath
import de.millione.schulranzen.core.study.MockExam
import de.millione.schulranzen.core.study.MockResult
import de.millione.schulranzen.core.study.PathInput
import de.millione.schulranzen.core.study.PathStage
import de.millione.schulranzen.core.study.PathState
import de.millione.schulranzen.core.study.PeriodTime
import de.millione.schulranzen.core.study.Rating
import de.millione.schulranzen.core.study.Repetition
import de.millione.schulranzen.core.study.StudyStats
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.core.study.WeekCycle
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.Deck
import de.millione.schulranzen.study.data.DeckCounts
import de.millione.schulranzen.study.data.DeckProgress
import de.millione.schulranzen.study.data.FocusSession
import de.millione.schulranzen.study.data.MockAnswerRow
import de.millione.schulranzen.study.data.MockRun
import de.millione.schulranzen.study.data.StudyLink
import de.millione.schulranzen.study.data.Exam
import de.millione.schulranzen.study.data.Lesson
import de.millione.schulranzen.study.data.PeriodRow
import de.millione.schulranzen.study.data.Review
import de.millione.schulranzen.study.data.SettingRow
import de.millione.schulranzen.study.data.StudyDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Kinds of exams a trainee meets; [label] is shown in the UI. */
enum class ExamKind(val label: String) {
    KLASSENARBEIT("Klassenarbeit"),
    TEST("Test"),
    LERNFELD("Lernfeldarbeit"),
    REFERAT("Referat"),
    PRAXIS("Praktische Prüfung"),
    GP1("Gesellenprüfung Teil 1"),
    GP2("Gesellenprüfung Teil 2"),
    SONSTIGE("Sonstige");

    companion object {
        fun of(name: String?): ExamKind = entries.firstOrNull { it.name == name } ?: SONSTIGE
    }
}

/** A subject the host app knows, offered when choosing the subject of a lesson, exam or deck. */
data class StudySubject(val id: Long?, val name: String, val colorIndex: Int)

/** Options of the timetable. [anchorEpochDay] is a day in an A week (only used with [abWeeks]). */
data class TimetableSettings(
    val saturday: Boolean = false,
    val abWeeks: Boolean = false,
    val anchorEpochDay: Long? = null,
) {
    val anchor: LocalDate? get() = anchorEpochDay?.let(LocalDate::ofEpochDay)
    val days: Int get() = if (saturday) 6 else 5
}

/** A lesson of a concrete day with its times. */
data class LessonSlot(val lesson: Lesson, val period: PeriodTime?)

/** A card on the error list with what the list shows about it. */
data class ErrorItem(val card: Card, val entry: ErrorEntry, val deckName: String, val colorIndex: Int)

/** What the plan page needs: the plan and the things its ids point at. */
data class TodayBundle(val plan: TodayPlan, val exams: Map<Long, Exam>, val links: Map<Long, StudyLink>)

/** One graded question of a mock exam, ready to be stored. */
data class MockAnswerDraft(
    val cardId: Long?,
    val topic: String,
    val type: String,
    val question: String,
    val solution: String,
    val given: String,
    val grade: SelfGrade,
)

data class ImportResult(val added: Int, val duplicates: Int)

/** What is stored of one content pack, for planning an import. */
data class StoredPack(val decks: List<de.millione.schulranzen.core.study.StoredPackDeck>, val cards: List<de.millione.schulranzen.core.study.StoredPackCard>)

/** What a reminder needs to know at the moment it fires. */
data class ReminderData(
    val dueReviews: Int,
    val fresh: Int,
    val reviewedToday: Int,
    val exams: List<Pair<Exam, Int?>>,
)

internal fun Card.quiz() = QuizCard(id, front, back, frontImage != null || backImage != null)

/** Numbers for the overview: due today, reviews done today, day streak. */
data class StudyOverview(val dueToday: Int, val reviewedToday: Int, val streak: Int, val totalCards: Int)

/**
 * Everything the study feature stores, in its own database `study.db`.
 * Other features use the flows (next exams, due cards, today's lessons) and
 * [addCard] to put cards into a deck, e.g. from a notebook or a book.
 *
 * Flows that depend on "today" re-evaluate when the date changes, so a screen
 * left open over midnight still shows the right day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyRepository(context: Context) {
    private val app = context.applicationContext
    private val db = StudyDatabase.get(context)
    private val dao = db.dao()

    /** The local calendar day, checked every half minute. */
    val today: Flow<LocalDate> = flow {
        while (true) { emit(LocalDate.now()); delay(30_000) }
    }.distinctUntilChanged()

    // ---- Timetable --------------------------------------------------------

    val periods: Flow<List<PeriodTime>> = dao.periods().map { rows ->
        if (rows.isEmpty()) Timetable.DEFAULT_PERIODS
        else rows.map { PeriodTime(it.number, it.startMinute, it.endMinute) }
    }

    val lessons: Flow<List<Lesson>> = dao.lessons()

    val settings: Flow<TimetableSettings> = dao.settings().map { rows ->
        val m = rows.associate { it.key to it.value }
        TimetableSettings(
            saturday = m["saturday"] == "1",
            abWeeks = m["abWeeks"] == "1",
            anchorEpochDay = m["anchor"]?.toLongOrNull(),
        )
    }

    /** Today's lessons in order, with times; respects A/B weeks. Empty on weekends without lessons. */
    val todaysLessons: Flow<List<LessonSlot>> = combine(today, lessons, periods, settings) { day, all, ps, set ->
        lessonsOn(day, all, ps, set)
    }

    suspend fun savePeriods(periods: List<PeriodTime>) = db.withTransaction {
        dao.clearPeriods()
        dao.insertPeriods(periods.sortedBy { it.startMinute }.mapIndexed { i, p -> PeriodRow(i + 1, p.startMinute, p.endMinute) })
        dao.deleteLessonsAfter(periods.size)
    }

    suspend fun saveLesson(lesson: Lesson): Long {
        val id = dao.upsertLesson(lesson)
        return if (lesson.id != 0L) lesson.id else id
    }

    suspend fun deleteLesson(id: Long) = dao.deleteLesson(id)

    suspend fun saveSettings(s: TimetableSettings) = db.withTransaction {
        dao.putSetting(SettingRow("saturday", if (s.saturday) "1" else "0"))
        dao.putSetting(SettingRow("abWeeks", if (s.abWeeks) "1" else "0"))
        dao.putSetting(SettingRow("anchor", s.anchorEpochDay?.toString() ?: ""))
    }

    // ---- Exams ------------------------------------------------------------

    val exams: Flow<List<Exam>> = dao.exams()

    /** Exams from today on, nearest first. */
    val nextExams: Flow<List<Exam>> = today.flatMapLatest { dao.examsFrom(it.toEpochDay()) }

    suspend fun saveExam(exam: Exam): Long {
        val id = dao.upsertExam(exam)
        return if (exam.id != 0L) exam.id else id
    }

    suspend fun deleteExam(id: Long) = dao.deleteExam(id)

    // ---- Cards ------------------------------------------------------------

    val decks: Flow<List<DeckCounts>> = today.flatMapLatest { dao.deckCounts(it.toEpochDay()) }

    /** Cards due today over all decks. */
    val dueCount: Flow<Int> = today.flatMapLatest { dao.dueCount(it.toEpochDay()) }

    val overview: Flow<StudyOverview> = today.flatMapLatest { day ->
        val d = day.toEpochDay()
        combine(dao.dueCount(d), dao.reviewsOn(d), dao.reviewDays(d - 400), dao.cardCount()) { due, done, days, total ->
            StudyOverview(due, done, StudyStats.streak(days.toSet(), day), total)
        }
    }

    fun cards(deckId: Long): Flow<List<Card>> = dao.cards(deckId)

    suspend fun deck(id: Long): Deck? = dao.deck(id)

    suspend fun saveDeck(deck: Deck): Long {
        val id = dao.upsertDeck(deck)
        return if (deck.id != 0L) deck.id else id
    }

    suspend fun deleteDeck(id: Long) {
        val images = dao.imagesOfDeck(id)
        dao.deleteDeck(id)
        StudyImages.delete(app, images)
    }

    /**
     * Adds a card to the deck called [deckName] (case-insensitive) and creates
     * the deck if needed. Meant for other features: "make a card from this".
     * Returns the new card's id.
     */
    suspend fun addCard(deckName: String, front: String, back: String): Long = db.withTransaction {
        val name = deckName.trim().ifEmpty { "Allgemein" }
        val deck = dao.deckByName(name) ?: Deck(name = name, colorIndex = dao.deckCount()).let { it.copy(id = dao.upsertDeck(it)) }
        addCard(deck.id, front, back)
    }

    /**
     * Adds a card. [frontImage]/[backImage] are paths relative to
     * `filesDir/study` as returned by [StudyImages.import].
     */
    suspend fun addCard(deckId: Long, front: String, back: String, frontImage: String? = null, backImage: String? = null): Long =
        dao.insertCard(
            Card(deckId = deckId, front = front.trim(), back = back.trim(), frontImage = frontImage, backImage = backImage, dueEpochDay = LocalDate.now().toEpochDay()),
        )

    /** Adds several cards at once: all of them or, on failure, none. */
    suspend fun addCards(deckId: Long, cards: List<Pair<String, String>>) = db.withTransaction {
        cards.forEach { (f, b) -> addCard(deckId, f, b) }
    }

    suspend fun updateCardText(card: Card, front: String, back: String) =
        // From the database: the caller's copy may hold an old schedule.
        dao.updateCard((dao.card(card.id) ?: card).copy(front = front.trim(), back = back.trim()))

    /** Updates text and pictures; pictures that were replaced or removed are deleted. */
    suspend fun updateCard(card: Card, front: String, back: String, frontImage: String?, backImage: String?) {
        val fresh = dao.card(card.id) ?: card
        dao.updateCard(fresh.copy(front = front.trim(), back = back.trim(), frontImage = frontImage, backImage = backImage))
        StudyImages.delete(app, listOf(fresh.frontImage, fresh.backImage).filter { it != null && it != frontImage && it != backImage })
    }

    suspend fun deleteCard(card: Card) {
        dao.deleteCard(card)
        StudyImages.delete(app, listOf(card.frontImage, card.backImage))
    }

    /**
     * Adds a card with an optional picture file (e.g. a cut-out from a
     * notebook or book page) on the front or back. The file is copied, the
     * caller may delete it afterwards. Returns the new card's id.
     */
    suspend fun addCard(deckId: Long, front: String, back: String, image: java.io.File?, imageOnBack: Boolean = false): Long {
        val rel = image?.let { StudyImages.import(app, it) }
        return addCard(deckId, front, back, if (imageOnBack) null else rel, if (imageOnBack) rel else null)
    }

    /** The deck that belongs to an exam (same subject); created if there is none yet. */
    suspend fun deckForExam(examId: Long): Long = db.withTransaction {
        val exam = dao.exam(examId) ?: error("Prüfung fehlt")
        val all = dao.deckCountsNow(LocalDate.now().toEpochDay())
        deckFor(exam, all)?.id ?: dao.upsertDeck(Deck(name = exam.subject, colorIndex = exam.colorIndex, subjectId = exam.subjectId))
    }

    /** The deck that belongs to an exam, or null; nothing is created. */
    suspend fun existingDeckForExam(exam: Exam): Long? = deckFor(exam, dao.deckCountsNow(LocalDate.now().toEpochDay()))?.id

    // ---- Exam links (Prüfungsstoff) and the bookmark list ------------------

    /**
     * Marks a notebook or book place as material for an exam, or puts it
     * on the bookmark list ("Merkliste") when [examId] is null. Returns the
     * link's id.
     */
    suspend fun addLink(examId: Long?, link: StudyLinkDraft, note: String = ""): Long =
        dao.insertLink(
            StudyLink(
                examId = examId, kind = link.kind.name, targetId = link.targetId, page = link.page,
                rectX = link.rect?.x, rectY = link.rect?.y, rectW = link.rect?.w, rectH = link.rect?.h,
                label = link.label.trim().ifEmpty { "${link.kind.label} · S. ${link.page + 1}" }, note = note.trim(),
                pageId = link.pageId,
            ),
        )

    /** Material of one exam, oldest first. */
    fun links(examId: Long): Flow<List<StudyLink>> = dao.links(examId)

    /** Links without an exam, newest first. */
    val bookmarks: Flow<List<StudyLink>> = dao.bookmarks()

    /** All links, newest first. */
    val allLinks: Flow<List<StudyLink>> = dao.allLinks()

    /** Links on one notebook or book, e.g. to draw markers on its pages. */
    fun linksOn(kind: StudyLinkKind, targetId: String): Flow<List<StudyLink>> = dao.linksOn(kind.name, targetId)

    suspend fun setLinkDone(link: StudyLink, done: Boolean) =
        dao.updateLink(link.copy(doneAt = if (done) System.currentTimeMillis() else null))

    suspend fun moveLink(link: StudyLink, examId: Long?) = dao.updateLink(link.copy(examId = examId))

    suspend fun deleteLink(id: Long) = dao.deleteLink(id)

    /** Remembers where a moved page is now, so the next list shows it right. */
    suspend fun relocateLink(link: StudyLink, targetId: String, page: Int) = dao.relocateLink(link.id, targetId, page)

    // ---- Mock exams --------------------------------------------------------

    /** Cards of the given decks, for building a mock exam. */
    suspend fun cardsOf(deckIds: Collection<Long>): List<Card> = if (deckIds.isEmpty()) emptyList() else dao.cardsOf(deckIds.toList())

    /** Questions for a mock exam: [count] cards of [deckIds], weak ones more likely. */
    suspend fun mockQuestions(deckIds: Collection<Long>, count: Int): List<Card> =
        MockExam.pick(cardsOf(deckIds), count, { it.schedule })

    /**
     * Stores a finished mock exam with its answers and pulls the cards answered
     * wrong (or left open) forward, so today's review starts with them.
     */
    suspend fun saveMockRun(examId: Long, result: MockResult, answers: List<MockAnswerDraft>, seconds: Int, limitSeconds: Int?): Long = db.withTransaction {
        val run = dao.insertMockRun(
            MockRun(
                examId = examId, asked = result.asked, points = result.points, percent = result.percent, grade = result.grade,
                weakTopics = result.weakTopics.joinToString("\n") { it.topic }, seconds = seconds, limitSeconds = limitSeconds,
            ),
        )
        dao.insertMockAnswers(
            answers.mapIndexed { i, a ->
                MockAnswerRow(
                    runId = run, cardId = a.cardId, position = i, topic = a.topic, type = a.type,
                    question = a.question, solution = a.solution, given = a.given, grade = a.grade.name,
                )
            },
        )
        val weak = answers.filter { MockExam.needsRelearning(it.grade) }.mapNotNull { it.cardId }
        if (weak.isNotEmpty()) dao.pullForward(weak, LocalDate.now().toEpochDay())
        run
    }

    suspend fun mockAnswers(runId: Long): List<MockAnswerRow> = dao.mockAnswers(runId)

    suspend fun cardsById(ids: Collection<Long>): List<Card> {
        if (ids.isEmpty()) return emptyList()
        // Long sessions must not hit SQLite's limit on bound variables.
        val found = ids.distinct().chunked(500).flatMap { dao.cardsById(it) }.associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    suspend fun deckCards(deckId: Long): List<Card> = dao.cardsOfDeck(deckId)

    fun mockRuns(examId: Long): Flow<List<MockRun>> = dao.mockRuns(examId)

    // ---- Learning path -----------------------------------------------------

    /** The learning path of an exam, kept up to date with links, cards and mock exams. */
    fun path(exam: Exam): Flow<PathState> = today.flatMapLatest { day ->
        val progress: Flow<DeckProgress> = decks.map { deckFor(exam, it)?.id }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(DeckProgress(0, 0, 0, 0)) else dao.deckProgress(id, day.toEpochDay())
        }
        combine(dao.links(exam.id), progress, dao.mockRuns(exam.id)) { links, p, runs ->
            LearningPath.build(
                PathInput(
                    linksTotal = links.size,
                    linksDone = links.count { it.doneAt != null },
                    topics = exam.topics.lines().count { it.isNotBlank() },
                    cards = p.total, cardsSeen = p.seen, cardsSolid = p.solid, cardsDue = p.due,
                    mockBestPercent = runs.maxOfOrNull { it.percent },
                    daysLeft = ChronoUnit.DAYS.between(day, LocalDate.ofEpochDay(exam.dateEpochDay)),
                ),
            )
        }
    }

    /** The next step on the path of the nearest upcoming exam, for the home screen. */
    val nextStep: Flow<Pair<Exam, PathStage>?> = nextExams.flatMapLatest { list ->
        val exam = list.firstOrNull()
        if (exam == null) flowOf(null) else path(exam).map { p -> p.next?.let { exam to it } }
    }

    // ---- Answer log, error list -----------------------------------------------

    private fun dayOf(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private val attempts: Flow<List<Attempt>> = combine(dao.reviewLog(), dao.mockLog()) { reviews, mocks ->
        reviews.map { Attempt(it.cardId, it.epochDay, it.at, ErrorBook.isRight(Rating.entries.getOrElse(it.rating) { Rating.GOOD })) } +
            mocks.map { Attempt(it.cardId, dayOf(it.at), it.at, it.grade == SelfGrade.RIGHT.name) }
    }

    /** The error list: built from the log, so it needs no care. */
    val errorBook: Flow<List<ErrorItem>> = combine(attempts, dao.allCards(), dao.deckCounts(0)) { log, cards, ds ->
        val byId = cards.associateBy { it.id }
        val deckOf = ds.associateBy { it.id }
        ErrorBook.build(log).mapNotNull { e ->
            val card = byId[e.cardId] ?: return@mapNotNull null
            val deck = deckOf[card.deckId]
            ErrorItem(card, e, deck?.name.orEmpty(), deck?.colorIndex ?: 0)
        }
    }

    // ---- Today's plan ----------------------------------------------------------

    val dailyMinutes: Flow<Int> = dao.settings().map { rows ->
        rows.firstOrNull { it.key == "dailyMinutes" }?.value?.toIntOrNull() ?: TodayPlanner.DEFAULT_MINUTES
    }

    suspend fun saveDailyMinutes(minutes: Int) = dao.putSetting(SettingRow("dailyMinutes", minutes.toString()))

    suspend fun studyMode(): String? = dao.settings().first().firstOrNull { it.key == "studyMode" }?.value

    suspend fun saveStudyMode(name: String) = dao.putSetting(SettingRow("studyMode", name))

    /** "Jetzt lernen": one session for today over all coming exams. [budget] overrides the daily time. */
    suspend fun todayPlan(budget: Int? = null): TodayBundle {
        val day = LocalDate.now()
        val d = day.toEpochDay()
        val decksNow = dao.deckCountsNow(d)
        val cards = dao.allCards().first()
        val errorIds = ErrorBook.build(attempts.first()).map { it.cardId }.toSet()
        val firstAnswer = HashMap<Long, Long>()
        // Only learning introduces a card: a mock exam leaves it new, so it must not use up the share.
        for (r in dao.reviewLog().first()) firstAnswer.merge(r.cardId, r.epochDay, ::minOf)
        fun plan(c: Card) = PlanCard(c.id, c.schedule, c.id in errorIds || c.lapses >= 2, firstAnswer[c.id] == d)
        val byDeck = cards.groupBy { it.deckId }
        val exams = dao.examsFromNow(d)
        val usedDecks = HashSet<Long>()
        val links = HashMap<Long, StudyLink>()
        val planExams = exams.map { e ->
            val examDecks = decksFor(e, decksNow).map { it.id }
            usedDecks += examDecks
            val open = dao.linksNow(e.id).filter { it.doneAt == null }
            open.forEach { links[it.id] = it }
            val lastMock = dao.mockRunsNow(e.id).firstOrNull()
            PlanExam(
                id = e.id, date = LocalDate.ofEpochDay(e.dateEpochDay), weight = TodayPlanner.weightOf(e.kind),
                cards = examDecks.flatMap { byDeck[it].orEmpty() }.map(::plan),
                openLinkIds = open.map { it.id },
                daysSinceMock = lastMock?.let { ChronoUnit.DAYS.between(LocalDate.ofEpochDay(dayOf(it.at)), day) },
            )
        }
        val other = cards.filter { it.deckId !in usedDecks && it.dueEpochDay <= d }.map(::plan)
        val minutes = budget ?: dailyMinutes.first()
        return TodayBundle(TodayPlanner.build(planExams, other, day, minutes), exams.associateBy { it.id }, links)
    }

    // ---- Readiness ---------------------------------------------------------------

    private fun readinessOf(exam: Exam, deckIds: List<Long>, allCards: List<Card>, links: List<StudyLink>, runs: List<MockRun>, day: LocalDate): Readiness {
        val cards = allCards.filter { it.deckId in deckIds }
        val topics = exam.topics.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return ReadinessForecast.estimate(
            ReadinessInput(
                cards = cards.map { it.schedule },
                cardTopics = if (topics.isEmpty()) emptyList() else cards.map { c ->
                    // A topic named by a pack is more reliable than matching words.
                    topics.firstOrNull { it.equals(c.topic, ignoreCase = true) } ?: TopicMatcher.topicOf("${c.topic.orEmpty()} ${c.front} ${c.back}", topics, "")
                },
                topics = topics,
                mocks = runs.map { MockPoint(it.percent, ChronoUnit.DAYS.between(LocalDate.ofEpochDay(dayOf(it.at)), day).coerceAtLeast(0), it.asked) },
                linksTotal = links.size,
                linksDone = links.count { it.doneAt != null },
                cardTarget = LearningPath.cardTarget(topics.size, links.size),
                today = day,
                examDate = LocalDate.ofEpochDay(exam.dateEpochDay),
            ),
        )
    }

    /** Forecast for one exam, kept up to date with cards, links and mock exams. */
    fun readiness(exam: Exam): Flow<Readiness> = today.flatMapLatest { day ->
        combine(dao.allCards(), dao.deckCounts(day.toEpochDay()), dao.links(exam.id), dao.mockRuns(exam.id)) { cards, ds, links, runs ->
            readinessOf(exam, decksFor(exam, ds).map { it.id }, cards, links, runs, day)
        }
    }

    // ---- Statistics, focus --------------------------------------------------------

    fun statistics(spanDays: Int): Flow<StudySummary> = today.flatMapLatest { day ->
        combine(dao.reviewLog(), dao.focusByDay()) { log, focus ->
            StudyStatistics.summary(
                log.map { StatAnswer(it.epochDay, it.deckId, ErrorBook.isRight(Rating.entries.getOrElse(it.rating) { Rating.GOOD })) },
                focus.associate { it.epochDay to it.minutes }, day, spanDays,
            )
        }
    }

    suspend fun addFocus(minutes: Int) {
        if (minutes > 0) dao.insertFocus(FocusSession(epochDay = LocalDate.now().toEpochDay(), minutes = minutes))
    }

    // ---- Import and export -----------------------------------------------------------

    /**
     * Adds [cards] as new cards, each to its own named deck if the file says so, else to
     * [deckId]. A front already in the deck (or twice in the file) is skipped: importing
     * the same file again must not double a deck or touch what has been learnt.
     */
    suspend fun importCards(deckId: Long, cards: List<CsvCard>): ImportResult = db.withTransaction {
        val today = LocalDate.now().toEpochDay()
        val seen = HashMap<Long, MutableSet<String>>()
        var added = 0
        var dupes = 0
        for (c in cards) {
            val target = c.deck?.let { name ->
                (dao.deckByName(name) ?: Deck(name = name, colorIndex = dao.deckCount()).let { it.copy(id = dao.upsertDeck(it)) }).id
            } ?: deckId
            val fronts = seen.getOrPut(target) { dao.frontsOf(target).map { it.trim().lowercase() }.toMutableSet() }
            if (!fronts.add(c.front.trim().lowercase())) { dupes++; continue }
            dao.insertCard(Card(deckId = target, front = c.front.trim(), back = c.back.trim(), dueEpochDay = today))
            added++
        }
        ImportResult(added, dupes)
    }

    // ---- Content packs -----------------------------------------------------------------

    val contentPacks: Flow<List<de.millione.schulranzen.study.data.ContentPackRow>> = dao.contentPacks()

    suspend fun storedPack(packId: String): StoredPack = StoredPack(
        dao.decksOfPack(packId).map { de.millione.schulranzen.core.study.StoredPackDeck(it.id, it.packDeckKey.orEmpty(), it.fach.orEmpty()) },
        dao.cardsOfPack(packId).map {
            de.millione.schulranzen.core.study.StoredPackCard(
                it.id, it.packCardKey.orEmpty(), it.front, it.back, it.packFront.orEmpty(), it.packBack.orEmpty(),
                it.verified, it.topic.orEmpty(), it.source, it.lastReviewEpochDay != null,
            )
        },
    )

    /**
     * Carries out a plan in one transaction: all of it or nothing. Pictures are written first
     * and removed again if it fails. Known cards are read fresh and only their text and
     * metadata change; schedule fields are not touched.
     */
    suspend fun applyPack(context: Context, pack: de.millione.schulranzen.core.study.ContentPack, plan: de.millione.schulranzen.core.study.PackImportPlan) {
        val written = mutableListOf<String>()
        suspend fun image(dataUrl: String?): String? {
            val (ext, bytes) = dataUrl?.let { de.millione.schulranzen.core.study.ContentPacks.decodeImage(it) } ?: return null
            val tmp = java.io.File.createTempFile("pack", ".$ext", context.cacheDir)
            try {
                tmp.writeBytes(bytes)
                return StudyImages.import(context, tmp).also { written += it }
            } finally { tmp.delete() }
        }
        val now = System.currentTimeMillis()
        try {
            val images = plan.newCards.associate { n -> n.key to (image(n.card.vorneBild) to image(n.card.hintenBild)) }
            val dropped = mutableListOf<String?>()
            db.withTransaction {
                val today = LocalDate.now().toEpochDay()
                var color = dao.deckCount()
                val deckIds = HashMap<String, Long>()
                for (put in plan.decks) {
                    val id = put.existingId
                    if (id != null) {
                        dao.deck(id)?.let { dao.upsertDeck(it.copy(name = put.deck.name, fach = put.fach.ifEmpty { null })) }
                        deckIds[put.deck.id] = id
                    } else {
                        deckIds[put.deck.id] = dao.upsertDeck(
                            Deck(name = put.deck.name, colorIndex = color++, packId = pack.id, packDeckKey = put.deck.id, fach = put.fach.ifEmpty { null }, createdAt = now),
                        )
                    }
                }
                plan.newCards.forEachIndexed { i, n ->
                    val k = n.card
                    val (front, back) = images[n.key] ?: (null to null)
                    dao.insertCard(
                        Card(
                            deckId = deckIds.getValue(n.deckKey), front = k.vorne, back = k.hinten, frontImage = front, backImage = back,
                            dueEpochDay = today, createdAt = now + i,
                            topic = k.thema.ifEmpty { null }, sourceBook = k.quelle.buch.ifEmpty { null }, sourcePage = k.quelle.seite.ifEmpty { null },
                            verified = k.geprueft, packId = pack.id, packCardKey = n.key, packFront = k.vorne, packBack = k.hinten,
                        ),
                    )
                }
                for (u in plan.updates) {
                    val c = dao.card(u.id) ?: continue
                    dao.updateCard(
                        c.copy(
                            front = u.front, back = u.back, verified = u.verified,
                            topic = u.topic.ifEmpty { null }, sourceBook = u.source.buch.ifEmpty { null }, sourcePage = u.source.seite.ifEmpty { null },
                            packFront = u.packFront, packBack = u.packBack,
                        ),
                    )
                }
                for (id in plan.detach) {
                    val c = dao.card(id) ?: continue
                    dao.updateCard(c.copy(packId = null, packCardKey = null, packFront = null, packBack = null))
                }
                for (id in plan.deletes) {
                    val c = dao.card(id) ?: continue
                    dao.deleteCard(c)
                    dropped += c.frontImage
                    dropped += c.backImage
                }
                dao.upsertContentPack(
                    de.millione.schulranzen.study.data.ContentPackRow(pack.id, pack.titel, pack.version, pack.stand, pack.autor, pack.beispiel, now),
                )
            }
            StudyImages.delete(app, dropped)
        } catch (e: Exception) {
            StudyImages.delete(app, written)
            throw e
        }
    }

    /** The learner's own check of a card from a pack; a later import can only add to it. */
    suspend fun setVerified(card: Card, verified: Boolean) {
        dao.updateCard((dao.card(card.id) ?: card).copy(verified = verified))
    }

    // ---- Reminders --------------------------------------------------------------------

    suspend fun reminderData(): ReminderData {
        val d = LocalDate.now()
        val day = d.toEpochDay()
        val decksNow = dao.deckCountsNow(day)
        val cards = dao.allCards().first()
        val exams = dao.examsFromNow(day).map { e ->
            e to readinessOf(e, decksFor(e, decksNow).map { it.id }, cards, dao.linksNow(e.id), dao.mockRunsNow(e.id), d).percent
        }
        return ReminderData(dao.dueReviewsNow(day), dao.freshNow(), dao.reviewsOnNow(day), exams)
    }

    // ---- Card suggestions --------------------------------------------------

    /**
     * Offline card proposals from [text] (notes, a book page, an
     * explanation). Quick; for very long texts call it off the main thread.
     * With a key, [StudyAssistant.suggestCards] gives better ones.
     */
    fun suggestCards(text: String): List<CardSuggestion> = CardSuggester.suggest(text)

    /** Exam runs over all exams, newest first. */
    val allMockRuns: Flow<List<MockRun>> = dao.allMockRuns()

    /** Due cards of one deck (or all), in learning order: overdue first, new last. */
    suspend fun studyQueue(deckId: Long?): List<Card> {
        val today = LocalDate.now()
        return Repetition.queue(dao.dueCards(today.toEpochDay(), deckId), today) { it.schedule }
    }

    /** The exam that should cap the intervals of a deck: the next one in the same subject. */
    suspend fun examForDeck(deck: Deck): Exam? =
        dao.nextExamFor(deck.name, deck.fach, deck.subjectId, LocalDate.now().toEpochDay())

    /** Interval preview for the rating buttons, in days. */
    suspend fun preview(card: Card): Map<Rating, Int> {
        val today = LocalDate.now()
        val exam = dao.deck(card.deckId)?.let { examForDeck(it) }
        return Repetition.preview(card.schedule, today, exam?.let { LocalDate.ofEpochDay(it.dateEpochDay) })
    }

    /** Applies an answer. Returns a token that [undo] takes to put the card back as it was. */
    suspend fun rate(card: Card, rating: Rating): Undo = db.withTransaction {
        val today = LocalDate.now()
        val fresh = dao.card(card.id) ?: card
        val exam = dao.deck(fresh.deckId)?.let { examForDeck(it) }
        val next: CardSchedule = Repetition.review(fresh.schedule, rating, today, exam?.let { LocalDate.ofEpochDay(it.dateEpochDay) })
        val updated = fresh.with(next)
        dao.updateCard(updated)
        val reviewId = dao.insertReview(Review(cardId = fresh.id, epochDay = today.toEpochDay(), rating = rating.ordinal))
        Undo(fresh, updated, reviewId)
    }

    class Undo internal constructor(val before: Card, val after: Card, internal val reviewId: Long)

    suspend fun undo(token: Undo) = db.withTransaction {
        dao.updateCard(token.before)
        dao.deleteReview(token.reviewId)
    }

    /** Subject names used anywhere in the study data, for quick picks. */
    val knownSubjects: Flow<List<StudySubject>> = combine(lessons, exams, decks) { ls, es, ds ->
        (ls.map { StudySubject(it.subjectId, it.subject, it.colorIndex) } +
            es.map { StudySubject(it.subjectId, it.subject, it.colorIndex) } +
            ds.map { StudySubject(it.subjectId, it.name, it.colorIndex) })
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    companion object {
        internal fun lessonsOn(day: LocalDate, all: List<Lesson>, periods: List<PeriodTime>, set: TimetableSettings): List<LessonSlot> {
            val dow = day.dayOfWeek.value
            val byIndex = periods.associateBy { it.index }
            return all.asSequence()
                .filter { it.day == dow && dow <= set.days }
                .filter { !set.abWeeks || Timetable.takesPlace(cycleOf(it), day, set.anchor) }
                .sortedBy { it.period }
                .map { LessonSlot(it, byIndex[it.period]) }
                .toList()
        }

        internal fun cycleOf(l: Lesson): WeekCycle = runCatching { WeekCycle.valueOf(l.cycle) }.getOrDefault(WeekCycle.EVERY)
    }
}

/** First of [flow], for one-off reads in UI event handlers. */
internal suspend fun <T> Flow<T>.now(): T = first()
