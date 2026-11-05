package de.millione.schulranzen.core.study

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayPlannerTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val d = today.toEpochDay()
    private var nextId = 1L

    private fun due(weak: Boolean = false, overdue: Int = 0) = PlanCard(nextId++, CardSchedule(reps = 2, intervalDays = 3, dueEpochDay = d - overdue, lastReviewEpochDay = d - 3 - overdue), weak)
    private fun fresh() = PlanCard(nextId++, CardSchedule(dueEpochDay = d))
    private fun later(weak: Boolean = false) = PlanCard(nextId++, CardSchedule(reps = 3, intervalDays = 10, dueEpochDay = d + 5, lastReviewEpochDay = d - 5), weak)
    private fun exam(id: Long, days: Long, cards: List<PlanCard>, weight: Double = 1.0, links: List<Long> = emptyList(), mock: Long? = null) =
        PlanExam(id, today.plusDays(days), weight, cards, links, mock)

    @Test fun `nothing to do is empty`() {
        val p = TodayPlanner.build(emptyList(), emptyList(), today)
        assertTrue(p.isEmpty)
        assertEquals(0, p.minutes)
        assertTrue(p.forecast.isEmpty())
    }

    @Test fun `past exams are ignored`() {
        assertTrue(TodayPlanner.build(listOf(exam(1, -1, listOf(due()))), emptyList(), today).isEmpty)
    }

    @Test fun `reviews come before new cards and weak before plain`() {
        val p = TodayPlanner.build(listOf(exam(1, 10, listOf(fresh(), due(), due(weak = true)))), emptyList(), today, 60)
        assertEquals(listOf(SessionTier.WEAK_DUE, SessionTier.DUE, SessionTier.NEW), p.cards.map { it.tier })
    }

    @Test fun `new cards are spread and the eve stays free`() {
        val cards = List(20) { fresh() }
        val p = TodayPlanner.build(listOf(exam(1, 5, cards)), emptyList(), today, 600)
        assertEquals(5, p.cards.size) // 20 cards over 4 days, day 5 stays for repetition
        assertEquals(4L, TodayPlanner.daysForNew(today, today.plusDays(5)))
        assertEquals(1L, TodayPlanner.daysForNew(today, today.plusDays(1)))
        assertEquals(1L, TodayPlanner.daysForNew(today, today))
    }

    @Test fun `exam tomorrow takes all new cards today`() {
        val p = TodayPlanner.build(listOf(exam(1, 1, List(7) { fresh() })), emptyList(), today, 600)
        assertEquals(7, p.cards.size)
    }

    @Test fun `near exam gets more of a short budget than a far one`() {
        val near = exam(1, 2, List(60) { due() })
        val far = exam(2, 30, List(60) { due() })
        val p = TodayPlanner.build(listOf(far, near), emptyList(), today, 10)
        val a = p.cards.count { it.examId == 1L }
        val b = p.cards.count { it.examId == 2L }
        assertTrue(a > b * 3, "near $a far $b")
        assertTrue(p.deferred > 0)
        assertTrue(p.minutes <= 10)
    }

    @Test fun `a final exam outweighs a small test at the same distance`() {
        assertEquals(3.0, TodayPlanner.weightOf("GP1"))
        val small = exam(1, 7, List(50) { due() }, weight = TodayPlanner.weightOf("TEST"))
        val big = exam(2, 7, List(50) { due() }, weight = TodayPlanner.weightOf("GP2"))
        val p = TodayPlanner.build(listOf(small, big), emptyList(), today, 10)
        assertTrue(p.cards.count { it.examId == 2L } > p.cards.count { it.examId == 1L })
    }

    @Test fun `every exam gets something when time allows`() {
        val p = TodayPlanner.build(listOf(exam(1, 1, List(4) { due() }), exam(2, 40, List(4) { due() })), emptyList(), today, 60)
        assertEquals(setOf(1L, 2L), p.cards.mapNotNull { it.examId }.toSet())
        assertEquals(0, p.deferred)
    }

    @Test fun `a card shared by two exams is learnt once for the nearer`() {
        val shared = due()
        val p = TodayPlanner.build(listOf(exam(1, 2, listOf(shared)), exam(2, 20, listOf(shared))), emptyList(), today, 60)
        assertEquals(listOf(SessionCard(shared.id, 1L, SessionTier.DUE)), p.cards)
    }

    @Test fun `weak cards not yet due are pulled forward only close to the exam`() {
        val weak = later(weak = true)
        assertEquals(SessionTier.WEAK_EXTRA, TodayPlanner.build(listOf(exam(1, 5, listOf(weak))), emptyList(), today, 60).cards.single().tier)
        assertTrue(TodayPlanner.build(listOf(exam(1, 20, listOf(weak))), emptyList(), today, 60).cards.isEmpty())
        assertTrue(TodayPlanner.build(listOf(exam(1, 5, listOf(later()))), emptyList(), today, 60).cards.isEmpty())
    }

    @Test fun `other decks get what is left`() {
        val other = due()
        assertEquals(SessionTier.OTHER_DUE, TodayPlanner.build(emptyList(), listOf(other), today, 10).cards.single().tier)
        val busy = TodayPlanner.build(listOf(exam(1, 2, List(30) { due() })), listOf(other), today, 5)
        assertTrue(busy.cards.none { it.cardId == other.id })
        assertTrue(busy.deferred > 0)
    }

    @Test fun `material share is spread and cards keep time`() {
        val p = TodayPlanner.build(listOf(exam(1, 5, listOf(due()), links = listOf(11, 12, 13, 14, 15, 16))), emptyList(), today, 30)
        assertEquals(1, p.links.size.coerceAtMost(1))
        assertTrue(p.links.size <= 2)
        assertEquals(listOf(1L to 11L), p.links.take(1))
        assertTrue(p.cards.isNotEmpty())
    }

    @Test fun `mock is suggested close to the exam with cards seen and not too often`() {
        val seen = List(8) { later() }
        assertEquals(1L, TodayPlanner.build(listOf(exam(1, 6, seen)), emptyList(), today).mockExamId)
        assertNull(TodayPlanner.build(listOf(exam(1, 6, seen, mock = 1)), emptyList(), today).mockExamId)
        assertEquals(1L, TodayPlanner.build(listOf(exam(1, 6, seen, mock = 5)), emptyList(), today).mockExamId)
        assertNull(TodayPlanner.build(listOf(exam(1, 30, seen)), emptyList(), today).mockExamId)
        assertNull(TodayPlanner.build(listOf(exam(1, 6, List(8) { fresh() })), emptyList(), today).mockExamId)
        assertNull(TodayPlanner.build(listOf(exam(1, 0, seen)), emptyList(), today).mockExamId, "on the day itself it is too late")
        assertNull(TodayPlanner.build(listOf(exam(1, 6, seen.take(3))), emptyList(), today).mockExamId)
    }

    @Test fun `forecast covers the days up to the last exam`() {
        val cards = List(10) { fresh() } + later()
        val p = TodayPlanner.build(listOf(exam(1, 6, cards)), emptyList(), today, 60)
        assertEquals(7, p.forecast.size)
        assertEquals(today, p.forecast.first().date)
        assertEquals(today.plusDays(6), p.forecast.last().date)
        assertEquals(11 + 10, p.forecast.sumOf { it.cards }) // ten new cards twice, the later one once
        assertTrue(p.minutesNeededPerDay > 0)
    }

    @Test fun `zero budget plans nothing but reports it`() {
        val p = TodayPlanner.build(listOf(exam(1, 3, listOf(due(), due()))), emptyList(), today, 0)
        assertTrue(p.cards.isEmpty())
        assertEquals(2, p.deferred)
    }
}

class ReadinessTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val exam = today.plusDays(10)
    private val d = today.toEpochDay()

    private fun solid(interval: Int = 30, ago: Long = 2) = CardSchedule(reps = 4, intervalDays = interval, dueEpochDay = d - ago + interval, lastReviewEpochDay = d - ago)
    private fun input(cards: List<CardSchedule>, mocks: List<MockPoint> = emptyList(), target: Int = 10, topics: List<String> = emptyList(), cardTopics: List<String> = emptyList(), lt: Int = 0, ld: Int = 0) =
        ReadinessInput(cards, cardTopics, topics, mocks, lt, ld, target, today, exam)

    @Test fun `no basis gives no number`() {
        val r = ReadinessForecast.estimate(input(emptyList()))
        assertNull(r.percent)
        assertNull(r.grade)
        assertEquals(Confidence.NONE, r.confidence)
        assertEquals(GapKind.NO_CARDS, r.gaps.first().kind)
        assertNull(ReadinessForecast.estimate(input(List(3) { solid() })).percent)
    }

    @Test fun `new cards know nothing`() {
        assertEquals(0.0, ReadinessForecast.recall(CardSchedule(), today, exam))
    }

    @Test fun `recall follows the interval`() {
        val atDue = ReadinessForecast.recall(CardSchedule(reps = 3, intervalDays = 10, lastReviewEpochDay = d), today, exam)
        assertEquals(0.9, atDue, 1e-9)
        val short = ReadinessForecast.recall(CardSchedule(reps = 1, intervalDays = 1, lastReviewEpochDay = d), today, exam)
        assertTrue(short < 0.5, "a one-day card is gone in ten days: $short")
        val failed = ReadinessForecast.recall(CardSchedule(reps = 0, lapses = 1, lastReviewEpochDay = d), today, exam)
        assertTrue(failed < short)
    }

    @Test fun `past exam date counts from today`() {
        assertEquals(1.0, ReadinessForecast.recall(CardSchedule(reps = 3, intervalDays = 10, lastReviewEpochDay = d), today, today.minusDays(5)), 1e-9)
    }

    @Test fun `solid cards without a mock stay capped and low confidence`() {
        val r = ReadinessForecast.estimate(input(List(10) { solid(interval = 300) }))
        assertNotNull(r.percent)
        assertTrue(r.percent!! <= 85)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.gaps.any { it.kind == GapKind.NO_MOCK })
        assertEquals(20, r.percent!! - r.low!!)
    }

    @Test fun `a good mock lifts the forecast and narrows the range`() {
        val cards = List(10) { solid(interval = 20) }
        val without = ReadinessForecast.estimate(input(cards))
        val with = ReadinessForecast.estimate(input(cards, mocks = listOf(MockPoint(95.0, 1, 20))))
        assertTrue(with.percent!! > without.percent!!)
        assertEquals(Confidence.HIGH, with.confidence)
        assertTrue(with.high!! - with.low!! < without.high!! - without.low!!)
    }

    @Test fun `a bad mock pulls it down even with good cards`() {
        val cards = List(10) { solid(interval = 60) }
        val r = ReadinessForecast.estimate(input(cards, mocks = listOf(MockPoint(30.0, 1, 30))))
        assertTrue(r.percent!! < 60, "percent ${r.percent}")
        assertTrue(r.gaps.any { it.kind == GapKind.MOCK_LOW })
    }

    @Test fun `old mocks do not count and are named`() {
        val r = ReadinessForecast.estimate(input(List(10) { solid() }, mocks = listOf(MockPoint(95.0, 40, 30))))
        assertNull(r.mockPercent)
        assertEquals(40, r.gaps.first { it.kind == GapKind.OLD_MOCK }.count)
    }

    @Test fun `newer and longer mocks weigh more`() {
        val cards = List(10) { solid() }
        val r = ReadinessForecast.estimate(input(cards, mocks = listOf(MockPoint(90.0, 1, 30), MockPoint(40.0, 15, 5))))
        assertTrue(r.mockPercent!! > 80)
    }

    @Test fun `few cards reduce coverage and the forecast`() {
        val full = ReadinessForecast.estimate(input(List(10) { solid() }))
        val thin = ReadinessForecast.estimate(input(List(5) { solid() }, target = 40))
        assertTrue(thin.coveragePercent < full.coveragePercent)
        assertTrue(thin.percent!! < full.percent!!)
        assertEquals(35, thin.gaps.first { it.kind == GapKind.FEW_CARDS }.count)
    }

    @Test fun `missing topics are named`() {
        val cards = List(6) { solid() }
        val r = ReadinessForecast.estimate(input(cards, topics = listOf("Trinkwasser", "Heizung"), cardTopics = List(6) { "Trinkwasser" }))
        assertEquals(listOf("Heizung"), r.gaps.first { it.kind == GapKind.TOPICS_MISSING }.names)
    }

    @Test fun `topic with unseen cards counts as weak not missing`() {
        val cards = List(6) { solid() } + CardSchedule()
        val r = ReadinessForecast.estimate(input(cards, topics = listOf("A", "B"), cardTopics = List(6) { "A" } + "B"))
        assertEquals(listOf("B"), r.gaps.first { it.kind == GapKind.TOPICS_WEAK }.names)
        assertTrue(r.gaps.none { it.kind == GapKind.TOPICS_MISSING })
    }

    @Test fun `open material and due and new cards are listed`() {
        val cards = List(6) { solid() } + CardSchedule() + CardSchedule(reps = 2, intervalDays = 3, dueEpochDay = d - 1, lastReviewEpochDay = d - 4)
        val r = ReadinessForecast.estimate(input(cards, lt = 4, ld = 1))
        assertEquals(1, r.gaps.first { it.kind == GapKind.NEW_CARDS }.count)
        assertEquals(1, r.gaps.first { it.kind == GapKind.DUE_CARDS }.count)
        assertEquals(3, r.gaps.first { it.kind == GapKind.LINKS_OPEN }.count)
    }

    @Test fun `grade follows the percent and bounds hold`() {
        val r = ReadinessForecast.estimate(input(List(10) { solid() }, mocks = listOf(MockPoint(100.0, 0, 30))))
        assertEquals(MockExam.grade(r.percent!!.toDouble()), r.grade)
        assertTrue(r.high!! <= 100)
        val z = ReadinessForecast.estimate(input(List(10) { CardSchedule() }, mocks = listOf(MockPoint(0.0, 0, 10))))
        assertEquals(0, z.percent)
        assertEquals(0, z.low)
        assertEquals(6, z.grade)
    }

    @Test fun `mock only basis works without cards`() {
        val r = ReadinessForecast.estimate(input(emptyList(), mocks = listOf(MockPoint(80.0, 1, 10))))
        assertNotNull(r.percent)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }
}

