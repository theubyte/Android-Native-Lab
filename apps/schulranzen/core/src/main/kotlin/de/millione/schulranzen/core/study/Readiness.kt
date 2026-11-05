package de.millione.schulranzen.core.study

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** A finished mock exam as far as the forecast cares. */
data class MockPoint(val percent: Double, val daysAgo: Long, val asked: Int)

data class ReadinessInput(
    val cards: List<CardSchedule>,
    /** Topic name for each card, same order as [cards]; empty if the exam lists no topics. */
    val cardTopics: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val mocks: List<MockPoint> = emptyList(),
    val linksTotal: Int = 0,
    val linksDone: Int = 0,
    /** Cards worth having, from [LearningPath.cardTarget]. */
    val cardTarget: Int = LearningPath.MIN_CARDS,
    val today: LocalDate,
    val examDate: LocalDate,
)

enum class Confidence { NONE, LOW, MEDIUM, HIGH }

enum class GapKind { NO_CARDS, FEW_CARDS, NEW_CARDS, DUE_CARDS, SHAKY_CARDS, TOPICS_MISSING, TOPICS_WEAK, LINKS_OPEN, NO_MOCK, OLD_MOCK, MOCK_LOW }

/** One thing still missing, most costly first. [names] are topics, if any. */
data class ReadinessGap(val kind: GapKind, val count: Int, val names: List<String> = emptyList())

data class Readiness(
    /** Expected share of points in the exam, 0..100; null without any basis. */
    val percent: Int?,
    /** Expected grade from [percent], null without any basis. */
    val grade: Int?,
    /** Honest range around [percent]: the less evidence, the wider. */
    val low: Int?,
    val high: Int?,
    val confidence: Confidence,
    /** Estimated recall on the exam day over the learner's own cards, 0..100. */
    val memoryPercent: Int,
    /** How much of the material is covered by cards at all, 0..100. */
    val coveragePercent: Int,
    /** Weighted mock exam result, null if none counts. */
    val mockPercent: Int?,
    val gaps: List<ReadinessGap>,
)

/**
 * A forecast the learner can trust because it does not flatter. It estimates
 * what is still in memory on the exam day (a forgetting curve from each card's
 * interval), scales that by how much of the material is covered at all, and
 * blends in recent mock exams, which are the only direct evidence. Without a
 * mock exam the forecast stays capped and its range wide. It knows only the
 * learner's own cards, never the real exam, and says so through [Confidence].
 */
object ReadinessForecast {
    /** A card is planned so that about this much is still recalled on its due day. */
    private const val RECALL_AT_DUE = 0.9
    /** Without a mock exam nobody has shown it under exam conditions. */
    private const val CAP_WITHOUT_MOCK = 85
    private const val MOCK_FRESH_DAYS = 14L
    private const val MOCK_OLD_DAYS = 21L
    private const val MIN_BASIS_CARDS = 5
    const val SHAKY_RECALL = 0.6
    private const val SHAKY_LAPSES = 2

    /**
     * Estimated chance to recall one card on [exam]. Exponential forgetting, with the
     * card's interval read as the time after which [RECALL_AT_DUE] is left. Cards seen
     * once have a short memory; an unseen card counts as zero.
     */
    fun recall(s: CardSchedule, today: LocalDate, exam: LocalDate): Double {
        val last = s.lastReviewEpochDay ?: return 0.0
        // Just failed: known only as far as the last repetition carried it.
        val interval = when {
            s.reps == 0 -> 0.25
            else -> max(1, s.intervalDays).toDouble()
        }
        val stability = interval / -ln(RECALL_AT_DUE)
        val until = maxOf(exam.toEpochDay(), today.toEpochDay())
        val waited = max(0L, until - last).toDouble()
        return exp(-waited / stability).coerceIn(0.0, 1.0)
    }

