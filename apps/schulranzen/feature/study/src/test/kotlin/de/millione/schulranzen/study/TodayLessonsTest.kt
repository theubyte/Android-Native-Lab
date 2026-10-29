package de.millione.schulranzen.study

import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.study.data.Lesson
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TodayLessonsTest {
    private val periods = Timetable.DEFAULT_PERIODS
    private val thursday = LocalDate.of(2026, 10, 29)

    private fun lesson(day: Int, period: Int, subject: String, cycle: String = "EVERY", room: String = "") =
        Lesson(day = day, period = period, subject = subject, colorIndex = 0, cycle = cycle, room = room)

    @Test fun `only today's lessons, in period order`() {
        val all = listOf(lesson(4, 3, "Mathe"), lesson(4, 1, "LF 3"), lesson(3, 1, "Deutsch"))
        val today = StudyRepository.lessonsOn(thursday, all, periods, TimetableSettings())
        assertEquals(listOf("LF 3", "Mathe"), today.map { it.lesson.subject })
        assertEquals(7 * 60 + 45, today.first().period?.startMinute)
    }

    @Test fun `a and b weeks filter only when switched on`() {
        val all = listOf(lesson(4, 1, "Werkstatt", "A"), lesson(4, 1, "Politik", "B"))
        val anchorA = thursday.toEpochDay()
        assertEquals(listOf("Werkstatt"), StudyRepository.lessonsOn(thursday, all, periods, TimetableSettings(abWeeks = true, anchorEpochDay = anchorA)).map { it.lesson.subject })
        assertEquals(listOf("Politik"), StudyRepository.lessonsOn(thursday.plusWeeks(1), all, periods, TimetableSettings(abWeeks = true, anchorEpochDay = anchorA)).map { it.lesson.subject })
        assertEquals(2, StudyRepository.lessonsOn(thursday, all, periods, TimetableSettings()).size)
    }

    @Test fun `double lessons merge into one block`() {
        val all = listOf(lesson(4, 1, "LF 3", room = "W1"), lesson(4, 2, "LF 3", room = "W1"), lesson(4, 3, "LF 3", room = "B2"), lesson(4, 5, "Mathe"))
        val blocks = blocksOf(StudyRepository.lessonsOn(thursday, all, periods, TimetableSettings()))
        assertEquals(listOf("1.–2.", "3.", "5."), blocks.map { it.label })
        assertEquals(8 * 60 + 30 + 45, blocks.first().end)
    }
}
