package de.millione.schulranzen.core.study

import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/** How the learner judged an answer in a mock exam. [points] count towards the score. */
enum class SelfGrade(val points: Double) { RIGHT(1.0), PARTLY(0.5), WRONG(0.0), SKIPPED(0.0) }

/** One answered (or unanswered) question of a mock exam. */
data class MockAnswer(val topic: String, val grade: SelfGrade)

data class TopicScore(val topic: String, val asked: Int, val points: Double) {
    val percent: Double get() = if (asked == 0) 0.0 else points * 100.0 / asked
}

data class MockResult(
    val asked: Int,
    val points: Double,
    val percent: Double,
    /** German school grade 1–6 after the IHK key. */
    val grade: Int,
    /** Topics below the pass mark, weakest first. */
    val weakTopics: List<TopicScore>,
    val topics: List<TopicScore>,
)

/**
 * Mock exams made from the learner's own cards. Questions are drawn with a
 * bias towards weak cards (forgotten before, low ease, never seen), because
 * a mock exam is most useful where it hurts. Grading follows the IHK key
 * used in vocational school and the journeyman's exam (SHK).
 */
object MockExam {
    /** Percent needed for a 4 (ausreichend). Also the pass mark for a topic. */
    const val PASS_PERCENT = 50.0
    /** Percent from which a topic or the whole exam counts as "sitzt" (grade 3). */
    const val SOLID_PERCENT = 67.0

    /**
     * Picks [count] cards (or all, if fewer), weak ones more likely, in a
     * random order. [random] makes it reproducible in tests.
     */
    fun <T> pick(cards: List<T>, count: Int, schedule: (T) -> CardSchedule, random: Random = Random.Default): List<T> {
        if (count <= 0 || cards.isEmpty()) return emptyList()
        val pool = cards.map { it to weight(schedule(it)) }.toMutableList()
        val out = ArrayList<T>(minOf(count, cards.size))
        while (out.size < count && pool.isNotEmpty()) {
            val total = pool.sumOf { it.second }
            var r = random.nextDouble() * total
            var idx = pool.lastIndex
            for ((i, p) in pool.withIndex()) {
                r -= p.second
                if (r <= 0) { idx = i; break }
            }
            out += pool.removeAt(idx).first
        }
        return out
    }

    /** Draw weight of a card: 1 for a well-known card, up to ~8 for a shaky one. */
    fun weight(s: CardSchedule): Double {
        var w = 1.0
        if (s.isNew) w += 1.0
        w += minOf(s.lapses, 4) * 1.0
        w += max(0.0, Repetition.START_EASE - s.ease) * 2.0
        if (s.reps <= 1 && !s.isNew) w += 0.5
        return w
    }

    /** Suggested time in minutes: 1.5 min per question, rounded up to 5. */
    fun suggestedMinutes(questions: Int): Int = max(5, (ceil(questions * 1.5 / 5.0) * 5).toInt())

    /** IHK key: 100–92 → 1, 91–81 → 2, 80–67 → 3, 66–50 → 4, 49–30 → 5, below → 6. */
    fun grade(percent: Double): Int = when {
        percent >= 92 -> 1
        percent >= 81 -> 2
        percent >= 67 -> 3
        percent >= 50 -> 4
        percent >= 30 -> 5
        else -> 6
    }

    fun evaluate(answers: List<MockAnswer>): MockResult {
        val points = answers.sumOf { it.grade.points }
        val percent = if (answers.isEmpty()) 0.0 else points * 100.0 / answers.size
        val topics = answers.groupBy { it.topic }
            .map { (t, a) -> TopicScore(t, a.size, a.sumOf { it.grade.points }) }
            .sortedBy { it.percent }
        return MockResult(
            asked = answers.size,
            points = points,
            percent = percent,
            grade = grade(percent),
            weakTopics = topics.filter { it.percent < SOLID_PERCENT },
            topics = topics,
        )
    }
}

/**
 * Assigns a card to one of an exam's topics by shared word stems, so the
 * mock exam result can say "Trinkwasser: 40 %" instead of naming a deck.
 */
object TopicMatcher {
    private val stop = setOf(
        "und", "oder", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "einem", "einen",
        "mit", "von", "für", "bei", "nach", "über", "unter", "wie", "was", "wird", "werden", "sind", "ist", "beim",
        "welche", "welcher", "welches", "nenne", "erkläre", "beschreibe", "warum", "wann", "wozu",
    )

    fun stems(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 4 && it !in stop }
        .map(::stem)
        .toSet()

    /** Crude German stemming: enough to match "Ventile" with "Ventil" and "Heizungen" with "Heizung". */
    fun stem(w: String): String {
        for (suffix in listOf("ungen", "en", "er", "es", "e", "n", "s")) {
            if (w.length - suffix.length >= 4 && w.endsWith(suffix)) {
                return if (suffix == "ungen") w.dropLast(2) else w.dropLast(suffix.length)
            }
        }
        return w
    }

    /** The topic sharing most stems with [text]; ties go to the earlier topic; none → [fallback]. */
    fun topicOf(text: String, topics: List<String>, fallback: String): String {
        if (topics.isEmpty()) return fallback
        val words = stems(text)
        var best: String? = null
        var bestScore = 0
        for (t in topics) {
            val score = stems(t).count { s -> words.any { it == s || (s.length >= 5 && (it.startsWith(s) || s.startsWith(it))) } }
            if (score > bestScore) { best = t; bestScore = score }
        }
        return best ?: fallback
    }
}
