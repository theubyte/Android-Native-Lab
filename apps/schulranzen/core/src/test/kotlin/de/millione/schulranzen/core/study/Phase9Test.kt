package de.millione.schulranzen.core.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CardSuggesterTest {
    @Test fun definitionWithIst() {
        val s = CardSuggester.suggest("Das Ausdehnungsgefäß ist ein Behälter, der die Volumenänderung des Heizwassers aufnimmt.")
        assertEquals(1, s.size)
        assertEquals("Was ist das Ausdehnungsgefäß?", s[0].front)
        assertTrue(s[0].back.startsWith("Ein Behälter"))
        assertEquals(SuggestionKind.DEFINITION, s[0].kind)
    }

    @Test fun understandAndPronounSentencesAreHandled() {
        val s = CardSuggester.suggest("Unter Legionellen versteht man Bakterien im warmen Trinkwasser. Es ist wichtig, das zu wissen.")
        assertEquals(1, s.size)
        assertEquals("Was versteht man unter Legionellen?", s[0].front)
        assertEquals("Bakterien im warmen Trinkwasser.", s[0].back)
    }

    @Test fun abbreviationsDoNotSplitSentences() {
        val parts = CardSuggester.sentences("Ein Rohr ist z. B. aus Kupfer. Die Pumpe fördert ca. 2 m³/h. Fertig.")
        assertEquals(3, parts.size)
        assertEquals("Ein Rohr ist z. B. aus Kupfer.", parts[0])
    }

    @Test fun termLines() {
        val s = CardSuggester.suggest("Mindesttemperatur Warmwasser: 60 °C am Speicheraustritt\nBeispiel: so nicht")
        assertEquals(1, s.size)
        assertEquals("Mindesttemperatur Warmwasser", s[0].front)
        assertEquals("60 °C am Speicheraustritt", s[0].back)
    }

    @Test fun listsUnderAHeading() {
        val s = CardSuggester.suggest("Bauteile einer Heizungsanlage:\n- Wärmeerzeuger\n- Pumpe\n- MAG: nimmt Ausdehnung auf\n\nEnde")
        val list = s.first { it.kind == SuggestionKind.LIST }
        assertEquals("Bauteile einer Heizungsanlage – nenne die 3 Punkte.", list.front)
        assertTrue(list.back.contains("• Pumpe"))
        // The bullet "MAG: …" also becomes its own term card.
        assertTrue(s.any { it.front == "MAG" })
    }

    @Test fun duplicatesAndNoiseAreDropped() {
        val s = CardSuggester.suggest("Das ist gut.\nDas ist gut.\nPumpe: fördert Wasser\nPumpe: fördert Wasser")
        assertEquals(listOf("Pumpe"), s.map { it.front })
    }

    @Test fun emptyTextGivesNothing() = assertTrue(CardSuggester.suggest("  \n ").isEmpty())
}

class MockExamTest {
    private fun sched(lapses: Int = 0, ease: Double = 2.5, seen: Boolean = true) =
        CardSchedule(reps = if (seen) 3 else 0, intervalDays = 5, ease = ease, lapses = lapses, dueEpochDay = 0, lastReviewEpochDay = if (seen) 0 else null)

    @Test fun gradesFollowIhkKey() {
        assertEquals(1, MockExam.grade(100.0))
        assertEquals(1, MockExam.grade(92.0))
        assertEquals(2, MockExam.grade(91.9))
        assertEquals(3, MockExam.grade(67.0))
        assertEquals(4, MockExam.grade(50.0))
        assertEquals(5, MockExam.grade(30.0))
        assertEquals(6, MockExam.grade(29.9))
    }

    @Test fun pickTakesDistinctCardsUpToCount() {
        val cards = (1..10).toList()
        val p = MockExam.pick(cards, 5, { sched() }, Random(1))
        assertEquals(5, p.size)
        assertEquals(5, p.toSet().size)
        assertEquals(10, MockExam.pick(cards, 50, { sched() }, Random(1)).size)
        assertTrue(MockExam.pick(cards, 0, { sched() }).isEmpty())
    }

    @Test fun weakCardsAreDrawnMoreOften() {
        val weak = sched(lapses = 4, ease = 1.3)
        val strong = sched()
        assertTrue(MockExam.weight(weak) > MockExam.weight(strong) * 3)
        var weakFirst = 0
        val r = Random(7)
        repeat(500) { if (MockExam.pick(listOf("w", "s"), 1, { if (it == "w") weak else strong }, r).first() == "w") weakFirst++ }
        assertTrue("weak drawn $weakFirst/500", weakFirst > 350)
    }

    @Test fun evaluationScoresAndWeakTopics() {
        val res = MockExam.evaluate(
            listOf(
                MockAnswer("Trinkwasser", SelfGrade.RIGHT),
                MockAnswer("Trinkwasser", SelfGrade.RIGHT),
                MockAnswer("Heizung", SelfGrade.WRONG),
                MockAnswer("Heizung", SelfGrade.PARTLY),
            ),
        )
        assertEquals(4, res.asked)
        assertEquals(2.5, res.points, 1e-9)
        assertEquals(62.5, res.percent, 1e-9)
        assertEquals(4, res.grade)
        assertEquals(listOf("Heizung"), res.weakTopics.map { it.topic })
        assertEquals(25.0, res.weakTopics[0].percent, 1e-9)
    }

    @Test fun suggestedMinutes() {
        assertEquals(5, MockExam.suggestedMinutes(2))
        assertEquals(15, MockExam.suggestedMinutes(10))
        assertEquals(30, MockExam.suggestedMinutes(20))
    }

