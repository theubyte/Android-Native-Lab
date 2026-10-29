package de.millione.schulranzen.core.shk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShkTest {
    private fun ok(id: String, vararg values: Pair<String, Double?>): List<ShkOutput> {
        val r = ShkCalculators.byId(id)!!.calculate(values.toMap())
        assertTrue("$id: $r", r is ShkCalcResult.Ok)
        return (r as ShkCalcResult.Ok).outputs
    }

    // Hand-checked: 10 kW / (1,163 · 20 K) = 0,42992 m³/h = 7,1654 l/min
    @Test fun flowFromPower() {
        assertEquals(0.429923, Shk.flowFromPower(10.0, 20.0), 1e-6)
        val out = ok("flow_from_power", "p" to 10.0, "dt" to 20.0)
        assertEquals(7.16538, out[1].value, 1e-4)
        assertEquals("l/min", out[1].unit)
    }

    // 10 l/min = 0,6 m³/h; 1,163 · 0,6 · 20 = 13,956 kW
    @Test fun powerFromFlow() {
        assertEquals(13.956, ok("power_from_flow", "q" to 10.0, "dt" to 20.0)[0].value, 1e-9)
    }

    @Test fun flowAndPowerAreInverse() {
        val m3h = Shk.flowFromPower(15.0, 10.0)
        assertEquals(15.0, Shk.powerFromFlow(m3h, 10.0), 1e-9)
    }

    // π · 0,02² / 4 · 10 m = 3,1416 l
    @Test fun pipeVolume() {
        assertEquals(3.14159, Shk.pipeVolumeLitres(20.0, 10.0), 1e-5)
        val out = ok("pipe_volume", "d" to 20.0, "l" to 10.0)
        assertEquals(0.314159, out[1].value, 1e-6)
    }

    // 20 l/min = 3,333e-4 m³/s; A = 3,1416e-4 m² → 1,061 m/s
    @Test fun velocityAndDiameter() {
        assertEquals(1.061033, Shk.velocity(20.0, 20.0), 1e-6)
        assertEquals(20.0, Shk.innerDiameter(20.0, Shk.velocity(20.0, 20.0)), 1e-9)
    }

    // 200 l · 50 K · 1,163 / 1000 = 11,63 kWh; with 3 kW → 232,6 min
    @Test fun waterEnergyAndHeatingTime() {
        assertEquals(11.63, Shk.waterHeatKwh(200.0, 50.0), 1e-9)
        val t = ok("heating_time", "v" to 200.0, "dt" to 50.0, "p" to 3.0)
        assertEquals(232.6, t[0].value, 1e-9)
    }

    // 100 kg · 4,19 · 10 K = 4190 kJ = 1,1639 kWh
    @Test fun heatQuantity() {
        val out = ok("heat_quantity", "m" to 100.0, "c" to 4.19, "dt" to 10.0)
        assertEquals(4190.0, out[0].value, 1e-9)
        assertEquals(1.163889, out[1].value, 1e-6)
    }

    @Test fun mixingAllowsZeroAndNegativeTemperatures() {
        assertEquals(35.0, ok("mixing_temperature", "m1" to 10.0, "t1" to 60.0, "m2" to 10.0, "t2" to 10.0)[0].value, 1e-9)
        assertEquals(60.0, ok("mixing_temperature", "m1" to 1.0, "t1" to 0.0, "m2" to 3.0, "t2" to 80.0)[0].value, 1e-9)
        assertEquals(-2.5, ok("mixing_temperature", "m1" to 1.0, "t1" to -10.0, "m2" to 3.0, "t2" to 0.0)[0].value, 1e-9)
    }

    // 1,4 · 12 · 32 = 537,6 W; Kupfer 10 m, 60 K → 9,96 mm
    @Test fun transmissionAndExpansion() {
        assertEquals(537.6, ok("transmission", "u" to 1.4, "a" to 12.0, "dt" to 32.0)[0].value, 1e-9)
        assertEquals(9.96, ok("expansion", "l" to 10.0, "a" to 0.0166, "dt" to 60.0)[0].value, 1e-9)
    }

    // 1 bar = 100000 / 9806,65 = 10,19716 mWS
    @Test fun pressureHead() {
        assertEquals(10.19716, Shk.barToMws(1.0), 1e-5)
        assertEquals(1.0, Shk.mwsToBar(10.19716213), 1e-8)
        assertEquals(1.5, ok("head_to_pressure", "h" to Shk.barToMws(1.5))[0].value, 1e-9)
    }

    // 3,6 m³/h = 0,001 m³/s; 1000 · 9,80665 · 0,001 · 10 / 0,5 = 196,133 W
    @Test fun pumpPower() {
        assertEquals(196.133, Shk.pumpPowerW(3.6, 10.0, 50.0), 1e-9)
    }

    // √3 · 400 · 16 = 11085,1 W; 21 kW / (√3 · 400) = 30,31 A
    @Test fun electric() {
        assertEquals(11085.125, Shk.power3p(400.0, 16.0, 1.0), 1e-3)
        assertEquals(30.3109, ok("current_3p", "p" to 21.0, "u" to 400.0, "cos" to 1.0)[0].value, 1e-4)
        assertEquals(3680.0, ok("power_1p", "u" to 230.0, "i" to 16.0, "cos" to 1.0)[0].value, 1e-9)
        assertEquals(230.0, ok("ohm", "r" to 23.0, "i" to 10.0)[0].value, 1e-9)
    }

    @Test fun basics() {
        assertEquals(92.0, ok("efficiency", "out" to 23.0, "in" to 25.0)[0].value, 1e-9)
        assertEquals(2.0, ok("gradient", "h" to 20.0, "l" to 10.0)[0].value, 1e-9)
        assertEquals(500.0, ok("offset", "a" to 300.0, "b" to 400.0)[0].value, 1e-9)
    }

    @Test fun validation() {
        val calc = ShkCalculators.byId("flow_from_power")!!
        assertEquals(ShkCalcResult.Incomplete, calc.calculate(mapOf("p" to 10.0, "dt" to null)))
        assertTrue(calc.calculate(mapOf("p" to 10.0, "dt" to 0.0)) is ShkCalcResult.Invalid)
        assertTrue(calc.calculate(mapOf("p" to -1.0, "dt" to 10.0)) is ShkCalcResult.Invalid)
        assertTrue(ShkCalculators.byId("efficiency")!!.calculate(mapOf("out" to 30.0, "in" to 25.0)) is ShkCalcResult.Invalid)
    }

    @Test fun calculatorsAreConsistent() {
        val ids = ShkCalculators.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ShkCalculators.all.forEach { c ->
            assertTrue(c.inputs.isNotEmpty())
            assertEquals(c.inputs.size, c.inputs.map { it.key }.toSet().size)
        }
        // Every calculator from the V15 SHK tools exists.
        listOf("flow_from_power", "power_from_flow", "pipe_volume", "flow_velocity", "water_energy", "pressure_to_head", "head_to_pressure")
            .forEach { assertNotNull(it, ShkCalculators.byId(it)) }
    }

    @Test fun formulasLinkToExistingCalculators() {
        val ids = Formulas.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Formulas.all.forEach { f ->
            f.calculatorId?.let { assertNotNull("${f.id} → $it", ShkCalculators.byId(it)) }
            assertTrue(f.variables.isNotEmpty())
            assertTrue(f.explanation.isNotBlank())
        }
        FormulaCategory.entries.forEach { assertTrue(Formulas.byCategory(it).size >= 4) }
    }

    @Test fun formulaSearch() {
        assertEquals("heat_quantity", Formulas.search("warmemenge").first().id)
        assertEquals("ohm", Formulas.search("Ohm").first().id)
        assertTrue(Formulas.search("pumpe").any { it.id == "pump_power" })
        assertTrue(Formulas.search("drehstrom sicherung").any { it.id == "current_3p" })
        assertTrue(Formulas.search("xyzzy").isEmpty())
        assertEquals(Formulas.all.size, Formulas.search("  ").size)
        assertFalse(Formulas.search("rohr").isEmpty())
    }
}
