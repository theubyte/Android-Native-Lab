package de.millione.schulranzen.core.study

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepetitionTest {
    private val day0 = LocalDate.of(2026, 10, 29)

    private fun CardSchedule.answer(r: Rating, on: LocalDate, exam: LocalDate? = null) = Repetition.review(this, r, on, exam)
    private fun CardSchedule.dueDate() = LocalDate.ofEpochDay(dueEpochDay)

    @Test fun `new card is due at once`() {
        val s = CardSchedule()
        assertTrue(s.isNew)
        assertTrue(s.isDue(day0))
        assertTrue(s.isDue(LocalDate.of(1970, 1, 1)))
    }

    @Test fun `first answers give short school steps`() {
        val s = CardSchedule()
        assertEquals(0, s.answer(Rating.AGAIN, day0).intervalDays)
        assertEquals(1, s.answer(Rating.HARD, day0).intervalDays)
        assertEquals(1, s.answer(Rating.GOOD, day0).intervalDays)
        assertEquals(3, s.answer(Rating.EASY, day0).intervalDays)
        val second = s.answer(Rating.GOOD, day0)
        assertEquals(3, second.answer(Rating.GOOD, day0.plusDays(1)).intervalDays)
        assertEquals(2, second.answer(Rating.HARD, day0.plusDays(1)).intervalDays)
        assertEquals(5, second.answer(Rating.EASY, day0.plusDays(1)).intervalDays)
    }

    @Test fun `good answers grow by the ease`() {
        var s = CardSchedule()
        var d = day0
        val intervals = mutableListOf<Int>()
        repeat(6) {
            s = s.answer(Rating.GOOD, d)
            intervals += s.intervalDays
            d = s.dueDate()
        }
        assertEquals(listOf(1, 3, 8, 20, 50, 125), intervals)
        assertEquals(Repetition.START_EASE, s.ease, 1e-9)
        assertEquals(0, s.lapses)
    }

    @Test fun `answers are strictly ordered in the review phase`() {
        val s = CardSchedule(reps = 3, intervalDays = 10, ease = 2.5, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(10).toEpochDay())
        val p = Repetition.preview(s, day0)
        assertEquals(0, p[Rating.AGAIN])
        assertTrue(p[Rating.HARD]!! < p[Rating.GOOD]!!)
        assertTrue(p[Rating.GOOD]!! < p[Rating.EASY]!!)
        assertEquals(12, p[Rating.HARD])
        assertEquals(25, p[Rating.GOOD])
        assertEquals(constrainedEasy(10, 0, 2.65), p[Rating.EASY])
    }

    private fun constrainedEasy(interval: Int, delay: Int, ease: Double) = Math.round((interval + delay) * ease * 1.3).toInt()

    @Test fun `hard on a tiny interval still moves forward`() {
        val s = CardSchedule(reps = 2, intervalDays = 1, ease = 1.3, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(1).toEpochDay())
        val p = Repetition.preview(s, day0)
        assertEquals(2, p[Rating.HARD])
        assertEquals(3, p[Rating.GOOD])
        assertEquals(4, p[Rating.EASY])
    }

    @Test fun `forgetting resets and counts a lapse`() {
        val s = CardSchedule(reps = 4, intervalDays = 20, ease = 2.5, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(20).toEpochDay())
        val a = s.answer(Rating.AGAIN, day0)
        assertEquals(0, a.reps)
        assertEquals(0, a.intervalDays)
        assertEquals(1, a.lapses)
        assertEquals(2.3, a.ease, 1e-9)
        assertTrue(a.isDue(day0), "a forgotten card comes back the same day")
        // Relearning starts with the short steps again.
        assertEquals(1, a.answer(Rating.GOOD, day0).intervalDays)
    }

    @Test fun `again on a new card is not a lapse`() {
        assertEquals(0, CardSchedule().answer(Rating.AGAIN, day0).lapses)
    }

    @Test fun `ease stays within bounds`() {
        var s = CardSchedule()
        repeat(30) { s = s.answer(Rating.HARD, day0) }
        assertEquals(Repetition.MIN_EASE, s.ease, 1e-9)
        repeat(30) { s = s.answer(Rating.EASY, day0) }
        assertEquals(Repetition.MAX_EASE, s.ease, 1e-9)
    }

    @Test fun `interval is capped`() {
        var s = CardSchedule()
        var d = day0
        repeat(20) { s = s.answer(Rating.EASY, d); d = s.dueDate() }
        assertEquals(Repetition.MAX_INTERVAL, s.intervalDays)
    }

    @Test fun `late review earns a longer interval`() {
        val onTime = CardSchedule(reps = 3, intervalDays = 10, ease = 2.5, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(10).toEpochDay())
        val late = onTime.copy(lastReviewEpochDay = day0.minusDays(20).toEpochDay())
        assertEquals(25, onTime.answer(Rating.GOOD, day0).intervalDays)
        assertEquals(38, late.answer(Rating.GOOD, day0).intervalDays) // (10 + 10/2) * 2.5 = 37.5
    }

    @Test fun `exam date pulls the next review before the exam`() {
        val s = CardSchedule(reps = 3, intervalDays = 10, ease = 2.5, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(10).toEpochDay())
        val exam = day0.plusDays(6)
        val r = s.answer(Rating.GOOD, day0, exam)
        assertEquals(exam.minusDays(1), r.dueDate())
        assertEquals(25, r.intervalDays, "the interval itself is kept for after the exam")
    }

    @Test fun `exam tomorrow still schedules on a later day`() {
        val r = CardSchedule().answer(Rating.GOOD, day0, day0.plusDays(1))
        assertEquals(day0.plusDays(1), r.dueDate())
    }

    @Test fun `past exam does not cap`() {
        val r = CardSchedule().answer(Rating.EASY, day0, day0.minusDays(3))
        assertEquals(day0.plusDays(3), r.dueDate())
    }

    @Test fun `due check is by calendar day`() {
        val s = CardSchedule().answer(Rating.GOOD, day0)
        assertFalse(s.isDue(day0))
        assertTrue(s.isDue(day0.plusDays(1)))
        assertTrue(s.isDue(day0.plusDays(30)))
    }

    @Test fun `time zone change keeps the due day`() {
        // Reviewed late in the evening in Berlin; the calendar day decides, not the instant.
        val berlin = ZonedDateTime.of(2026, 3, 28, 23, 30, 0, 0, ZoneId.of("Europe/Berlin"))
        val s = CardSchedule().answer(Rating.GOOD, berlin.toLocalDate())
        val nextMorningDst = berlin.plusHours(9).withZoneSameInstant(ZoneId.of("Europe/Berlin")) // across the DST switch
        assertTrue(s.isDue(nextMorningDst.toLocalDate()))
        val sameInstantInNewYork = berlin.withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate()
        assertFalse(s.isDue(sameInstantInNewYork))
    }

    @Test fun `queue puts overdue cards first and new cards last`() {
        data class C(val id: String, val s: CardSchedule)
        val cards = listOf(
            C("new", CardSchedule()),
            C("today", CardSchedule(reps = 1, intervalDays = 1, dueEpochDay = day0.toEpochDay(), lastReviewEpochDay = day0.minusDays(1).toEpochDay())),
            C("future", CardSchedule(reps = 1, intervalDays = 3, dueEpochDay = day0.plusDays(2).toEpochDay(), lastReviewEpochDay = day0.toEpochDay())),
            C("overdue", CardSchedule(reps = 2, intervalDays = 3, dueEpochDay = day0.minusDays(4).toEpochDay(), lastReviewEpochDay = day0.minusDays(7).toEpochDay())),
        )
        assertEquals(listOf("overdue", "today", "new"), Repetition.queue(cards, day0) { it.s }.map { it.id })
    }
}