class ErrorBookTest {
    private fun a(card: Long, day: Long, at: Long, right: Boolean) = Attempt(card, day, at, right)

    @Test fun `two failures in the first sitting are normal`() {
        assertTrue(ErrorBook.build(listOf(a(1, 10, 1, false), a(1, 10, 2, false), a(1, 10, 3, true))).isEmpty())
    }

    @Test fun `wrong on two days puts it on the list`() {
        val e = ErrorBook.build(listOf(a(1, 10, 1, false), a(1, 12, 2, false))).single()
        assertEquals(2, e.wrongDays)
        assertEquals(12, e.lastWrongDay)
    }

    @Test fun `three times wrong on one day puts it on the list too`() {
        assertEquals(1, ErrorBook.build(listOf(a(1, 10, 1, false), a(1, 10, 2, false), a(1, 10, 3, false))).size)
    }

    @Test fun `three right in a row clear it whatever the input order`() {
        val base = listOf(a(1, 10, 1, false), a(1, 11, 2, false))
        assertEquals(1, ErrorBook.build(base + a(1, 12, 3, true) + a(1, 13, 4, true)).size)
        assertTrue(ErrorBook.build((base + listOf(a(1, 12, 3, true), a(1, 13, 4, true), a(1, 14, 5, true))).shuffled()).isEmpty())
        // A slip in between resets the count.
        assertEquals(1, ErrorBook.build(base + listOf(a(1, 12, 3, true), a(1, 13, 4, true), a(1, 14, 5, false), a(1, 15, 6, true))).size)
    }

