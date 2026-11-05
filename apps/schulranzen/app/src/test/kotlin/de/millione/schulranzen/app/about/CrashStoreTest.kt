package de.millione.schulranzen.app.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(keep: Int = 5) = CrashStore(tmp.newFolder(), keep)

    @Test fun `new report is open and newest wins`() {
        val s = store()
        assertNull(s.open())
        s.write("eins", 100)
        s.write("zwei", 200)
        assertEquals("zwei", s.open()?.readText())
    }

    @Test fun `answered reports stop asking but stay available`() {
        val s = store()
        s.write("eins", 100)
        s.markAllSeen()
        assertNull(s.open())
        assertEquals("eins", s.latest()?.readText())
    }

    @Test fun `only the newest reports are kept`() {
        val s = store(keep = 2)
        (1L..5L).forEach { s.write("r$it", it) }
        assertEquals(2, s.count())
        assertEquals("r5", s.latest()?.readText())
    }

    @Test fun `report contains header and trace`() {
        val h = ReportHeader("0.5.0", 50000, "release", "Samsung SM-T733", "14 (API 34)")
        val text = CrashFormat.report(h, "Absturz", "05.11.2026 10:00:00", "java.lang.IllegalStateException: kaputt")
        assertTrue("0.5.0" in text && "SM-T733" in text && "IllegalStateException" in text)
    }

    @Test fun `logins and keys never reach the report`() {
        val out = CrashFormat.scrub("login anna.b@schule.de failed, key sk-ant-api03-abcDEF123 Bearer abc.def")
        assertFalse("anna" in out || "abcDEF" in out || "abc.def" in out)
    }

    @Test fun `huge traces are cut`() {
        val h = ReportHeader("0", 0, "d", "x", "y")
        val text = CrashFormat.report(h, "Absturz", "t", "a".repeat(CrashFormat.MAX_CHARS + 100))
        assertTrue(text.length < CrashFormat.MAX_CHARS + 300)
        assertTrue("[gekürzt]" in text)
    }
}
