package de.millione.schulranzen.core.study

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A card as the day planner sees it. */
data class PlanCard(
    val id: Long,
    val schedule: CardSchedule,
    /** On the error list or wrong in a recent mock exam. */
    val weak: Boolean = false,
    /** First answered today: it used up part of today's share of new cards. */
    val introducedToday: Boolean = false,
)

/** One upcoming exam with what belongs to it. */
data class PlanExam(
    val id: Long,
    val date: LocalDate,
    /** 1 for a normal class test, more for what counts more (see [TodayPlanner.weightOf]). */
    val weight: Double,
    val cards: List<PlanCard>,
    val openLinkIds: List<Long> = emptyList(),
    /** Days since the last mock exam, null if none yet. */
    val daysSinceMock: Long? = null,
)

/** Why a card is in today's session, most pressing first. */
enum class SessionTier { WEAK_DUE, DUE, NEW, WEAK_EXTRA, OTHER_DUE }

data class SessionCard(val cardId: Long, val examId: Long?, val tier: SessionTier)

/** What today's session asks of one exam. */
data class ExamShare(
    val examId: Long,
    val due: Int,
    val fresh: Int,
    val weak: Int,
    val links: Int,
    /** Cards that belong here today but did not fit into the time. */
    val deferred: Int,
)

data class DayLoad(val date: LocalDate, val cards: Int, val minutes: Int)

data class TodayPlan(
    /** Cards in the order to learn them. */
    val cards: List<SessionCard>,
    /** Marked places to work through today, nearest exam first. */
    val links: List<Pair<Long, Long>>,
    val shares: List<ExamShare>,
    /** An exam for which a mock exam is the most useful thing now. */
    val mockExamId: Long?,
    val minutes: Int,
    val budgetMinutes: Int,
    /** Cards left out because the time ran out; [TodayPlanner.build] with a larger budget brings them in. */
    val deferred: Int,
    /** Expected work per day up to the last exam, today first. */
    val forecast: List<DayLoad>,
    /** Minutes a day it would take to get everything done in time; can exceed the budget. */
    val minutesNeededPerDay: Int,
) {
    val isEmpty: Boolean get() = cards.isEmpty() && links.isEmpty() && mockExamId == null
}

/**
 * "Jetzt lernen": turns everything open over all coming exams into one session
 * for today. Reviews come before new cards (forgetting what you had costs more
 * than learning something later), an exam close by comes before one far away,
 * and new cards are spread over the days left so that the last day before an
 * exam stays free for repetition. Time is the hard limit: what does not fit is
 * reported, not hidden, and stays for tomorrow.
 */
object TodayPlanner {
    const val DEFAULT_MINUTES = 30
    const val SECONDS_REVIEW = 25
    const val SECONDS_NEW = 45
    const val SECONDS_WEAK = 40
    const val MINUTES_LINK = 10
    /** A mock exam is overdue this close to the date, if none was written lately. */
    private const val MOCK_WINDOW_DAYS = 10L
    private const val MOCK_REPEAT_DAYS = 4L
    private const val MIN_CARDS_FOR_MOCK = 5
    /** Weak cards that are not due yet are only pulled forward this close to the exam. */
    private const val WEAK_EXTRA_DAYS = 7L

    /** How much an exam counts relative to a class test; by name of its kind. */
    fun weightOf(kind: String): Double = when (kind) {
        "GP1", "GP2" -> 3.0
        "KLASSENARBEIT", "LERNFELD", "PRAXIS" -> 1.5
        "REFERAT" -> 0.75
        else -> 1.0
    }

    /** Higher means first: closeness to the date, scaled by how much the exam counts. */
    fun urgency(exam: PlanExam, today: LocalDate): Double {
        val days = max(0L, ExamPlanner.daysUntil(today, exam.date))
        return exam.weight / (days + 1.0)
    }

    /** Days on which new cards can still be introduced; the eve stays for repetition. */
    fun daysForNew(today: LocalDate, date: LocalDate): Long {
        val left = ExamPlanner.daysUntil(today, date)
        return when {
            left <= 1 -> 1
            else -> left - 1
        }
    }

