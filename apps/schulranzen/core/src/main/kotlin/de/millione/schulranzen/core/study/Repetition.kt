package de.millione.schulranzen.core.study

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The four answers in the learning mode: Nochmal, Schwer, Gut, Leicht. */
enum class Rating { AGAIN, HARD, GOOD, EASY }

/**
 * Scheduling state of one flash card. All dates are epoch days of a
 * [LocalDate] so a review never shifts when the device changes time zone or
 * daylight saving time: "due" means "due on that calendar day", nothing finer.
 */
data class CardSchedule(
    /** Successful reviews in a row; 0 = new or just lapsed. */
    val reps: Int = 0,
    /** Current interval in days; 0 = again today. */
    val intervalDays: Int = 0,
    val ease: Double = Repetition.START_EASE,
    val lapses: Int = 0,
    /** First day the card should be shown again. A new card is due at once. */
    val dueEpochDay: Long = Long.MIN_VALUE,
    /** Day of the last review, null for a card never seen. */
    val lastReviewEpochDay: Long? = null,
) {
    val isNew: Boolean get() = lastReviewEpochDay == null
    fun isDue(today: LocalDate): Boolean = dueEpochDay <= today.toEpochDay()
}

/**
 * Day-based SM-2, tuned for school: the first steps are shorter than in
 * classic SM-2 (1 → 3 days instead of 1 → 6) because exams are weeks away,
 * not years. Answers adjust the ease, late reviews count part of the delay
 * (the card was remembered longer than planned), and an exam date caps the
 * next due day so every card comes up once more before the exam.
 */
object Repetition {
    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.2
    const val MAX_INTERVAL = 365

    private const val HARD_FACTOR = 1.2
    private const val EASY_BONUS = 1.3

    fun review(
        state: CardSchedule,
        rating: Rating,
        today: LocalDate,
        examDate: LocalDate? = null,
    ): CardSchedule {
        val day = today.toEpochDay()
        val ease = (state.ease + when (rating) {
            Rating.AGAIN -> -0.20
            Rating.HARD -> -0.15
            Rating.GOOD -> 0.0
            Rating.EASY -> 0.15
        }).coerceIn(MIN_EASE, MAX_EASE)

        val interval: Int
        val reps: Int
        var lapses = state.lapses
        if (rating == Rating.AGAIN) {
            // Forgotten: back to the start, once more today.
            interval = 0
            reps = 0
            if (state.reps > 0) lapses++
        } else {
            reps = state.reps + 1
            interval = when (state.reps) {
                0 -> when (rating) { Rating.HARD -> 1; Rating.GOOD -> 1; else -> 3 }
                1 -> when (rating) { Rating.HARD -> 2; Rating.GOOD -> 3; else -> 5 }
                else -> grow(state, rating, ease, day)
            }.coerceIn(1, MAX_INTERVAL)
        }

        var due = day + interval
        if (examDate != null) {
            val exam = examDate.toEpochDay()
            // Keep one review before the exam; on the eve itself the exam day is the latest slot.
            if (exam > day) due = min(due, max(day + if (interval == 0) 0 else 1, exam - 1))
        }
        return CardSchedule(reps, interval, ease, lapses, due, day)
    }

    /** Interval for a card in the review phase (at least two successful reviews). */
    private fun grow(state: CardSchedule, rating: Rating, ease: Double, today: Long): Int {
        val current = max(1, state.intervalDays)
        val delay = state.lastReviewEpochDay?.let { max(0L, today - (it + current)) }?.toInt() ?: 0
        val hard = max(current + 1, (current * HARD_FACTOR).roundToInt())
        val good = max(hard + 1, ((current + delay / 2.0) * ease).roundToInt())
        val easy = max(good + 1, ((current + delay) * ease * EASY_BONUS).roundToInt())
        return when (rating) {
            Rating.HARD -> hard
            Rating.GOOD -> good
            else -> easy
        }
    }

    /** What each answer would give, for the small day counts on the buttons. */
    fun preview(state: CardSchedule, today: LocalDate, examDate: LocalDate? = null): Map<Rating, Int> =
        Rating.entries.associateWith {
            (review(state, it, today, examDate).dueEpochDay - today.toEpochDay()).toInt()
        }

    /** Due cards first by how overdue they are, then new cards in the order they were made. */
    fun <T> queue(cards: List<T>, today: LocalDate, schedule: (T) -> CardSchedule): List<T> {
        val due = cards.filter { schedule(it).isDue(today) }
        val (fresh, old) = due.partition { schedule(it).isNew }
        return old.sortedBy { schedule(it).dueEpochDay } + fresh
    }
}
