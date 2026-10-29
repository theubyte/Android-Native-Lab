package de.millione.schulranzen.study

import android.content.Context
import androidx.room.withTransaction
import de.millione.schulranzen.core.study.CardSchedule
import de.millione.schulranzen.core.study.PeriodTime
import de.millione.schulranzen.core.study.Rating
import de.millione.schulranzen.core.study.Repetition
import de.millione.schulranzen.core.study.StudyStats
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.core.study.WeekCycle
import de.millione.schulranzen.study.data.Card
import de.millione.schulranzen.study.data.Deck
import de.millione.schulranzen.study.data.DeckCounts
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate

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

    suspend fun deleteDeck(id: Long) = dao.deleteDeck(id)

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

    suspend fun addCard(deckId: Long, front: String, back: String): Long =
        dao.insertCard(Card(deckId = deckId, front = front.trim(), back = back.trim(), dueEpochDay = LocalDate.now().toEpochDay()))

    suspend fun updateCardText(card: Card, front: String, back: String) =
        dao.updateCard(card.copy(front = front.trim(), back = back.trim()))

    suspend fun deleteCard(card: Card) = dao.deleteCard(card)

    /** Due cards of one deck (or all), in learning order: overdue first, new last. */
    suspend fun studyQueue(deckId: Long?): List<Card> {
        val today = LocalDate.now()
        return Repetition.queue(dao.dueCards(today.toEpochDay(), deckId), today) { it.schedule }
    }

    /** The exam that should cap the intervals of a deck: the next one in the same subject. */
    suspend fun examForDeck(deck: Deck): Exam? =
        dao.nextExamFor(deck.name, deck.subjectId, LocalDate.now().toEpochDay())

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
                .filter { it.day == dow }
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