    /**
     * Today's plan for [exams] (past ones are ignored). [otherDue] are due cards of
     * decks without an exam; they only get what time is left. [budgetMinutes] is the
     * time the learner wants to spend; pass a large value for "everything".
     */
    fun build(exams: List<PlanExam>, otherDue: List<PlanCard>, today: LocalDate, budgetMinutes: Int = DEFAULT_MINUTES): TodayPlan {
        val day = today.toEpochDay()
        val coming = exams.filter { !it.date.isBefore(today) }.sortedByDescending { urgency(it, today) }
        // A deck can serve two exams; a card is learnt once, for the more urgent one.
        val owner = HashMap<Long, Long>()
        for (e in coming) e.cards.forEach { owner.putIfAbsent(it.id, e.id) }
        val owned = coming.associateWith { e -> e.cards.filter { owner[it.id] == e.id } }

        var left = budgetMinutes.coerceAtLeast(0) * 60

        // Material first, at most its fair share a day: it feeds everything else.
        val links = mutableListOf<Pair<Long, Long>>()
        val linkCount = HashMap<Long, Int>()
        for (e in coming) {
            val days = daysForNew(today, e.date)
            val share = ceil(e.openLinkIds.size.toDouble() / days).toInt()
            for (id in e.openLinkIds.take(share)) {
                val cost = MINUTES_LINK * 60
                // Keep at least half the time for cards, unless there are none.
                if (left - cost < budgetMinutes * 30 && links.isNotEmpty()) break
                links += e.id to id
                linkCount[e.id] = (linkCount[e.id] ?: 0) + 1
                left -= cost
            }
        }

        val tiers = HashMap<Long, MutableMap<SessionTier, List<PlanCard>>>()
        for (e in coming) {
            val cards = owned[e].orEmpty()
            val close = ExamPlanner.daysUntil(today, e.date) <= WEAK_EXTRA_DAYS
            val due = cards.filter { !it.schedule.isNew && it.schedule.dueEpochDay <= day }
            val fresh = cards.filter { it.schedule.isNew }
            // Counting what was started today keeps a second look at the plan from handing out a fresh share.
            val introduced = cards.count { it.introducedToday }
            val quota = (ceil((fresh.size + introduced).toDouble() / daysForNew(today, e.date)).toInt() - introduced).coerceAtLeast(0)
            tiers[e.id] = mutableMapOf(
                SessionTier.WEAK_DUE to due.filter { it.weak }.sortedBy { it.schedule.dueEpochDay },
                SessionTier.DUE to due.filter { !it.weak }.sortedBy { it.schedule.dueEpochDay },
                SessionTier.NEW to fresh.take(quota),
                SessionTier.WEAK_EXTRA to if (close) cards.filter { it.weak && !it.schedule.isNew && it.schedule.dueEpochDay > day } else emptyList(),
            )
        }

        fun cost(t: SessionTier) = when (t) {
            SessionTier.WEAK_DUE, SessionTier.WEAK_EXTRA -> SECONDS_WEAK
            SessionTier.NEW -> SECONDS_NEW
            else -> SECONDS_REVIEW
        }

        val session = mutableListOf<SessionCard>()
        val deferred = HashMap<Long, Int>()
        for (tier in listOf(SessionTier.WEAK_DUE, SessionTier.DUE, SessionTier.NEW, SessionTier.WEAK_EXTRA)) {
            if (coming.isEmpty()) break
            // Within a tier the exams share the time by urgency, so a far exam is not starved
            // to zero, but the near one gets the larger part.
            val queues = coming.map { e -> e to ArrayDeque(tiers[e.id]?.get(tier).orEmpty()) }
            val total = coming.sumOf { urgency(it, today) }.coerceAtLeast(1e-9)
            val credit = HashMap<Long, Double>()
            while (queues.any { it.second.isNotEmpty() }) {
                for ((e, q) in queues) credit[e.id] = (credit[e.id] ?: 0.0) + urgency(e, today) / total
                val (e, q) = queues.filter { it.second.isNotEmpty() }.maxByOrNull { credit[it.first.id] ?: 0.0 } ?: break
                credit[e.id] = (credit[e.id] ?: 0.0) - 1.0
                val c = q.removeFirst()
                if (left >= cost(tier)) {
                    left -= cost(tier)
                    session += SessionCard(c.id, e.id, tier)
                } else {
                    deferred[e.id] = (deferred[e.id] ?: 0) + 1
                }
            }
        }
        var otherDeferred = 0
        for (c in otherDue.filter { it.id !in owner }.sortedBy { it.schedule.dueEpochDay }) {
            val cost = if (c.schedule.isNew) SECONDS_NEW else SECONDS_REVIEW
            if (left >= cost) {
                left -= cost
                session += SessionCard(c.id, null, SessionTier.OTHER_DUE)
            } else otherDeferred++
        }

        val mock = coming.firstOrNull { e ->
            owned[e].orEmpty().size >= MIN_CARDS_FOR_MOCK &&
                ExamPlanner.daysUntil(today, e.date) in 1..MOCK_WINDOW_DAYS &&
                (e.daysSinceMock == null || e.daysSinceMock >= MOCK_REPEAT_DAYS) &&
                // Writing a mock exam on cards never seen tells nothing.
                owned[e].orEmpty().count { !it.schedule.isNew } * 2 >= owned[e].orEmpty().size
        }?.id

        val shares = coming.map { e ->
            val mine = session.filter { it.examId == e.id }
            ExamShare(
                e.id,
                due = mine.count { it.tier == SessionTier.DUE || it.tier == SessionTier.WEAK_DUE },
                fresh = mine.count { it.tier == SessionTier.NEW },
                weak = mine.count { it.tier == SessionTier.WEAK_DUE || it.tier == SessionTier.WEAK_EXTRA },
                links = linkCount[e.id] ?: 0,
                deferred = deferred[e.id] ?: 0,
            )
        }
        val forecast = forecast(coming.map { it.copy(cards = owned[it].orEmpty()) }, otherDue, today)
        val used = budgetMinutes.coerceAtLeast(0) * 60 - left
        return TodayPlan(
            cards = session,
            links = links,
            shares = shares,
            mockExamId = mock,
            minutes = ceil(used / 60.0).toInt(),
            budgetMinutes = budgetMinutes,
            deferred = deferred.values.sum() + otherDeferred,
            forecast = forecast,
            minutesNeededPerDay = forecast.maxOfOrNull { it.minutes } ?: 0,
        )
    }