    @Test fun topicMatcherUsesStems() {
        val topics = listOf("Trinkwasserinstallation", "Heizungsventile und Pumpen", "Abwasser")
        assertEquals("Heizungsventile und Pumpen", TopicMatcher.topicOf("Wozu dient die Pumpe im Heizkreis?", topics, "Stapel"))
        assertEquals("Stapel", TopicMatcher.topicOf("Was ist ein Lötfitting?", topics, "Stapel"))
        assertEquals("Stapel", TopicMatcher.topicOf("egal", emptyList(), "Stapel"))
    }
}

class LearningPathTest {
    private fun input(
        links: Int = 0, linksDone: Int = 0, topics: Int = 0, cards: Int = 0, seen: Int = 0, solid: Int = 0,
        due: Int = 0, mock: Double? = null, days: Long = 10,
    ) = PathInput(links, linksDone, topics, cards, seen, solid, due, mock, days)

    @Test fun freshExamStartsWithMaterial() {
        val p = LearningPath.build(input(links = 4))
        assertEquals(StageKind.MATERIAL, p.next?.kind)
        assertEquals(StageState.ACTIVE, p.stages[0].state)
        assertTrue(p.stages.drop(1).all { it.state == StageState.OPEN })
        assertEquals(0f, p.progress, 1e-6f)
    }

    @Test fun progressesThroughStages() {
        val afterMaterial = LearningPath.build(input(links = 2, linksDone = 2, cards = 4))
        assertEquals(StageKind.CARDS, afterMaterial.next?.kind)
        assertEquals(4, afterMaterial.stages[1].done)
        assertEquals(LearningPath.MIN_CARDS, afterMaterial.stages[1].total)

        val learning = LearningPath.build(input(cards = 12, seen = 12, solid = 3))
        assertEquals(StageKind.LEARN, learning.next?.kind)

        val mock = LearningPath.build(input(cards = 12, seen = 12, solid = 11, mock = 40.0))
        assertEquals(StageKind.MOCK, mock.next?.kind)

        val waiting = LearningPath.build(input(cards = 12, seen = 12, solid = 11, mock = 80.0, days = 5))
        assertEquals(StageKind.FINAL, waiting.next?.kind)
        assertEquals(StageState.OPEN, waiting.next?.state)

        val eve = LearningPath.build(input(cards = 12, seen = 12, solid = 11, mock = 80.0, days = 1, due = 0))
        assertTrue(eve.stages.all { it.state == StageState.DONE })
        assertNull(eve.next?.takeIf { it.state == StageState.ACTIVE })
        assertEquals(1f, eve.progress, 1e-6f)
    }

    @Test fun pastExamHasNoNextStep() = assertNull(LearningPath.build(input(days = -1)).next)

    @Test fun cardTargetScalesWithTopics() {
        assertEquals(10, LearningPath.cardTarget(1, 0))
        assertEquals(18, LearningPath.cardTarget(6, 2))
        assertEquals(40, LearningPath.cardTarget(30, 0))
    }
}

class ClaudeApiTest {
    @Test fun requestBodyHasModelSystemAndTurns() {
        val body = Json.parseToJsonElement(ClaudeApi.requestBody("sys", listOf(ChatTurn("user", "Was ist ein MAG?")), 500)).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(500, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("sys", body["system"]!!.jsonPrimitive.content)
        val msg = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
        assertEquals("Was ist ein MAG?", msg["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
    }

    @Test fun replyTextSkipsThinking() {
        val r = ClaudeApi.parseReply(
            """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Ein MAG "},{"type":"text","text":"gleicht aus."}],"stop_reason":"end_turn"}""",
        )
        assertEquals("Ein MAG gleicht aus.", r.text)
        assertEquals("end_turn", r.stopReason)
    }

    @Test fun refusalAndEmptyAreErrors() {
        val refused = runCatching { ClaudeApi.parseReply("""{"content":[],"stop_reason":"refusal"}""") }.exceptionOrNull() as AssistantException
        assertEquals(AssistantError.REFUSED, refused.error)
        val empty = runCatching { ClaudeApi.parseReply("""{"content":[],"stop_reason":"end_turn"}""") }.exceptionOrNull() as AssistantException
        assertEquals(AssistantError.EMPTY, empty.error)
    }

    @Test fun httpErrorsMapToKinds() {
        val e = ClaudeApi.parseError(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(AssistantError.INVALID_KEY, e.error)
        assertEquals("invalid x-api-key", e.message)
        assertEquals(AssistantError.RATE_LIMIT, ClaudeApi.parseError(429, null).error)
        assertEquals(AssistantError.OVERLOADED, ClaudeApi.parseError(529, "kaputt").error)
        assertEquals(AssistantError.SERVER, ClaudeApi.parseError(503, "").error)
    }

    @Test fun cardsFromFencedJson() {
        val cards = ClaudeApi.parseCards(
            "Hier:\n```json\n[{\"front\":\"Was ist ein MAG?\",\"back\":\"Membran-Ausdehnungsgefäß\"},{\"front\":\"\",\"back\":\"x\"},{\"front\":\"was ist ein mag?\",\"back\":\"doppelt\"}]\n```",
        )
        assertEquals(1, cards.size)
        assertEquals(SuggestionKind.ASSISTANT, cards[0].kind)
        assertTrue(ClaudeApi.parseCards("keine Karten").isEmpty())
        assertTrue(ClaudeApi.parseCards("[kaputt").isEmpty())
    }

    @Test fun noKeyFailsWithoutNetwork() {
        val e = runCatching { ClaudeApi.send("", "s", listOf(ChatTurn("user", "x"))) }.exceptionOrNull() as AssistantException
        assertEquals(AssistantError.NO_KEY, e.error)
    }
}
