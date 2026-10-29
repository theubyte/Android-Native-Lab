package de.millione.schulranzen.core.study

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** What a planned study day is for. The UI turns it into words. */
enum class PlanPhase {
    /** Sort the material, make cards. */
    START,
    /** Spaced review of the material. */
    REVIEW,
    /** Last pass the day before: only what is still shaky. */
    FINAL,
}

data class PlanDay(val date: LocalDate, val phase: PlanPhase)

/**
 * Suggests study days before an exam. Reviews are spaced so they grow denser
 * towards the exam (1, 2, 4, 7, 11 … days before), which is how spaced
 * repetition works best when the end date is fixed.
 */
object ExamPlanner {
    /** Days before the exam, nearest first; gaps widen by one each step. */
    private val OFFSETS: List<Int> = buildList {
        var o = 1; var gap = 1
        while (o <= 120) { add(o); o += gap; gap++ }
    }

    fun daysUntil(today: LocalDate, date: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

    /**
     * Study days from [today] (inclusive) up to the day before [exam], at most
     * [maxSessions]. If the exam is today or past, the plan is empty.
     */
    fun plan(today: LocalDate, exam: LocalDate, maxSessions: Int = 8): List<PlanDay> {
        val left = daysUntil(today, exam)
        if (left <= 0 || maxSessions <= 0) return emptyList()
        val dates = OFFSETS.asSequence()
            .filter { it <= left }
            .take(maxSessions)
            .map { exam.minusDays(it.toLong()) }
            .toMutableList()
        // Start today if the first planned day lies further ahead: the sooner the first pass, the better.
        if (dates.size < maxSessions && dates.none { it == today }) dates.add(today)
        val sorted = dates.distinct().sorted()
        return sorted.mapIndexed { i, d ->
            val phase = when {
                d == exam.minusDays(1) && sorted.size > 1 -> PlanPhase.FINAL
                i == 0 -> PlanPhase.START
                else -> PlanPhase.REVIEW
            }
            PlanDay(d, phase)
        }
    }

    /**
     * Cards to study today so that all unseen cards are introduced before
     * the exam: every due card plus a fair share of the new ones.
     */
    fun cardsToday(dueCards: Int, newCards: Int, today: LocalDate, exam: LocalDate?): Int {
        if (newCards <= 0) return dueCards
        val days = exam?.let { daysUntil(today, it) }?.coerceAtLeast(1) ?: 7
        return dueCards + ceil(newCards.toDouble() / days).toInt()
    }
}
