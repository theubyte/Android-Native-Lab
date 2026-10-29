package de.millione.schulranzen.core.study

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExamPlannerTest {
    private val today = LocalDate.of(2026, 10, 29)

    @Test fun `plan gets denser towards the exam`() {
        val exam = today.plusDays(20)
        val plan = ExamPlanner.plan(today, exam)
        val before = plan.map { ExamPlanner.daysUntil(it.date, exam).toInt() }
        assertEquals(listOf(20, 16, 11, 7, 4, 2, 1), before)
        assertEquals(PlanPhase.START, plan.first().phase)
        assertEquals(PlanPhase.FINAL, plan.last().phase)
        assertTrue(plan.drop(1).dropLast(1).all { it.phase == PlanPhase.REVIEW })
    }

    @Test fun `plan never lies in the past or on the exam day`() {
        for (left in 1L..60L) {
            val exam = today.plusDays(left)
            val plan = ExamPlanner.plan(today, exam)
            assertTrue(plan.isNotEmpty())
            assertTrue(plan.all { it.date >= today && it.date < exam }, "left=$left")
            assertEquals(plan.map { it.date }.sorted(), plan.map { it.date })
            assertTrue(plan.size <= 8)
        }
    }

    @Test fun `exam tomorrow means one session today`() {
        val plan = ExamPlanner.plan(today, today.plusDays(1))
        assertEquals(listOf(PlanDay(today, PlanPhase.START)), plan)
    }

    @Test fun `no plan for an exam today or past`() {
        assertTrue(ExamPlanner.plan(today, today).isEmpty())
        assertTrue(ExamPlanner.plan(today, today.minusDays(5)).isEmpty())
    }

    @Test fun `daily cards spread new ones over the days left`() {
        assertEquals(5, ExamPlanner.cardsToday(5, 0, today, today.plusDays(3)))
        assertEquals(5 + 4, ExamPlanner.cardsToday(5, 20, today, today.plusDays(5)))
        assertEquals(20, ExamPlanner.cardsToday(0, 20, today, today))
        assertEquals(3, ExamPlanner.cardsToday(0, 20, today, null))
    }
}

class TimetableTest {
    @Test fun `default periods are ordered and do not overlap`() {
        val p = Timetable.DEFAULT_PERIODS
        assertEquals(10, p.size)
        p.zipWithNext().forEach { (a, b) -> assertTrue(a.endMinute <= b.startMinute) }
        assertEquals("07:45", Timetable.formatTime(p.first().startMinute))
    }

    @Test fun `times parse in common forms`() {
        assertEquals(465, Timetable.parseTime("7:45"))
        assertEquals(465, Timetable.parseTime("07:45"))
        assertEquals(465, Timetable.parseTime("0745"))
        assertEquals(465, Timetable.parseTime("7.45"))
        assertNull(Timetable.parseTime("25:00"))
        assertNull(Timetable.parseTime("7:5"))
        assertNull(Timetable.parseTime("abc"))
        assertNull(Timetable.parseTime(""))
    }

    @Test fun `status finds period, break and the ends of the day`() {
        val p = Timetable.DEFAULT_PERIODS
        assertEquals(DayStatus.BeforeSchool, Timetable.status(p, 7 * 60))
        assertEquals(DayStatus.InPeriod(1), Timetable.status(p, 7 * 60 + 45))
        assertEquals(DayStatus.InPeriod(2), Timetable.status(p, 8 * 60 + 30))
        assertEquals(DayStatus.Break(3), Timetable.status(p, 9 * 60 + 20))
        assertEquals(DayStatus.AfterSchool, Timetable.status(p, 17 * 60))
        assertEquals(DayStatus.AfterSchool, Timetable.status(emptyList(), 600))
    }

    @Test fun `a and b weeks alternate from the anchor`() {
        val anchor = LocalDate.of(2026, 10, 28) // Wednesday of an A week
        assertTrue(Timetable.isWeekA(LocalDate.of(2026, 10, 26), anchor))
        assertTrue(Timetable.isWeekA(LocalDate.of(2026, 11, 1), anchor)) // Sunday, same week
        assertTrue(!Timetable.isWeekA(LocalDate.of(2026, 11, 2), anchor))
        assertTrue(Timetable.isWeekA(LocalDate.of(2026, 11, 9), anchor))
        assertTrue(!Timetable.isWeekA(LocalDate.of(2026, 10, 19), anchor)) // before the anchor
        assertTrue(Timetable.isWeekA(LocalDate.of(2026, 10, 12), anchor))
        // Across the new year (ISO week numbers restart, the alternation must not)
        assertTrue(!Timetable.isWeekA(LocalDate.of(2026, 12, 28), anchor))
        assertTrue(Timetable.isWeekA(LocalDate.of(2027, 1, 4), anchor))
    }

    @Test fun `lessons by cycle`() {
        val anchor = LocalDate.of(2026, 10, 26)
        val bWeek = LocalDate.of(2026, 11, 3)
        assertTrue(Timetable.takesPlace(WeekCycle.EVERY, bWeek, anchor))
        assertTrue(!Timetable.takesPlace(WeekCycle.A, bWeek, anchor))
        assertTrue(Timetable.takesPlace(WeekCycle.B, bWeek, anchor))
        assertTrue(Timetable.takesPlace(WeekCycle.A, bWeek, null))
    }
}

class StudyStatsTest {
    private val today = LocalDate.of(2026, 10, 29)
    private fun days(vararg back: Long) = back.map { today.minusDays(it).toEpochDay() }.toSet()

    @Test fun `streak counts back from today`() {
        assertEquals(3, StudyStats.streak(days(0, 1, 2, 4), today))
    }

    @Test fun `streak from yesterday is still alive`() {
        assertEquals(2, StudyStats.streak(days(1, 2), today))
    }

    @Test fun `gap breaks the streak`() {
        assertEquals(0, StudyStats.streak(days(2, 3), today))
        assertEquals(0, StudyStats.streak(emptySet(), today))
    }

    @Test fun `mastery counts cards out of the learning phase`() {
        val s = listOf(CardSchedule(), CardSchedule(reps = 1), CardSchedule(reps = 2), CardSchedule(reps = 5))
        assertEquals(50, StudyStats.mastery(s))
        assertEquals(0, StudyStats.mastery(emptyList()))
    }
}
