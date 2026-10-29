package de.millione.schulranzen.core.study

import kotlin.math.min

/** The stages of a learning path, in order. */
enum class StageKind {
    /** Work through the marked material (notebook and book pages). */
    MATERIAL,
    /** Write enough cards. */
    CARDS,
    /** Learn the cards until most of them sit. */
    LEARN,
    /** Pass a mock exam. */
    MOCK,
    /** Last pass on the eve of the exam. */
    FINAL,
}

enum class StageState { DONE, ACTIVE, OPEN }

/** One stage with its numbers; the UI turns [done]/[total] into words. */
data class PathStage(val kind: StageKind, val state: StageState, val progress: Float, val done: Int, val total: Int)

data class PathState(val stages: List<PathStage>, val progress: Float, val next: PathStage?)

/** Everything the path is computed from; collected by the study repository. */
data class PathInput(
    val linksTotal: Int,
    val linksDone: Int,
    val topics: Int,
    val cards: Int,
    val cardsSeen: Int,
    /** Cards with at least two successful reviews in a row. */
    val cardsSolid: Int,
    val cardsDue: Int,
    val mockBestPercent: Double?,
    val daysLeft: Long,
)

/**
 * A learning path per exam: sight the material, write cards, learn them,
 * pass a mock exam, last pass on the eve. Each stage is derived from data
 * the learner produces anyway, so nothing has to be ticked twice. The next
 * step is the first unfinished stage; the final pass waits for the eve.
 */
object LearningPath {
    const val MIN_CARDS = 10
    const val MAX_CARDS_TARGET = 40
    const val SOLID_SHARE = 0.8f

    /** Cards worth writing: three per topic or marked page, at least [MIN_CARDS]. */
    fun cardTarget(topics: Int, links: Int): Int = (maxOf(topics, links) * 3).coerceIn(MIN_CARDS, MAX_CARDS_TARGET)

    fun build(i: PathInput): PathState {
        val material = if (i.linksTotal > 0) {
            val p = i.linksDone.toFloat() / i.linksTotal
            Triple(p, i.linksDone, i.linksTotal)
        } else {
            // Nothing marked: working from cards alone is fine once cards exist.
            Triple(if (i.cards > 0) 1f else 0f, 0, 0)
        }
        val target = cardTarget(i.topics, i.linksTotal)
        val mockDone = (i.mockBestPercent ?: 0.0) >= MockExam.SOLID_PERCENT
        val cardsP = if (mockDone && i.cards > 0) 1f else min(1f, i.cards.toFloat() / target)
        val learnP = if (i.cards == 0) 0f else min(1f, (i.cardsSolid.toFloat() / i.cards) / SOLID_SHARE)
        val mockP = min(1f, ((i.mockBestPercent ?: 0.0) / MockExam.SOLID_PERCENT).toFloat())
        val eve = i.daysLeft <= 1
        val finalP = when {
            !eve || i.cards == 0 -> 0f
            i.cardsDue == 0 && i.cardsSeen == i.cards -> 1f
            else -> (i.cards - i.cardsDue).coerceAtLeast(0).toFloat() / i.cards
        }
        val raw = listOf(
            Triple(StageKind.MATERIAL, material.first, material.second to material.third),
            Triple(StageKind.CARDS, cardsP, min(i.cards, target) to target),
            Triple(StageKind.LEARN, learnP, i.cardsSolid to i.cards),
            Triple(StageKind.MOCK, mockP, (i.mockBestPercent ?: 0.0).toInt() to MockExam.SOLID_PERCENT.toInt()),
            Triple(StageKind.FINAL, finalP, (i.cards - i.cardsDue).coerceAtLeast(0) to i.cards),
        )
        var activeFound = false
        val stages = raw.map { (kind, p, nums) ->
            val done = p >= 0.999f
            val state = when {
                done -> StageState.DONE
                activeFound -> StageState.OPEN
                kind == StageKind.FINAL && !eve -> StageState.OPEN
                else -> { activeFound = true; StageState.ACTIVE }
            }
            PathStage(kind, state, p.coerceIn(0f, 1f), nums.first, nums.second)
        }
        val next = when {
            i.daysLeft < 0 -> null
            else -> stages.firstOrNull { it.state == StageState.ACTIVE } ?: stages.firstOrNull { it.state == StageState.OPEN }
        }
        return PathState(stages, stages.map { it.progress }.average().toFloat(), next)
    }
}