    /**
     * Rough work per day: reviews where they are already scheduled, plus the even share
     * of new cards and material, each new card coming back about a day later. An estimate
     * for "wird das knapp?", not a promise.
     */
    fun forecast(exams: List<PlanExam>, otherDue: List<PlanCard>, today: LocalDate): List<DayLoad> {
        val last = exams.maxOfOrNull { it.date } ?: return emptyList()
        val horizon = min(ExamPlanner.daysUntil(today, last), 60L).toInt()
        val cards = IntArray(horizon + 1)
        val seconds = IntArray(horizon + 1)
        val day0 = today.toEpochDay()
        fun add(i: Int, n: Int, each: Int) {
            if (i in 0..horizon) { cards[i] += n; seconds[i] += n * each }
        }
        for (c in otherDue) if (!c.schedule.isNew) add((c.schedule.dueEpochDay - day0).coerceAtLeast(0).toInt(), 1, SECONDS_REVIEW)
        for (e in exams) {
            val end = ExamPlanner.daysUntil(today, e.date).coerceAtLeast(0).toInt().coerceAtMost(horizon)
            for (c in e.cards) {
                if (c.schedule.isNew) continue
                val at = (c.schedule.dueEpochDay - day0).coerceAtLeast(0).toInt()
                if (at <= end) add(at, 1, if (c.weak) SECONDS_WEAK else SECONDS_REVIEW)
            }
            val fresh = e.cards.count { it.schedule.isNew }
            val days = daysForNew(today, e.date).toInt().coerceAtMost(end.coerceAtLeast(1))
            val links = e.openLinkIds.size
            for (i in 0 until days) {
                val n = fresh / days + if (i < fresh % days) 1 else 0
                add(i, n, SECONDS_NEW)
                add(i + 1, n, SECONDS_REVIEW)
                val l = links / days + if (i < links % days) 1 else 0
                if (i in 0..horizon) seconds[i] += l * MINUTES_LINK * 60
            }
        }
        return (0..horizon).map { DayLoad(today.plusDays(it.toLong()), cards[it], ceil(seconds[it] / 60.0).toInt()) }
    }
}