    @Test fun `worst first`() {
        val list = ErrorBook.build(
            listOf(a(1, 10, 1, false), a(1, 11, 2, false), a(2, 10, 3, false), a(2, 11, 4, false), a(2, 12, 5, false)),
        )
        assertEquals(listOf(2L, 1L), list.map { it.cardId })
    }

    @Test fun `only again is wrong`() {
        assertFalse(ErrorBook.isRight(Rating.AGAIN))
        assertTrue(ErrorBook.isRight(Rating.HARD))
    }
}

class FocusTimerTest {
    private val plan = FocusPlan(25, 5, 15, 4)

    @Test fun `first block counts down`() {
        val s = FocusTimer.at(plan, 60)
        assertFalse(s.inBreak)
        assertEquals(1, s.block)
        assertEquals(24 * 60, s.secondsLeft)
        assertEquals(60, s.focusSeconds)
    }

    @Test fun `break follows and does not count as focus`() {
        val s = FocusTimer.at(plan, 26 * 60L)
        assertTrue(s.inBreak)
        assertFalse(s.longBreak)
        assertEquals(1, s.block)
        assertEquals(4 * 60, s.secondsLeft)
        assertEquals(25 * 60, s.focusSeconds)
    }

    @Test fun `second block starts after the break`() {
        val s = FocusTimer.at(plan, 30 * 60L)
        assertFalse(s.inBreak)
        assertEquals(2, s.block)
        assertEquals(25 * 60, s.focusSeconds)
    }

