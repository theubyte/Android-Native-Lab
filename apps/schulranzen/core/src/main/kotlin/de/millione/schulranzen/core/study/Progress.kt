package de.millione.schulranzen.core.study

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One answer to a card, from learning or from a mock exam. */
data class Attempt(val cardId: Long, val epochDay: Long, val at: Long, val right: Boolean)

/** A card on the error list, with why it is there. */
data class ErrorEntry(val cardId: Long, val wrong: Int, val wrongDays: Int, val lastWrongDay: Long, val rightInARow: Int)

/**
 * The error list ("Fehlerheft") collects itself from the answer log: a card is
 * on it once it went wrong on two different days, or three times overall (a new
 * card failed twice in its first sitting is normal learning). It leaves on its
 * own after [CLEAR_AFTER] right answers in a row, so nothing has to be tidied up.
 */
object ErrorBook {
    const val MIN_WRONG_DAYS = 2
    const val MIN_WRONG_TOTAL = 3
    const val CLEAR_AFTER = 3

    /** Entries, worst and most recent first. [attempts] may be in any order. */
    fun build(attempts: List<Attempt>): List<ErrorEntry> =
        attempts.groupBy { it.cardId }.mapNotNull { (id, list) ->
            val sorted = list.sortedBy { it.at }
            val wrongs = sorted.filter { !it.right }
            val days = wrongs.map { it.epochDay }.distinct().size
            if (wrongs.size < MIN_WRONG_TOTAL && days < MIN_WRONG_DAYS) return@mapNotNull null
            val streak = sorted.asReversed().takeWhile { it.right }.size
            if (streak >= CLEAR_AFTER) return@mapNotNull null
            ErrorEntry(id, wrongs.size, days, wrongs.maxOf { it.epochDay }, streak)
        }.sortedWith(compareByDescending<ErrorEntry> { it.wrong }.thenByDescending { it.lastWrongDay })

    /** Only "Nochmal" counts as wrong; "Schwer" was remembered. */
    fun isRight(rating: Rating): Boolean = rating != Rating.AGAIN
}

/** Lengths of a study rhythm, in minutes. */
data class FocusPlan(val focus: Int = 25, val shortBreak: Int = 5, val longBreak: Int = 15, val blocksUntilLong: Int = 4) {
    init { require(focus > 0 && shortBreak >= 0 && longBreak >= 0 && blocksUntilLong > 0) }
}

/** Where a running timer stands. */
data class FocusState(
    val inBreak: Boolean,
    /** 1-based number of the current focus block (or of the one just finished, during a break). */
    val block: Int,
    val secondsLeft: Int,
    /** Seconds in this phase altogether, for the progress ring. */
    val phaseSeconds: Int,
    /** Focus time so far, the number that goes into the statistics. */
    val focusSeconds: Int,
    val longBreak: Boolean,
)

/**
 * Focus timer as a pure function of elapsed time. Measured from a start instant
 * rather than ticked, so it stays right when the screen goes dark or the app is
 * in the background.
 */
object FocusTimer {
    fun at(plan: FocusPlan, elapsedSeconds: Long): FocusState {
        var t = elapsedSeconds.coerceAtLeast(0)
        var block = 1
        var focus = 0L
        val fs = plan.focus * 60L
        while (true) {
            if (t < fs) return FocusState(false, block, (fs - t).toInt(), fs.toInt(), (focus + t).toInt(), false)
            t -= fs
            focus += fs
            val long = block % plan.blocksUntilLong == 0
            val bs = (if (long) plan.longBreak else plan.shortBreak) * 60L
            if (t < bs) return FocusState(true, block, (bs - t).toInt(), bs.toInt(), focus.toInt(), long)
            t -= bs
            block++
        }
    }

    /** Seconds from now until the current phase ends, for a notification. */
    fun secondsToPhaseEnd(plan: FocusPlan, elapsedSeconds: Long): Int = at(plan, elapsedSeconds).secondsLeft
}

/** One answer for the statistics. */
data class StatAnswer(val epochDay: Long, val deckId: Long, val right: Boolean)

