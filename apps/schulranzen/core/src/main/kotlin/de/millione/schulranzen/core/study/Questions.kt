package de.millione.schulranzen.core.study

import kotlin.math.abs
import kotlin.random.Random

/** The ways a card can be asked. */
enum class QuestionType { FLIP, TYPE, CHOICE, CLOZE, MATCH }

/** What the question logic needs to know about a card. */
data class QuizCard(
    val id: Long,
    val front: String,
    val back: String,
    /** Pictures can only be asked by turning the card over. */
    val hasImage: Boolean = false,
)

/** One question, ready to show. A [Match] asks several cards at once. */
sealed interface Question {
    val cards: List<QuizCard>
    val type: QuestionType

    data class Flip(val card: QuizCard) : Question {
        override val cards get() = listOf(card)
        override val type get() = QuestionType.FLIP
    }
    data class TypeIn(val card: QuizCard) : Question {
        override val cards get() = listOf(card)
        override val type get() = QuestionType.TYPE
    }
    data class Choice(val card: QuizCard, val options: List<String>, val correct: Int) : Question {
        override val cards get() = listOf(card)
        override val type get() = QuestionType.CHOICE
    }
    data class Cloze(val card: QuizCard, val text: String, val answer: String) : Question {
        override val cards get() = listOf(card)
        override val type get() = QuestionType.CLOZE
    }
    /** [rightOrder] is the shuffled order of the answers, as indices into [cards]. */
    data class Match(override val cards: List<QuizCard>, val rightOrder: List<Int>) : Question {
        override val type get() = QuestionType.MATCH
    }
}

/** A sentence with one gap. */
data class ClozeText(val text: String, val answer: String)

/**
 * Builds questions from a learner's own cards. Nothing is invented: distractors
 * are answers of other cards in the same deck, gaps are cut from the card's own text.
 */
object QuestionMaker {
    const val BLANK = "_____"
    const val MATCH_EVERY = 8
    const val MATCH_SIZE = 4
    private const val MAX_CHOICE_LENGTH = 90
    private const val MAX_MATCH_LENGTH = 60

    /**
     * Up to three distractors from [pool]: other cards' answers, most similar in kind
     * first (a number competes with numbers of the same unit, a short term with short
     * terms), never one that would also be correct. Null with fewer than two, since a
     * choice between two is hardly a question.
     */
    fun choice(card: QuizCard, pool: List<QuizCard>, random: Random = Random.Default): Question.Choice? {
        val right = plain(card.back).trim()
        if (card.hasImage || right.isEmpty() || right.length > MAX_CHOICE_LENGTH) return null
        val seen = hashSetOf(AnswerCheck.normalize(right))
        val candidates = pool.asSequence()
            .filter { it.id != card.id && !it.hasImage }
            .map { plain(it.back).trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_CHOICE_LENGTH }
            .filter { val k = AnswerCheck.normalize(it); k.isNotEmpty() && seen.add(k) }
            // Near-spellings of the right answer would be correct under the tolerant check.
            .filter { AnswerCheck.check(it, right).verdict.grade != SelfGrade.RIGHT }
            .toList()
            .shuffled(random)
        val distractors = candidates.sortedBy { similarity(right, it) }.take(3)
        if (distractors.size < 2) return null
        val options = (distractors + right).shuffled(random)
        return Question.Choice(card, options, options.indexOf(right))
    }

    /** Lower is more alike. The sort is stable, which keeps the shuffle among equals. */
    private fun similarity(a: String, b: String): Int {
        val numA = a.any(Char::isDigit)
        val numB = b.any(Char::isDigit)
        val kind = if (numA == numB) 0 else 100
        val unit = if (numA && numB && tail(a) != tail(b)) 20 else 0
        val lines = if (a.contains('\n') == b.contains('\n')) 0 else 50
        return kind + unit + lines + abs(a.length - b.length) / 8
    }

    private fun tail(s: String) = s.trim().substringAfterLast(' ').lowercase()

    private val clozeMark = Regex("""\{\{c\d+::(.+?)(?:::[^}]*)?\}\}""")
    private val valueRe = Regex("""(?<![\p{L}\d.,])\d(?:[\d.,]*\d)?(?:\s?(?:°\s?C|°|%|[\p{L}]{1,4}(?:/[\p{L}²³\d]+)?[²³]?\b))?""")

    /** [text] as it is read: a gap marked {{c1::word}} shows as the word. */
    fun plain(text: String): String = clozeMark.replace(text) { it.groupValues[1] }

