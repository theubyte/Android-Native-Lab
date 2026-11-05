package de.millione.schulranzen.core.scan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanEnhanceTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun lum(p: Int) = p and 255

    /** Paper falling off from bright to dim from left to right, with a dark word in each half. */
    private fun sheet(w: Int, h: Int, ink: Int = 30): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val paper = 220 - 110 * x / w
        val isInk = y in h / 2..h / 2 + 4 && (x in w / 8..w / 8 + 40 || x in w * 3 / 4..w * 3 / 4 + 40)
        gray(if (isInk) ink * paper / 220 else paper)
    }

    @Test fun originalChangesNothing() {
        val a = sheet(200, 200)
        val b = a.copyOf()
        ScanEnhance.apply(b, 200, 200, ScanMode.ORIGINAL)
        assertContentEquals(a, b)
    }

    @Test fun unevenPaperBecomesEvenlyWhite() {
        val w = 400; val h = 400
        val a = sheet(w, h)
        ScanEnhance.apply(a, w, h, ScanMode.BRIGHT)
        // Far from the writing, both ends of the falloff are nearly the same white.
        val left = lum(a[20 * w + 20]); val right = lum(a[20 * w + w - 20])
        assertTrue(left > 235, "links $left")
        assertTrue(right > 235, "rechts $right")
        assertTrue(kotlin.math.abs(left - right) < 16, "$left / $right")
    }

    @Test fun inkStaysDarkEvenInTheShadow() {
        val w = 400; val h = 400
        val a = sheet(w, h)
        ScanEnhance.apply(a, w, h, ScanMode.BRIGHT)
        assertTrue(lum(a[(h / 2 + 2) * w + w / 8 + 20]) < 90)
        assertTrue(lum(a[(h / 2 + 2) * w + w * 3 / 4 + 20]) < 90)
    }

    @Test fun blackAndWhiteSeparatesPaperAndInk() {
        val w = 400; val h = 400
        val a = sheet(w, h)
        ScanEnhance.apply(a, w, h, ScanMode.BLACK_WHITE)
        assertEquals(255, lum(a[20 * w + 20]))
        assertEquals(0, lum(a[(h / 2 + 2) * w + w * 3 / 4 + 20]))
        // Grey means every channel alike.
        val p = a[20 * w + w / 2]
        assertEquals(p and 255, p shr 8 and 255)
        assertEquals(p and 255, p shr 16 and 255)
    }

    @Test fun colourSurvivesBrightening() {
        val w = 200; val h = 200
        val red = (0xFF shl 24) or (170 shl 16) or (30 shl 8) or 30
        val a = IntArray(w * h) { i -> if (i % w in 90..110 && i / w in 90..110) red else gray(210) }
        ScanEnhance.apply(a, w, h, ScanMode.BRIGHT)
        val p = a[100 * w + 100]
        assertTrue((p shr 16 and 255) > (p and 255) + 80, "bleibt rot")
    }

    @Test fun alphaIsKept() {
        val a = IntArray(64 * 64) { 0x80C0C0C0.toInt() }
        ScanEnhance.apply(a, 64, 64, ScanMode.BRIGHT)
        assertEquals(0x80, a[0] ushr 24)
    }

    @Test fun extremeSizesAndBlackImagesDoNotBreak() {
        for ((w, h) in listOf(1 to 1, 1 to 500, 500 to 1, 7 to 3)) {
            val a = IntArray(w * h) { gray(0) }
            ScanEnhance.apply(a, w, h, ScanMode.BRIGHT)
            ScanEnhance.apply(a, w, h, ScanMode.BLACK_WHITE)
        }
        ScanEnhance.apply(IntArray(0), 0, 0, ScanMode.BRIGHT)
    }
}
