package de.millione.schulranzen.rechnen

import de.millione.schulranzen.core.math.AngleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorModelTest {
    private fun CalculatorModel.type(vararg keys: String) = keys.forEach { k ->
        when (k) {
            in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") -> digit(k)
            "," -> comma()
            "+", "−", "×", "÷", "^", "%", "!", ")", "²" -> operator(Piece(k))
            else -> insert(Piece(k))
        }
    }

    @Test fun referenceTask() {
        val m = CalculatorModel()
        m.type("3", "3", "6", "×", "(", "5", "5", "÷", "1", "5", ")")
        val e = m.equals(AngleMode.DEG, 0.0)
        assertNotNull(e)
        assertEquals(1232.0, e!!.value, 1e-9)
        assertEquals("1232", m.result)
    }

    @Test fun operatorAfterEqualsContinuesWithAns() {
        val m = CalculatorModel()
        m.type("6", "×", "7")
        val ans = m.equals(AngleMode.DEG, 0.0)!!.value
        m.type("+", "1")
        assertEquals("ANS+1", m.displayText)
        assertEquals(43.0, m.equals(AngleMode.DEG, ans)!!.value, 1e-9)
    }

    @Test fun digitAfterEqualsStartsFresh() {
        val m = CalculatorModel()
        m.type("2", "+", "2"); m.equals(AngleMode.DEG, 0.0)
        m.type("5")
        assertEquals("5", m.displayText)
    }

    @Test fun inverseTrigUsesEngineSpelling() {
        val m = CalculatorModel()
        m.insert(Piece("sin⁻¹(", "asin("))
        m.type("0", ",", "5")
        assertEquals("sin⁻¹(0,5", m.displayText)
        assertEquals(30.0, m.equals(AngleMode.DEG, 0.0)!!.value, 1e-9)
    }

    @Test fun backspaceRemovesWholeFunction() {
        val m = CalculatorModel()
        m.type("2", "×")
        m.insert(Piece("sin("))
        m.backspace()
        assertEquals("2×", m.displayText)
    }

    @Test fun errorIsShownAndInputKept() {
        val m = CalculatorModel()
        m.type("1", "÷", "0")
        assertNull(m.equals(AngleMode.DEG, 0.0))
        assertEquals("1÷0", m.displayText)
        assertNotNull(m.error)
    }

    @Test fun previewOnlyForCalculations() {
        val m = CalculatorModel()
        m.type("1", "2")
        assertNull(m.preview(AngleMode.DEG, 0.0))
        m.type("+", "3")
        assertEquals("15", m.preview(AngleMode.DEG, 0.0))
    }

    @Test fun commaAloneBecomesZeroComma() {
        val m = CalculatorModel()
        m.type(",", "5")
        assertEquals("0,5", m.displayText)
    }
}