data class DayStat(val date: LocalDate, val answers: Int, val right: Int, val focusMinutes: Int) {
    val percent: Int? get() = if (answers == 0) null else right * 100 / answers
}

data class DeckStat(val deckId: Long, val answers: Int, val right: Int) {
    val percent: Int get() = if (answers == 0) 0 else right * 100 / answers
}

data class StudySummary(
    val days: List<DayStat>,
    val decks: List<DeckStat>,
    val answers: Int,
    val right: Int,
    val focusMinutes: Int,
    val activeDays: Int,
    val bestStreak: Int,
)

object StudyStatistics {
    /**
     * Statistics over the [spanDays] days up to [today], one entry per day (also the empty
     * ones, so a gap is visible). Streak and totals look only at this span.
     */
    fun summary(answers: List<StatAnswer>, focus: Map<Long, Int>, today: LocalDate, spanDays: Int): StudySummary {
        val first = today.minusDays((spanDays - 1).toLong())
        val from = first.toEpochDay()
        val to = today.toEpochDay()
        val inSpan = answers.filter { it.epochDay in from..to }
        val perDay = inSpan.groupBy { it.epochDay }
        val days = (0 until spanDays).map { i ->
            val d = first.plusDays(i.toLong())
            val list = perDay[d.toEpochDay()].orEmpty()
            DayStat(d, list.size, list.count { it.right }, focus[d.toEpochDay()] ?: 0)
        }
        val decks = inSpan.groupBy { it.deckId }
            .map { (id, l) -> DeckStat(id, l.size, l.count { it.right }) }
            .sortedBy { it.percent }
        var best = 0
        var run = 0
        for (d in days) {
            if (d.answers > 0 || d.focusMinutes > 0) { run++; best = maxOf(best, run) } else run = 0
        }
        return StudySummary(
            days, decks, inSpan.size, inSpan.count { it.right },
            days.sumOf { it.focusMinutes },
            days.count { it.answers > 0 || it.focusMinutes > 0 },
            best,
        )
    }
}

/** What a reminder should say; the worker only has to post it. */
data class ReminderText(val title: String, val body: String)

object Reminders {
    /** Days before an exam on which a reminder goes out. */
    val EXAM_DAYS = setOf(14L, 7L, 3L, 2L, 1L, 0L)
    const val DEFAULT_MINUTE = 17 * 60

    /** Milliseconds until the next [minuteOfDay]; today if still ahead, else tomorrow. Follows DST. */
    fun delayMillis(now: ZonedDateTime, minuteOfDay: Int): Long {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        var next = now.toLocalDate().atTime(m / 60, m % 60).atZone(now.zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(m / 60, m % 60).atZone(now.zone)
        return ChronoUnit.MILLIS.between(now, next)
    }

    /**
     * The daily nudge. Nothing to do or today's work already done means no message:
     * a reminder that says nothing teaches people to swipe it away.
     */
    fun daily(dueCards: Int, newCards: Int, reviewedToday: Int, nextExam: String?, daysToExam: Long?): ReminderText? {
        if (dueCards <= 0 && (newCards <= 0 || reviewedToday > 0)) return null
        val todo = buildList {
            if (dueCards > 0) add(if (dueCards == 1) "1 Karte fällig" else "$dueCards Karten fällig")
            if (newCards > 0 && reviewedToday == 0) add(if (newCards == 1) "1 neue" else "$newCards neue")
        }.joinToString(", ")
        val exam = if (nextExam != null && daysToExam != null && daysToExam in 0..14) " · $nextExam ${phrase(daysToExam)}" else ""
        return ReminderText("Zeit zum Lernen", todo + exam)
    }

    /** The exam warning on the days in [EXAM_DAYS]; null on all others. */
    fun exam(subject: String, kind: String, daysLeft: Long, percent: Int?): ReminderText? {
        if (daysLeft !in EXAM_DAYS) return null
        val ready = percent?.let { " Prognose: $it %." } ?: ""
        return ReminderText("$kind ${phrase(daysLeft)}", "$subject.$ready")
    }

    private fun phrase(days: Long) = when (days) {
        0L -> "heute"
        1L -> "morgen"
        else -> "in $days Tagen"
    }
}
