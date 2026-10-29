package de.millione.schulranzen.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI

class MathEngineTest {
    private fun eval(s: String, angle: AngleMode = AngleMode.DEG, ans: Double = 0.0, hw: Boolean = false) =
        MathEngine.evaluate(s, angle, ans, hw).value

    private fun check(expected: Double, s: String, angle: AngleMode = AngleMode.DEG, ans: Double = 0.0, hw: Boolean = false) =
        assertEquals(s, expected, eval(s, angle, ans, hw), 1e-9)

    private fun error(s: String, angle: AngleMode = AngleMode.DEG): String {
        try {
            MathEngine.evaluate(s, angle)
        } catch (e: MathException) {
            return e.message!!
        }
        fail("expected error for $s")
        throw AssertionError()
    }

    @Test fun referenceTaskFromV15() = check(1232.0, "336*(55:15)")

    @Test fun basicOperators() {
        check(7.0, "3+4")
        check(-1.0, "3-4")
        check(12.0, "3*4")
        check(2.5, "5/2")
        check(14.0, "2+3*4")
        check(20.0, "(2+3)*4")
        check(1.0, "8/4/2")
        check(5.0, "10-3-2")
    }

    @Test fun schoolSymbols() {
        check(12.0, "3×4")
        check(12.0, "3·4")
        check(12.0, "3∙4")
        check(3.0, "12÷4")
        check(3.0, "12:4")
        check(3.0, "5−2")
        check(3.0, "5–2")
        check(10.0, "[2+3]·2")
        check(10.0, "{2+3}·2")
    }

    @Test fun xAsTimes() {
        check(12.0, "3x4")
        check(12.0, "3 X 4")
        check(9.0, "3x(1+2)")
    }

    @Test fun decimalCommaAndPoint() {
        check(5.0, "2,5+2.5")
        check(0.5, ",5")
        check(1234.5, "1.234,5")
        check(1234.5, "1,234.5")
        check(1_000_001.0, "1.000.000+1")
        check(2_000_000.0, "2,000,000")
        assertTrue(error("1,2,3").contains("Ungültige Zahl"))
        assertTrue(error("1.2,3,4").contains("Ungültige Zahl"))
    }

    @Test fun powersAndRoots() {
        check(4.0, "2²")
        check(8.0, "2³")
        check(0.01, "10⁻²")
        check(1024.0, "2^10")
        check(512.0, "2^3^2")
        check(-4.0, "-2^2")
        check(4.0, "(-2)^2")
        check(0.5, "2^-1")
        check(3.0, "√9")
        check(4.0, "√(16)")
        check(6.0, "2√9")
        check(5.0, "sqrt(9)+2")
        check(3.0, "wurzel(9)")
        check(-2.0, "(-8)^(1/3)")
        check(3.0, "∛27")
        assertTrue(error("√(-4)").contains("negativen"))
        assertTrue(error("(-8)^0,5").contains("reelle"))
    }

    @Test fun percent() {
        check(0.5, "50%")
        check(220.0, "200+10%")
        check(180.0, "200-10%")
        check(20.0, "200*10%")
        check(20.0, "10%*200")
        check(2.1, "(200+10)%")
    }

    @Test fun constantsAndImplicitMultiplication() {
        check(PI, "π")
        check(PI, "pi")
        check(2 * PI, "2π")
        check(E, "e")
        check(25.0, "(2+3)(4+1)")
        check(6.0, "2(3)")
        check(PI * PI, "ππ")
    }

    @Test fun trigDegrees() {
        check(0.5, "sin(30)")
        check(0.5, "cos(60)")
        check(1.0, "tan(45)")
        check(0.0, "sin(180)")
        check(-1.0, "sin(270)")
        check(0.0, "cos(90)")
        check(30.0, "asin(0.5)")
        check(60.0, "arccos(0,5)")
        check(45.0, "atan(1)")
        check(0.5, "sin30")
        assertEquals("0,5", MathEngine.evaluate("sin(30)").text)
        assertTrue(error("tan(90)").contains("nicht definiert"))
        assertTrue(error("asin(2)").contains("−1 bis 1"))
    }

