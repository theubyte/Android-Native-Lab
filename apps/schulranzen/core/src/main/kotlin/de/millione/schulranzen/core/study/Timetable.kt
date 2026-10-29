package de.millione.schulranzen.core.study

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One period of the school day, times as minutes after midnight (local wall clock). */
data class PeriodTime(val index: Int, val startMinute: Int, val endMinute: Int) {
    init { require(endMinute > startMinute) { "period must end after it starts" } }
    fun contains(minute: Int): Boolean = minute in startMinute until endMinute
}

/** Where the clock stands in the school day. */
sealed interface DayStatus {
    data object BeforeSchool : DayStatus
    data class InPeriod(val index: Int) : DayStatus
    data class Break(val nextIndex: Int) : DayStatus
    data object AfterSchool : DayStatus
}

/** Which weeks a lesson takes place in when the school alternates A and B weeks. */
enum class WeekCycle { EVERY, A, B }

object Timetable {
    /**
     * Common vocational school times: 45-minute periods in double lessons,
     * a 20-minute break after the second and a 15-minute one after the fourth,
     * lunch after the sixth.
     */
    val DEFAULT_PERIODS: List<PeriodTime> = listOf(
        "07:45" to "08:30", "08:30" to "09:15",
        "09:35" to "10:20", "10:20" to "11:05",
        "11:20" to "12:05", "12:05" to "12:50",
        "13:20" to "14:05", "14:05" to "14:50",
        "14:50" to "15:35", "15:35" to "16:20",
    ).mapIndexed { i, (a, b) -> PeriodTime(i + 1, parseTime(a)!!, parseTime(b)!!) }

    /** "7:45", "07:45" or "0745" → minutes; null if it is not a valid time. */
    fun parseTime(text: String): Int? {
        val t = text.trim().replace('.', ':')
        val (h, m) = when {
            ':' in t -> t.split(':').let { if (it.size != 2) return null; it[0] to it[1] }
            t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
            else -> return null
        }
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59 || m.length != 2) return null
        return hh * 60 + mm
    }

    fun formatTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    fun status(periods: List<PeriodTime>, minute: Int): DayStatus {
        val sorted = periods.sortedBy { it.startMinute }
        if (sorted.isEmpty()) return DayStatus.AfterSchool
        sorted.firstOrNull { it.contains(minute) }?.let { return DayStatus.InPeriod(it.index) }
        if (minute < sorted.first().startMinute) return DayStatus.BeforeSchool
        val next = sorted.firstOrNull { it.startMinute > minute } ?: return DayStatus.AfterSchool
        return DayStatus.Break(next.index)
    }

    /**
     * Whether [date] is in an A week. [anchor] is any day of a week the user
     * called "A"; weeks then alternate from Monday to Sunday.
     */
    fun isWeekA(date: LocalDate, anchor: LocalDate): Boolean {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val anchorMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = ChronoUnit.WEEKS.between(anchorMonday, monday)
        return Math.floorMod(weeks, 2L) == 0L
    }

    fun takesPlace(cycle: WeekCycle, date: LocalDate, anchor: LocalDate?): Boolean = when (cycle) {
        WeekCycle.EVERY -> true
        WeekCycle.A -> anchor == null || isWeekA(date, anchor)
        WeekCycle.B -> anchor == null || !isWeekA(date, anchor)
    }
}
