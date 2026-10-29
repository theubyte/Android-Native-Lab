package de.millione.schulranzen.core.shk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {
    private fun ok(id: String, vararg values: Pair<String, Double?>): List<ShkOutput> {
        val calc = ShkCalculators.byId(id)!!
        val all = calc.inputs.associate { it.key to it.default } + values.toMap()
        val r = calc.calculate(all)
        assertTrue("$id: $r", r is ShkCalcResult.Ok)
        return (r as ShkCalcResult.Ok).outputs
    }

    // Cu 22 × 1 (dᵢ 20 mm) at 0,5 m³/h: v ≈ 0,44 m/s, R ≈ 130 Pa/m (table books: 120–140 Pa/m).
    @Test fun pressureDropMatchesTables() {
        val cu22 = PipeCatalog.byId("cu22")!!
        assertEquals(20.0, cu22.innerMm, 1e-9)
        val f = PipeCatalog.flow(cu22, 0.5)
        assertEquals(0.442, f.velocityMs, 1e-3)
        assertTrue("R = ${f.rPaPerM}", f.rPaPerM in 110.0..150.0)
        // Rougher steel loses more than copper at the same diameter and flow.
        assertTrue(PipeCatalog.rValue(PipeCatalog.byId("st15")!!, 0.3) > PipeCatalog.rValue(PipeCatalog.byId("cu18")!!, 0.3))
        // R grows with flow.
        assertTrue(PipeCatalog.rValue(cu22, 1.0) > 3 * PipeCatalog.rValue(cu22, 0.5))
        assertEquals(0.0, PipeCatalog.rValue(cu22, 0.0), 0.0)
    }

    @Test fun chooseSmallestFittingPipe() {
        // 10 kW at 20 K = 0,43 m³/h: the pick holds both limits and every smaller size breaks one.
        val q = Shk.flowFromPower(10.0, 20.0)
        val pick = PipeCatalog.choose(PipeMaterial.COPPER, q, 100.0, 1.0)!!
        assertTrue(pick.rPaPerM <= 100.0 && pick.velocityMs <= 1.0)
        val smaller = PipeCatalog.of(PipeMaterial.COPPER).takeWhile { it != pick.size }
        assertTrue(smaller.all { PipeCatalog.rValue(it, q) > 100.0 || PipeCatalog.flow(it, q).velocityMs > 1.0 })
        assertNull(PipeCatalog.choose(PipeMaterial.COPPER, 100.0))
        val out = ok("pipe_sizing", "p" to 10.0)
        assertEquals(q, out[0].value, 1e-9)
        assertEquals(1 + PipeMaterial.entries.count { it.pressurePipe }, out.size)
    }

    // 200 l, 8 m, 70 °C, 3 bar: n = 2,24 %, V_e = 4,49 l, V_V = 3 l, p₀ = 1,0, p_e = 2,5 → V_N = 17,5 l → 18 l.
    @Test fun magSizing() {
        val r = ShkDesign.mag(200.0, 8.0, 70.0, 3.0)
        assertEquals(2.244, r.expansionPercent, 0.01)
        assertEquals(3.0, r.reserveL, 1e-9)
        assertEquals(1.0, r.preChargeBar, 1e-9)
        assertEquals(2.5, r.finalBar, 1e-9)
        assertEquals(17.47, r.nominalMinL, 0.05)
        assertEquals(18, r.standardL)
        assertEquals(1.3, r.fillBar, 1e-9)
        val out = ok("mag", "va" to 200.0)
        assertEquals(18.0, out[1].value, 0.0)
        // Safety valve too low for the height.
        assertTrue(ShkCalculators.byId("mag")!!.calculate(mapOf("va" to 200.0, "hst" to 30.0, "tmax" to 70.0, "psv" to 3.0)) is ShkCalcResult.Invalid)
    }

    @Test fun waterExpansion() {
        assertEquals(0.0, ShkDesign.expansionPercent(10.0), 1e-9)
        assertEquals(4.31, ShkDesign.expansionPercent(100.0), 0.01)
        assertTrue(ShkDesign.expansionPercent(55.0) in ShkDesign.expansionPercent(50.0)..ShkDesign.expansionPercent(60.0))
    }

    @Test fun safetyValve() {
        assertEquals(15, ShkDesign.safetyValveDn(50.0))
        assertEquals(20, ShkDesign.safetyValveDn(50.1))
        assertEquals(50, ShkDesign.safetyValveDn(900.0))
        assertNull(ShkDesign.safetyValveDn(901.0))
        assertEquals(25.0, ok("safety_valve", "p" to 150.0)[0].value, 0.0)
    }

    @Test fun gegInsulation() {
        assertEquals(20.0, ShkDesign.insulationMm(20.0), 0.0)
        assertEquals(20.0, ShkDesign.insulationMm(22.0), 0.0)
        assertEquals(30.0, ShkDesign.insulationMm(25.0), 0.0)
        assertEquals(50.0, ShkDesign.insulationMm(50.0), 0.0)
        assertEquals(100.0, ShkDesign.insulationMm(150.0), 0.0)
        assertEquals(15.0, ShkDesign.insulationMm(25.0, InsulationCase.HALF), 0.0)
        assertEquals(6.0, ShkDesign.insulationMm(25.0, InsulationCase.FLOOR), 0.0)
        assertEquals(20, ShkDesign.insulationStockMm(20.0))
        assertEquals(40, ShkDesign.insulationStockMm(32.0))
    }

    // Wohnung: 2 Waschtische, Dusche, Wanne, WC, Spüle, Waschmaschine = 0,79 l/s → V̇_S ≈ 0,475 l/s.
    @Test fun drinkingWaterPeakFlow() {
        val counts = mapOf(TapPoint.BASIN to 2, TapPoint.SHOWER to 1, TapPoint.BATH to 1, TapPoint.WC to 1, TapPoint.SINK to 1, TapPoint.WASHER to 1)
        assertEquals(0.475, ShkDesign.peakFlow(counts), 0.002)
        // A single tap runs fully.
        assertEquals(0.07, ShkDesign.peakFlow(mapOf(TapPoint.BASIN to 1)), 1e-9)
        assertEquals(0.0, ShkDesign.peakFlow(emptyMap()), 0.0)
        assertEquals(0.475, ok("tw_peak", "basin" to 2.0, "shower" to 1.0, "bath" to 1.0, "wc" to 1.0, "sink" to 1.0, "washer" to 1.0)[0].value, 0.002)
        assertTrue(ShkCalculators.byId("tw_peak")!!.calculate(TapPoint.entries.associate { it.name.lowercase() to 0.0 }) is ShkCalcResult.Invalid)
        assertTrue(ShkCalculators.byId("tw_peak")!!.calculate(TapPoint.entries.associate { it.name.lowercase() to 1.5 }) is ShkCalcResult.Invalid)
    }

    // 1000 W at 55/45/20: ΔT_ln = 29,7 K → 1000 · (29,7 / 49,8)^1,3 ≈ 511 W.
    @Test fun radiatorOutput() {
        assertEquals(49.83, ShkDesign.NORM_DELTA, 0.01)
        assertEquals(1000.0, ShkDesign.radiatorOutput(1000.0, 75.0, 65.0, 20.0), 1e-6)
        assertEquals(511.0, ShkDesign.radiatorOutput(1000.0, 55.0, 45.0, 20.0), 1.5)
        assertEquals(511.0, ok("radiator", "pn" to 1000.0)[0].value, 1.5)
        assertTrue(ShkCalculators.byId("radiator")!!.calculate(mapOf("pn" to 1000.0, "tv" to 18.0, "tr" to 15.0, "ti" to 20.0, "n" to 1.3)) is ShkCalcResult.Invalid)
    }

    // 60 m · 100 Pa/m · 1,3 + 10 kPa = 17,8 kPa → 1,815 m.
    @Test fun pumpHead() {
        assertEquals(1.815, ShkDesign.pumpHeadM(60.0, 100.0, 30.0, 10.0), 1e-3)
        val out = ok("pump_head", "p" to 10.0, "l" to 60.0)
        assertEquals(1.815, out[1].value, 1e-3)
        assertEquals(17.8, out[2].value, 1e-6)
    }

    @Test fun heatLoad() {
        assertEquals(9.6, ok("heat_load", "a" to 120.0)[0].value, 1e-9)
    }

    @Test fun clampCount() {
        assertEquals(6, ShkDesign.clampCount(10.0, 2.0))
        assertEquals(3, ShkDesign.clampCount(4.0, 2.0))
        assertEquals(2, ShkDesign.clampCount(0.5, 2.0))
        assertEquals(0, ShkDesign.clampCount(0.0, 2.0))
    }

    @Test fun tablesAreRectangular() {
        assertTrue(ShkTables.all.size >= 10)
        ShkTables.all.forEach { t ->
            assertTrue(t.id, t.rows.isNotEmpty())
            t.rows.forEach { assertEquals(t.id, t.columns.size, it.size) }
            t.calculatorId?.let { assertNotNull(t.id, ShkCalculators.byId(it)) }
        }
        assertEquals(ShkTables.all.size, ShkTables.all.map { it.id }.toSet().size)
    }

    @Test fun catalogIdsAreUnique() {
        assertEquals(PipeCatalog.all.size, PipeCatalog.all.map { it.id }.toSet().size)
        assertEquals(ShkCalculators.all.size, ShkCalculators.all.map { it.id }.toSet().size)
        assertEquals(Formulas.all.size, Formulas.all.map { it.id }.toSet().size)
        PipeCatalog.all.forEach { assertTrue(it.id, it.innerMm > 0 && it.clampSpacingM > 0) }
    }
}