    /** A gap in [text]: marked with {{c1::…}} if the card has one, otherwise the best term. */
    fun cloze(text: String, avoid: String = ""): ClozeText? {
        clozeMark.find(text)?.let { m ->
            val filled = clozeMark.replace(text) { o -> if (o.range == m.range) BLANK else o.groupValues[1] }
            return ClozeText(filled, m.groupValues[1].trim())
        }
        val clean = text.trim().replace('\n', ' ')
        val words = clean.split(Regex("\\s+"))
        if (words.size < 4 || words.size > 45) return null
        // Values are what exams ask: "bei 60 °C", "mindestens 1,5 bar".
        valueRe.find(clean)?.let { m ->
            return ClozeText(clean.replaceRange(m.range, BLANK), m.value.trim())
        }
        val avoidStems = TopicMatcher.stems(avoid)
        val best = words.withIndex()
            .map { (i, w) -> Triple(i, w, w.trim('.', ',', ';', ':', '!', '?', '(', ')', '„', '“', '"')) }
            .filter { (_, _, bare) -> bare.length >= 5 && bare.all { it.isLetter() || it == '-' } && bare.lowercase() !in commonWords }
            .filter { (_, _, bare) -> TopicMatcher.stem(bare.lowercase()) !in avoidStems }
            .maxByOrNull { (i, _, bare) ->
                // Nouns carry the fact; the sentence start is capitalised anyway.
                (if (bare.first().isUpperCase() && i > 0) 100 else 0) + bare.length
            } ?: return null
        val (index, word, bare) = best
        return ClozeText(words.toMutableList().also { it[index] = word.replace(bare, BLANK) }.joinToString(" "), bare)
    }

    private val commonWords = setOf(
        "werden", "wurde", "worden", "haben", "hatte", "sollte", "sollen", "können", "müssen", "nicht", "diese", "dieser", "dieses",
        "einem", "einer", "eines", "durch", "immer", "dabei", "sowie", "außerdem", "welche", "welcher", "welches",
        "zwischen", "während", "gegen", "unter", "wichtig", "mindestens", "höchstens",
    )

    fun clozeQuestion(card: QuizCard): Question.Cloze? {
        if (card.hasImage) return null
        // A single word on the back is a typing question, not a gap.
        val c = cloze(card.back, avoid = card.front) ?: return null
        if (!AnswerCheck.canCheck(c.answer)) return null
        return Question.Cloze(card, c.text, c.answer)
    }

    /** A matching round from [cards] whose both sides are short enough to tap. */
    fun match(cards: List<QuizCard>, random: Random = Random.Default): Question.Match? {
        val usable = cards.filter(::canMatch).distinctBy { AnswerCheck.normalize(it.front) }
        if (usable.size < 3) return null
        return Question.Match(usable, usable.indices.toList().shuffled(random))
    }

    fun canMatch(c: QuizCard): Boolean =
        !c.hasImage && c.front.isNotBlank() && c.back.isNotBlank() && c.front.length <= MAX_MATCH_LENGTH && c.back.length <= MAX_MATCH_LENGTH && '\n' !in c.back

    /** A question of [type], or the plainest one that works for this card. */
    fun question(type: QuestionType, card: QuizCard, pool: List<QuizCard>, random: Random = Random.Default): Question = when (type) {
        QuestionType.TYPE -> if (!card.hasImage && AnswerCheck.canCheck(card.back)) Question.TypeIn(card) else Question.Flip(card)
        QuestionType.CHOICE -> choice(card, pool, random) ?: question(QuestionType.TYPE, card, pool, random)
        QuestionType.CLOZE -> clozeQuestion(card) ?: question(QuestionType.TYPE, card, pool, random)
        else -> Question.Flip(card)
    }

    /**
     * Exam mix: every card gets a kind it suits, kinds stay balanced, and one
     * matching block per [MATCH_EVERY] cards. Free explanations stay open questions.
     */
    fun examMix(cards: List<QuizCard>, pool: List<QuizCard>, random: Random = Random.Default): List<Question> {
        val out = mutableListOf<Question>()
        val rest = cards.toMutableList()
        repeat(cards.size / MATCH_EVERY) {
            val group = rest.filter(::canMatch).take(MATCH_SIZE)
            match(group, random)?.let { q ->
                out += q
                rest.removeAll { c -> q.cards.any { it.id == c.id } }
            }
        }
        val used = HashMap<QuestionType, Int>()
        for (card in rest) {
            val options = listOfNotNull(
                clozeQuestion(card),
                choice(card, pool, random),
                if (!card.hasImage && AnswerCheck.canCheck(card.back)) Question.TypeIn(card) else null,
            )
            val q = options.minByOrNull { used[it.type] ?: 0 } ?: Question.Flip(card)
            used[q.type] = (used[q.type] ?: 0) + 1
            out += q
        }
        return out.shuffled(random)
    }
}

/**
 * State of one matching round: tap a left item, tap a right item. Two cards with
 * the same answer accept either right item. A pair wrong on the first try stays
 * in [missed], so the round can be scored per card.
 */
class MatchRound(private val cards: List<QuizCard>) {
    private val solved = linkedSetOf<Long>()
    private val wrong = hashSetOf<Long>()

    val missed: Set<Long> get() = wrong
    val solvedIds: Set<Long> get() = solved
    val done: Boolean get() = solved.size == cards.size

    /** True if [rightCardId]'s answer belongs to [leftCardId]. Wrong tries are remembered. */
    fun pick(leftCardId: Long, rightCardId: Long): Boolean {
        val left = cards.first { it.id == leftCardId }
        val right = cards.first { it.id == rightCardId }
        val ok = AnswerCheck.normalize(left.back) == AnswerCheck.normalize(right.back)
        if (ok) solved += leftCardId else wrong += leftCardId
        return ok
    }

    fun firstTry(cardId: Long): Boolean = cardId in solved && cardId !in wrong
}
