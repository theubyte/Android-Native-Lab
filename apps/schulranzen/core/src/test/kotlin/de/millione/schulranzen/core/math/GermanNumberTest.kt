package de.millione.schulranzen.core.math

import org.junit.Assert.assertEquals
import org.junit.Test

class GermanNumberTest {
    private val g = GermanNumber.GROUP
    private val m = GermanNumber.MINUS

    @Test fun integersStayPlain() {
        assertEquals("1232", GermanNumber.format(1232.0))
        assertEquals("0", GermanNumber.format(0.0))
        assertEquals("0", GermanNumber.format(-0.0))
        assertEquals("${m}5", GermanNumber.format(-5.0))
    }

    @Test fun decimalComma() {
        assertEquals("2,5", GermanNumber.format(2.5))
        assertEquals("0,3", GermanNumber.format(0.1 + 0.2))
        assertEquals("0,333333333333", GermanNumber.format(1.0 / 3.0))
        assertEquals("0,666666666667", GermanNumber.format(2.0 / 3.0))
        assertEquals("3,14159265359", GermanNumber.format(Math.PI))
    }

    @Test fun grouping() {
        assertEquals("12${g}345", GermanNumber.format(12345.0))
        assertEquals("1${g}234${g}567,5", GermanNumber.format(1234567.5))
        assertEquals("1234567,5", GermanNumber.format(1234567.5, grouping = false))
    }

    @Test fun scientific() {
        assertEquals("1×10¹⁵", GermanNumber.format(1e15))
        assertEquals("1,5×10⁻⁷", GermanNumber.format(1.5e-7))
        assertEquals("${m}2,5×10¹³", GermanNumber.format(-2.5e13))
    }

    @Test fun plainCanBeReadBack() {
        assertEquals("-1234,5", GermanNumber.formatPlain(-1234.5))
        val back = MathEngine.evaluate(GermanNumber.formatPlain(-1234.5)).value
        assertEquals(-1234.5, back, 0.0)
        assertEquals(1.0 / 3.0, MathEngine.evaluate(GermanNumber.format(1.0 / 3.0)).value, 1e-12)
    }

    @Test fun fixedDecimals() {
        assertEquals("7,17", GermanNumber.format(7.16537, 2))
        assertEquals("10", GermanNumber.format(10.0001, 2))
        assertEquals("0", GermanNumber.format(0.0001, 2))
        assertEquals("1000", GermanNumber.format(999.9999, 2))
        assertEquals("12${g}345,7", GermanNumber.format(12345.678, 1))
        assertEquals("${m}3,5", GermanNumber.format(-3.5, 1))
    }
}