    @Test fun `every fourth break is long`() {
        val end = 4 * 25 * 60L + 3 * 5 * 60L
        val s = FocusTimer.at(plan, end + 1)
        assertTrue(s.longBreak)
        assertEquals(4, s.block)
        assertEquals(15 * 60 - 1, s.secondsLeft)
        assertEquals(5, FocusTimer.at(plan, end + 15 * 60).block)
    }

    @Test fun `negative time is the start and no break plans work`() {
        assertEquals(1, FocusTimer.at(plan, -5).block)
        assertEquals(2, FocusTimer.at(FocusPlan(25, 0, 0, 4), 25 * 60L).block)
    }
}

class StatisticsTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val d = today.toEpochDay()

    @Test fun `days include gaps and the span ends today`() {
        val s = StudyStatistics.summary(listOf(StatAnswer(d, 1, true), StatAnswer(d - 2, 1, false)), emptyMap(), today, 7)
        assertEquals(7, s.days.size)
        assertEquals(today, s.days.last().date)
        assertEquals(1, s.days.last().answers)
        assertEquals(100, s.days.last().percent)
        assertEquals(0, s.days[5].answers)
        assertNull(s.days[5].percent)
        assertEquals(2, s.activeDays)
    }

    @Test fun `older answers stay out`() {
        val s = StudyStatistics.summary(listOf(StatAnswer(d - 30, 1, true)), emptyMap(), today, 7)
        assertEquals(0, s.answers)
    }

    @Test fun `hit rate per deck weakest first`() {
        val s = StudyStatistics.summary(
            listOf(StatAnswer(d, 1, true), StatAnswer(d, 1, true), StatAnswer(d, 2, true), StatAnswer(d, 2, false)),
            emptyMap(), today, 7,
        )
        assertEquals(listOf(2L, 1L), s.decks.map { it.deckId })
        assertEquals(50, s.decks[0].percent)
        assertEquals(3, s.right)
    }

    @Test fun `focus counts as activity and adds up`() {
        val s = StudyStatistics.summary(emptyList(), mapOf(d to 25, d - 1 to 50, d - 10 to 99), today, 7)
        assertEquals(75, s.focusMinutes)
        assertEquals(2, s.activeDays)
        assertEquals(2, s.bestStreak)
    }
}

class RemindersTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun `later today or tomorrow`() {
        val now = ZonedDateTime.of(2026, 11, 5, 16, 0, 0, 0, zone)
        assertEquals(3_600_000, Reminders.delayMillis(now, 17 * 60))
        assertEquals(23 * 3_600_000L, Reminders.delayMillis(now, 15 * 60))
        assertEquals(24 * 3_600_000L, Reminders.delayMillis(now, 16 * 60), "exactly now means tomorrow")
    }

    @Test fun `daylight saving is respected`() {
        val beforeSpring = ZonedDateTime.of(2027, 3, 27, 18, 0, 0, 0, zone) // clocks jump the next night
        assertEquals(23 * 3_600_000L, Reminders.delayMillis(beforeSpring, 18 * 60))
    }

    @Test fun `silent when there is nothing to say`() {
        assertNull(Reminders.daily(0, 0, 0, null, null))
        assertNull(Reminders.daily(0, 5, 3, null, null), "already learnt today, only new ones left")
    }

    @Test fun `daily text says what is open and a close exam`() {
        assertEquals("3 Karten fällig, 2 neue", Reminders.daily(3, 2, 0, null, null)?.body)
        assertEquals("1 Karte fällig · Mathe in 3 Tagen", Reminders.daily(1, 0, 0, "Mathe", 3)?.body)
        assertEquals("1 Karte fällig", Reminders.daily(1, 0, 0, "Mathe", 30)?.body)
    }

    @Test fun `exam warning only on the set days`() {
        assertEquals("Klassenarbeit morgen", Reminders.exam("LF 3", "Klassenarbeit", 1, null)?.title)
        assertEquals("LF 3. Prognose: 70 %.", Reminders.exam("LF 3", "Test", 7, 70)?.body)
        assertNull(Reminders.exam("LF 3", "Test", 5, null))
        assertNull(Reminders.exam("LF 3", "Test", -1, null))
    }
}