    fun estimate(i: ReadinessInput): Readiness {
        val cards = i.cards
        val seen = cards.count { !it.isNew }
        val fresh = cards.size - seen
        val recalls = cards.map { recall(it, i.today, i.examDate) }
        val memory = if (cards.isEmpty()) 0.0 else recalls.average()
        val day = i.today.toEpochDay()
        val due = cards.count { !it.isNew && it.dueEpochDay <= day }
        val shaky = cards.indices.count { !cards[it].isNew && (recalls[it] < SHAKY_RECALL || cards[it].lapses >= SHAKY_LAPSES) }

        // Coverage: enough cards, topics reached, marked material worked through.
        val coverage = coverage(i)

        val counted = i.mocks.filter { it.daysAgo <= MOCK_OLD_DAYS && it.asked > 0 }
        val mock = if (counted.isEmpty()) null else {
            // Newer runs count more, longer runs count more: a 5-question run is thin evidence.
            var sum = 0.0
            var weight = 0.0
            for (m in counted) {
                val w = exp(-m.daysAgo / 10.0) * minOf(m.asked, 30)
                sum += m.percent * w
                weight += w
            }
            sum / weight
        }
        val mockQuestions = counted.sumOf { it.asked }
        val hasBasis = seen >= MIN_BASIS_CARDS || counted.isNotEmpty()

        val gaps = gaps(i, fresh, due, shaky, coverage, counted, mock, cards.size)

        if (!hasBasis) {
            return Readiness(null, null, null, null, Confidence.NONE, (memory * 100).roundToInt(), (coverage * 100).roundToInt(), mock?.roundToInt(), gaps)
        }
        val mockWeight = when {
            mock == null -> 0.0
            mockQuestions >= 30 -> 0.7
            mockQuestions >= 15 -> 0.55
            else -> 0.35
        }
        val memoryPart = memory * 100.0
        val blended = (mock ?: 0.0) * mockWeight + memoryPart * (1 - mockWeight)
        // Material not on cards is not known just because the cards went well.
        var percent = blended * (0.4 + 0.6 * coverage)
        if (mock == null) percent = minOf(percent, CAP_WITHOUT_MOCK.toDouble())
        val value = percent.roundToInt().coerceIn(0, 100)
        val confidence = when {
            mock != null && mockQuestions >= 15 && seen >= MIN_BASIS_CARDS -> Confidence.HIGH
            mock != null -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val spread = when (confidence) { Confidence.HIGH -> 7; Confidence.MEDIUM -> 12; else -> 20 }
        return Readiness(
            percent = value,
            grade = MockExam.grade(value.toDouble()),
            low = (value - spread).coerceAtLeast(0),
            high = (value + spread).coerceAtMost(100),
            confidence = confidence,
            memoryPercent = memoryPart.roundToInt(),
            coveragePercent = (coverage * 100).roundToInt(),
            mockPercent = mock?.roundToInt(),
            gaps = gaps,
        )
    }

    private fun coverage(i: ReadinessInput): Double {
        val parts = mutableListOf<Double>()
        parts += (i.cards.size.toDouble() / max(1, i.cardTarget)).coerceAtMost(1.0)
        if (i.topics.isNotEmpty()) {
            val hit = i.topics.count { t -> covered(t, i) }
            parts += hit.toDouble() / i.topics.size
        }
        if (i.linksTotal > 0) parts += i.linksDone.toDouble() / i.linksTotal
        return parts.average()
    }

    private fun covered(topic: String, i: ReadinessInput): Boolean =
        i.cardTopics.indices.any { k -> i.cardTopics[k] == topic && k < i.cards.size && !i.cards[k].isNew }

    private fun gaps(
        i: ReadinessInput, fresh: Int, due: Int, shaky: Int, coverage: Double,
        counted: List<MockPoint>, mock: Double?, cardCount: Int,
    ): List<ReadinessGap> {
        val out = mutableListOf<ReadinessGap>()
        if (cardCount == 0) out += ReadinessGap(GapKind.NO_CARDS, 0)
        else if (cardCount < i.cardTarget) out += ReadinessGap(GapKind.FEW_CARDS, i.cardTarget - cardCount)
        val missing = i.topics.filter { t -> i.cardTopics.none { it == t } }
        if (missing.isNotEmpty()) out += ReadinessGap(GapKind.TOPICS_MISSING, missing.size, missing)
        val unlearned = i.topics.filter { t -> t !in missing && !covered(t, i) }
        if (unlearned.isNotEmpty()) out += ReadinessGap(GapKind.TOPICS_WEAK, unlearned.size, unlearned)
        if (fresh > 0) out += ReadinessGap(GapKind.NEW_CARDS, fresh)
        if (due > 0) out += ReadinessGap(GapKind.DUE_CARDS, due)
        if (shaky > 0) out += ReadinessGap(GapKind.SHAKY_CARDS, shaky)
        if (i.linksTotal > i.linksDone) out += ReadinessGap(GapKind.LINKS_OPEN, i.linksTotal - i.linksDone)
        when {
            i.mocks.isEmpty() -> if (cardCount > 0) out += ReadinessGap(GapKind.NO_MOCK, 0)
            counted.isEmpty() -> out += ReadinessGap(GapKind.OLD_MOCK, i.mocks.minOf { it.daysAgo }.toInt())
            mock != null && mock < MockExam.SOLID_PERCENT -> out += ReadinessGap(GapKind.MOCK_LOW, mock.roundToInt())
            counted.none { it.daysAgo <= MOCK_FRESH_DAYS } -> out += ReadinessGap(GapKind.OLD_MOCK, counted.minOf { it.daysAgo }.toInt())
        }
        return out
    }
}