    @Test fun trigRadians() {
        check(1.0, "sin(π/2)", AngleMode.RAD)
        check(-1.0, "cos(π)", AngleMode.RAD)
        check(PI / 6, "asin(0,5)", AngleMode.RAD)
        assertEquals(0.0, eval("sin(π)", AngleMode.RAD), 1e-12)
        assertTrue(error("tan(π/2)", AngleMode.RAD).contains("nicht definiert"))
    }

    @Test fun logarithms() {
        check(1.0, "ln(e)")
        check(3.0, "log(1000)")
        check(2.0, "lg(100)")
        assertTrue(error("ln(0)").contains("größer als 0"))
        assertTrue(error("log(-1)").contains("größer als 0"))
    }

    @Test fun factorialAndAbs() {
        check(120.0, "5!")
        check(1.0, "0!")
        check(5.0, "abs(-5)")
        assertTrue(error("2,5!").contains("Fakultät"))
    }

    @Test fun ans() {
        check(42.0, "ANS*2", ans = 21.0)
        check(42.0, "2ans", ans = 21.0)
    }

    @Test fun equalsSign() {
        check(7.0, "3+4=")
        check(7.0, "3+4=7")
        check(7.0, "3 + 4 = ")
    }

    @Test fun brackets() {
        check(3.0, "(1+2")
        check(9.0, "3*(1+(1+1")
        assertTrue(error("1+2)").contains("schließende"))
        assertTrue(error("()").contains("Leere Klammer"))
    }

    @Test fun errors() {
        assertTrue(error("").contains("Keine Rechnung"))
        assertTrue(error("1/0").contains("Division durch 0"))
        assertTrue(error("3+").contains("fehlt"))
        assertTrue(error("*3").contains("fehlt eine Zahl"))
        assertTrue(error("2^").contains("Exponent"))
        assertTrue(error("3+abc").contains("Unbekannt"))
        assertTrue(error("3#4").contains("Unbekanntes Zeichen"))
        assertTrue(error("10^400").contains("zu groß"))
        assertTrue(error("sin").contains("fehlt"))
    }

    @Test fun tryEvaluateNeverThrows() {
        assertTrue(MathEngine.tryEvaluate("1+") is MathOutcome.Error)
        assertTrue(MathEngine.tryEvaluate("1+1") is MathOutcome.Ok)
        assertTrue(MathEngine.tryEvaluate("((((") is MathOutcome.Error)
    }

    @Test fun handwritingFixes() {
        check(15.0, "1O+5", hw = true)
        check(15.0, "l2+3", hw = true)
        check(0.75, "3;4", hw = true)
        check(110.0, "1o0+10", hw = true)
        check(60.0, "2 mal 30")
    }

    @Test fun prettyExpression() {
        val r = MathEngine.evaluate("336*(55:15)")
        assertEquals("336*(55/15)", r.expression)
        assertEquals("336×(55÷15)", r.prettyExpression)
        assertEquals("√9−π×2,5", MathEngine.pretty(MathEngine.normalize("√9-π*2,5")))
    }

    @Test fun solveHandwritingPicksPlausibleCandidate() {
        assertEquals(1232.0, MathEngine.solveHandwriting(listOf("336(55:15)"))!!.value, 1e-9)
        assertEquals(19.0, MathEngine.solveHandwriting(listOf("12 + 7 ="))!!.value, 1e-9)
        assertEquals(672.0, MathEngine.solveHandwriting(listOf("33b x 2"))!!.value, 1e-9)
        assertEquals(12.0, MathEngine.solveHandwriting(listOf("Hallo", "3×4"))!!.value, 1e-9)
        assertEquals(10.0, MathEngine.solveHandwriting(listOf("5t5"))!!.value, 1e-9)
        assertEquals(1232.0, MathEngine.solveHandwriting(listOf("336*(55:15)\nNotiz"))!!.value, 1e-9)
    }

    @Test fun solveHandwritingRejectsNonCalculations() {
        assertNull(MathEngine.solveHandwriting(listOf("Hallo")))
        assertNull(MathEngine.solveHandwriting(listOf("42")))
        assertNull(MathEngine.solveHandwriting(listOf("a+b")))
        assertNull(MathEngine.solveHandwriting(emptyList()))
    }
}
