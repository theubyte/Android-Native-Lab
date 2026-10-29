package de.millione.schulranzen.core.study

import java.time.LocalDate

object StudyStats {
    /**
     * Days in a row with at least one review. A streak that ended yesterday
     * still counts until today is over: the user can keep it alive tonight.
     */
    fun streak(reviewDays: Set<Long>, today: LocalDate): Int {
        var day = today.toEpochDay()
        if (day !in reviewDays) day -= 1
        var n = 0
        while (day in reviewDays) { n++; day-- }
        return n
    }

    /** Share of cards that are out of the learning phase (two good answers in a row), 0..100. */
    fun mastery(schedules: List<CardSchedule>): Int {
        if (schedules.isEmpty()) return 0
        return schedules.count { it.reps >= 2 } * 100 / schedules.size
    }
}
