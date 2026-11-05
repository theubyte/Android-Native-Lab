package de.millione.schulranzen.core.shk

import de.millione.schulranzen.core.math.GermanNumber
import de.millione.schulranzen.core.math.MathEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Findings of the technical review (docs/audit/rechnen.md) with hand-checked values. */
class AuditTest {
    private fun calc(id: String, vararg values: Pair<String, Double?>): ShkCalcResult {
        val c = ShkCalculators.byId(id)!!
        return c.calculate(c.inputs.associate { it.key to it.default } + values.toMap())
    }

    private fun ok(id: String, vararg values: Pair<String, Double?>) = calc(id, *values) as ShkCalcResult.Ok

    // ---- MAG: Reflex-Planungsunterlage

    // 300 l, 12 m, 70 °C, 3 bar: n = 2,244 %, V_e = 6,73 l, V_V = 3 l, p₀ = 1,4, p_e = 2,5 → 9,73 · 3,5 / 1,1 = 30,97 l → 35 l.
    @Test fun magByHand() {
        val r = ShkDesign.mag(300.0, 12.0, 70.0, 3.0)
        assertEquals(6.733, r.expansionL, 0.01)
        assertEquals(1.4, r.preChargeBar, 1e-9)
        assertEquals(1.7, r.fillBar, 1e-9)
        assertEquals(30.97, r.nominalMinL, 0.05)
        assertEquals(35, r.standardL)
    }

    @Test fun magPreChargeIsAtLeastOneBar() {
        assertEquals(1.0, ShkDesign.mag(200.0, 3.0, 70.0, 3.0).preChargeBar, 1e-9)
        assertEquals(1.0, ShkDesign.mag(200.0, 0.0, 70.0, 3.0).preChargeBar, 1e-9)
        assertTrue(calc("mag", "va" to 200.0, "hst" to 0.0) is ShkCalcResult.Ok)
        assertTrue(calc("mag", "va" to 200.0, "hst" to -1.0) is ShkCalcResult.Invalid)
    }

    // 50 l, 70 °C: V_e = 1,122 l; f = 3,5 / 1,5 = 2,333; V_N = 1,122 · 2,333 / (1 − 0,4667) = 4,91 l; V_V = 0,98 l.
    @Test fun magSmallVesselSealIsTwentyPercent() {
        val r = ShkDesign.mag(50.0, 8.0, 70.0, 3.0)
        assertEquals(4.91, r.nominalMinL, 0.02)
        assertEquals(r.nominalMinL * 0.2, r.reserveL, 1e-9)
        assertEquals(8, r.standardL)
    }

    @Test fun magOver100CIsRefused() {
        assertTrue(calc("mag", "va" to 200.0, "tmax" to 105.0) is ShkCalcResult.Invalid)
        assertTrue(calc("mag", "va" to 200.0, "tmax" to 100.0) is ShkCalcResult.Ok)
    }

    @Test fun magWarnsWhenValveIsTooCloseToPreCharge() {
        // h = 14 m: p₀ = 1,6; 3 bar < 1,6 + 1,5.
        assertTrue(ok("mag", "va" to 200.0, "hst" to 14.0).warnings.any { it.contains("p_SV") })
        assertTrue(ok("mag", "va" to 200.0, "hst" to 8.0).warnings.isEmpty())
        // Above the catalogue.
        assertTrue(ok("mag", "va" to 50_000.0).warnings.any { it.contains("Tabellengröße") })
    }

    // pe above 5 bar: 0,9 · p_SV.
    @Test fun magHighPressureValve() {
        assertEquals(5.4, ShkDesign.mag(1000.0, 20.0, 70.0, 6.0).finalBar, 1e-9)
    }

    // Reflex table (without antifreeze): 70 °C 2,24 %, 100 °C 4,34 %; ours from density within 0,04 points.
    @Test fun expansionAgainstManufacturerTable() {
        val reflex = mapOf(30.0 to 0.37, 40.0 to 0.72, 50.0 to 1.15, 60.0 to 1.66, 70.0 to 2.24, 80.0 to 2.88, 90.0 to 3.58, 100.0 to 4.34)
        reflex.forEach { (t, n) -> assertEquals("$t °C", n, ShkDesign.expansionPercent(t), 0.045) }
    }

    // ---- Dämmung: GModG Anlage 8

    @Test fun insulationCases() {
        // Grenzen aa–dd
        assertEquals(20.0, ShkDesign.insulationMm(22.0), 0.0)
        assertEquals(30.0, ShkDesign.insulationMm(22.1), 0.0)
        assertEquals(30.0, ShkDesign.insulationMm(35.0), 0.0)
        assertEquals(35.1, ShkDesign.insulationMm(35.1), 1e-9)
        assertEquals(100.0, ShkDesign.insulationMm(100.0), 0.0)
        assertEquals(100.0, ShkDesign.insulationMm(150.0), 0.0)
        // hh: doppelt an Außenluft
        assertEquals(40.0, ShkDesign.insulationMm(20.0, InsulationCase.OUTDOOR), 0.0)
        assertEquals(200.0, ShkDesign.insulationMm(150.0, InsulationCase.OUTDOOR), 0.0)
        assertTrue(InsulationCase.FLOOR.title.contains("zwischen Nutzern"))
        // Bestellgröße nie kleiner als gefordert.
        assertEquals(200, ShkDesign.insulationStockMm(200.0))
        assertEquals(110, ShkDesign.insulationStockMm(101.0))
        val out = ok("insulation", "di" to 20.0).outputs
        assertEquals(40.0, out.single { it.label == "An Außenluft" }.value, 0.0)
    }

    // ---- Tabellenwerte gegen Quellen

    @Test fun drinkingWaterConstantsAndFlows() {
        val r = BuildingType.RESIDENTIAL
        assertEquals(Triple(1.48, 0.19, 0.94), Triple(r.a, r.b, r.c))
        val b = BuildingType.HOSPITAL
        assertEquals(Triple(0.75, 0.44, 0.18), Triple(b.a, b.b, b.c))
        val h = BuildingType.HOTEL
        assertEquals(Triple(0.70, 0.48, 0.13), Triple(h.a, h.b, h.c))
        val s = BuildingType.SCHOOL
        assertEquals(Triple(0.91, 0.31, 0.38), Triple(s.a, s.b, s.c))
        val c = BuildingType.CARE
        assertEquals(Triple(1.40, 0.14, 0.92), Triple(c.a, c.b, c.c))
        assertEquals(0.07, TapPoint.BASIN.flowLs, 0.0)
        assertEquals(0.13, TapPoint.WC.flowLs, 0.0)
        assertEquals(0.15, TapPoint.SHOWER.flowLs, 0.0)
        assertEquals(0.07, TapPoint.DISHWASHER.flowLs, 0.0)
        assertEquals(0.30, TapPoint.URINAL.flowLs, 0.0)
        // Hotel, ΣV̇_R = 5 l/s: 0,70 · 5^0,48 − 0,13 = 1,3857 l/s.
        assertEquals(1.3857, ShkDesign.peakFlow(5.0, 0.15, BuildingType.HOTEL), 1e-3)
        // Grenzen: nie unter der größten Stelle, nie über der Summe.
        assertEquals(0.30, ShkDesign.peakFlow(0.30, 0.30), 1e-9)
    }

    @Test fun clampSpacingFromSources() {
        fun c(id: String) = PipeCatalog.byId(id)!!.clampSpacingM
        // Kupferinstitut Tab. 6.2
        mapOf("cu12" to 1.25, "cu15" to 1.25, "cu18" to 1.5, "cu22" to 2.0, "cu28" to 2.25, "cu35" to 2.75, "cu42" to 3.0, "cu54" to 3.5)
            .forEach { (id, v) -> assertEquals(id, v, c(id), 0.0) }
        // Ostendorf HT: waagerecht
        assertEquals(0.5, c("ht40"), 0.0)
        assertEquals(0.5, c("ht50"), 0.0)
        assertEquals(0.8, c("ht70"), 0.0)
        assertEquals(1.1, c("ht100"), 0.0)
        // MÜPRO Verbund
        assertEquals(1.2, c("ml16"), 0.0)
        assertEquals(1.5, c("ml63"), 0.0)
    }

    @Test fun pipeDimensions() {
        fun d(id: String) = PipeCatalog.byId(id)!!.innerMm
        assertEquals(16.0, d("st15"), 1e-9)
        assertEquals(21.6, d("st20"), 1e-9)
        assertEquals(50.0, d("cu54"), 1e-9)
        assertEquals(25.0, d("cu28"), 1e-9)
        assertEquals(51.0, d("cs54"), 1e-9)
        assertEquals(16.0, d("ml20"), 1e-9)
    }

    // Cu 22 × 1, 1 m³/h: v = 0,884 m/s, Re ≈ 37 200, λ ≈ 0,0225, R = λ/d · ρ · v²/2 ≈ 433 Pa/m.
    @Test fun pressureDropByHand() {
        val f = PipeCatalog.flow(PipeCatalog.byId("cu22")!!, 1.0)
        assertEquals(0.884, f.velocityMs, 1e-3)
        assertEquals(433.0, f.rPaPerM, 12.0)
    }

    @Test fun safetyValveTableIsTheTwoAndAHalfBarColumn() {
        assertEquals(listOf(50.0, 100.0, 200.0, 350.0, 600.0, 900.0), ShkDesign.SAFETY_VALVES.map { it.first })
        assertEquals(listOf(15, 20, 25, 32, 40, 50), ShkDesign.SAFETY_VALVES.map { it.second })
        assertTrue(calc("safety_valve", "p" to 901.0) is ShkCalcResult.Invalid)
    }

    @Test fun drainTableSeparatesUnventedRuns() {
        val rows = ShkTables.byId("drain_gradient")!!.rows
        assertEquals("1 %", rows.single { it[0] == "Sammelanschlussleitung, unbelüftet" }[1])
        assertEquals("1 : DN", rows.single { it[0].contains("außerhalb") }[1])
    }

    // ---- Grenzwerte in den Rechnern

    @Test fun physicalLimits() {
        assertTrue(calc("power_1p", "i" to 10.0, "cos" to 1.2) is ShkCalcResult.Invalid)
        assertTrue(calc("power_3p", "i" to 10.0, "cos" to 1.01) is ShkCalcResult.Invalid)
        assertTrue(calc("current_3p", "p" to 10.0, "cos" to 1.5) is ShkCalcResult.Invalid)
        assertTrue(calc("pump_power", "q" to 1.0, "h" to 1.0, "eta" to 120.0) is ShkCalcResult.Invalid)
        assertEquals(ShkCalcResult.Incomplete, calc("pipe_volume", "d" to 20.0))
        assertEquals("−273,15".replace('−', GermanNumber.MINUS), GermanNumber.format(Units.convert(Quantity.TEMPERATURE, 0.0, "K", "°C"), 2))
        assertNotNull(Units.problem(Quantity.TEMPERATURE, -300.0, "°C"))
        assertNotNull(Units.problem(Quantity.TEMPERATURE, -1.0, "K"))
        assertNull(Units.problem(Quantity.TEMPERATURE, -273.15, "°C"))
        assertNull(Units.problem(Quantity.PRESSURE, -1.0, "bar"))
    }

    @Test fun pipeSizingTellsWhenTheTableEnds() {
        // 8 m³/h at 1 m/s: only the widest pipes still fit, copper 54 × 2 does not.
        val r = ok("pipe_sizing", "p" to 46.5, "dt" to 5.0, "r" to 1000.0)
        assertTrue(r.warnings.any { it.startsWith("Kupfer") })
        assertTrue(r.outputs.size > 1)
        // Beyond every table: the flow stays right, and each material says it does not fit.
        val huge = ok("pipe_sizing", "p" to 100_000.0, "dt" to 5.0)
        assertEquals(1, huge.outputs.size)
        assertEquals(PipeMaterial.entries.count { it.pressurePipe }, huge.warnings.size)
    }

    @Test fun simplifiedResultsAreMarked() {
        listOf("mag", "safety_valve", "tw_peak", "heat_load", "radiator", "pump_head", "pipe_sizing", "insulation", "flow_from_power", "water_energy").forEach {
            assertEquals(it, Reliability.GUIDE, ShkCalculators.byId(it)!!.reliability)
        }
        listOf("mag", "safety_valve", "tw_peak", "heat_load", "pipe_sizing").forEach { assertTrue(it, ShkCalculators.byId(it)!!.needsPlanner) }
        listOf("ohm", "offset", "pipe_volume", "gradient").forEach { assertEquals(it, Reliability.DEFINITION, ShkCalculators.byId(it)!!.reliability) }
        assertTrue(Formulas.byId("tw_peak")!!.guide)
        assertFalse(Formulas.byId("ohm")!!.guide)
        ShkTables.all.forEach { assertTrue(it.id, it.source.isNotBlank()) }
        // Die Einheit im Formeltext muss stimmen: t = … / (1000 · Φ).
        assertTrue(ShkCalculators.byId("heating_time")!!.formula.contains("1000"))
    }

    @Test fun oneOutputPerCalculatorByHand() {
        // V = π · 0,016² / 4 · 25 m = 5,0265 l
        assertEquals(5.0265, ok("pipe_volume", "d" to 16.0, "l" to 25.0).outputs[0].value, 1e-3)
        // 25 l/min in 26 mm: 4,1667e-4 m³/s / 5,3093e-4 m² = 0,7848 m/s
        assertEquals(0.7848, ok("flow_velocity", "q" to 25.0, "d" to 26.0).outputs[0].value, 1e-3)
        // √(4 · 25/60000 / (π · 0,8)) = 25,75 mm
        assertEquals(25.75, ok("pipe_diameter", "q" to 25.0).outputs[0].value, 0.01)
        // 12 m Kupfer, 50 K: 9,96 mm
        assertEquals(9.96, ok("expansion", "l" to 12.0, "dt" to 50.0).outputs[0].value, 1e-9)
        // √3 · 400 · 10 · 0,9 = 6235,4 W
        assertEquals(6235.38, ok("power_3p", "i" to 10.0, "cos" to 0.9).outputs[0].value, 0.01)
        // 230 · 2 · 0,85 = 391 W
        assertEquals(391.0, ok("power_1p", "i" to 2.0, "cos" to 0.85).outputs[0].value, 1e-9)
        // 150 m² · 60 W/m² = 9 kW
        assertEquals(9.0, ok("heat_load", "a" to 150.0, "q" to 60.0).outputs[0].value, 1e-9)
        // 4,3 kPa... 10 mWS = 0,98067 bar
        assertEquals(0.98067, ok("head_to_pressure", "h" to 10.0).outputs[0].value, 1e-5)
    }

    // ---- Aufmaß und Export

    private fun pos(id: String, expr: String, pipe: String? = "cu22", kind: PositionKind = PositionKind.PIPE) = AufmassPosition(id, kind, "", expr, pipe)

    @Test fun deductionsReduceTheMaterial() {
        val p = AufmassProject("p", "x", rooms = listOf(AufmassRoom("r", "Bad", listOf(pos("a", "12"), pos("b", "-2")))))
        assertEquals(10.0, AufmassMath.sums(p).single().quantity, 1e-9)
        val pipe = MaterialPlanner.plan(MaterialPlanner.fromAufmass(p), MaterialSettings(pipeWastePercent = 0.0)).lines.first { it.group == MaterialGroup.PIPE }
        assertEquals(2.0, pipe.quantity, 0.0) // 10 m in Stangen à 5 m
        // Nur Abzug: keine Bestellung.
        val only = AufmassProject("p", "x", rooms = listOf(AufmassRoom("r", "Bad", listOf(pos("b", "-2")))))
        assertTrue(MaterialPlanner.plan(MaterialPlanner.fromAufmass(only)).lines.isEmpty())
    }

    @Test fun unreadableIsVisibleInTheExport() {
        val p = AufmassProject("p", "x", rooms = listOf(AufmassRoom("r", "Bad", listOf(pos("a", "2+"), pos("b", "1.250"), pos("c", "2,5")))))
        val csv = ShkCsv.aufmass(p)
        assertTrue(csv.contains("2 Rechnung(en) nicht lesbar"))
        assertTrue(csv.contains("nicht lesbar;m"))
        assertEquals(2, AufmassMath.invalid(p).size)
    }

    @Test fun csvDoesNotRunFormulas() {
        assertEquals("'=1+2", ShkCsv.cell("=1+2"))
        assertEquals("'+49 170", ShkCsv.cell("+49 170"))
        assertEquals("'@x", ShkCsv.cell("@x"))
        assertEquals("'-2+3", ShkCsv.cell("-2+3"))
        assertEquals("-1,5", ShkCsv.cell("-1,5"))
        assertEquals("\"'=a;b\"", ShkCsv.cell("=a;b"))
    }

    // ---- Schema: Knicke und T-Stücke

    private fun p(x: Number, y: Number) = Pt(x.toDouble(), y.toDouble())

    private fun assertOrthogonal(points: List<Pt>) =
        points.zipWithNext().forEach { (a, b) -> assertTrue("$points", a.x == b.x || a.y == b.y) }

    @Test fun moveCornerKeepsEndsAndStaysOrthogonal() {
        val moved = SchemaGeometry.moveCorner(listOf(p(0, 0), p(5, 0), p(5, 5)), 1, p(3, 2))
        assertEquals(p(0, 0), moved.first())
        assertEquals(p(5, 5), moved.last())
        assertTrue(p(3, 2) in moved)
        assertOrthogonal(moved)

        val long = listOf(p(0, 0), p(4, 0), p(4, 3), p(8, 3), p(8, 6))
        val m2 = SchemaGeometry.moveCorner(long, 2, p(6, 1))
        assertEquals(p(0, 0), m2.first())
        assertEquals(p(8, 6), m2.last())
        assertOrthogonal(m2)
        // Ends cannot be moved this way.
        assertEquals(long, SchemaGeometry.moveCorner(long, 0, p(9, 9)))
        assertEquals(long, SchemaGeometry.moveCorner(long, 4, p(9, 9)))
    }

    @Test fun moveSegmentShiftsSideways() {
        val moved = SchemaGeometry.moveSegment(listOf(p(0, 0), p(5, 0), p(5, 5), p(9, 5)), 1, p(7, 3))
        assertEquals(listOf(p(0, 0), p(7, 0), p(7, 5), p(9, 5)), moved)
        // End segment: the end stays, a leg appears.
        val end = SchemaGeometry.moveSegment(listOf(p(0, 0), p(5, 0)), 0, p(2, 2))
        assertEquals(listOf(p(0, 0), p(0, 2), p(5, 2), p(5, 0)), end)
        assertOrthogonal(end)
    }

    @Test fun cornerHit() {
        val pipe = SchemaPipe("a", PipeKind.FLOW, listOf(p(0, 0), p(5, 0), p(5, 5)))
        assertEquals(1, SchemaGeometry.cornerAt(pipe, p(5.2, 0.1), 0.5))
        assertNull(SchemaGeometry.cornerAt(pipe, p(0, 0), 0.5))
        assertNull(SchemaGeometry.cornerAt(pipe, p(3, 3), 0.5))
    }

    private val base = Schema("s", "x", metersPerUnit = 0.5, pipes = listOf(SchemaPipe("a", PipeKind.FLOW, listOf(p(0, 0), p(10, 0)), pipeId = "cu22")))

    @Test fun branchOntoPipeMakesATee() {
        var n = 0
        val s = SchemaOps.addPipeWithTees(base, "b", PipeKind.FLOW, p(4, 0), p(4, 5), "cu22") { "id${n++}" }
        val tee = s.symbols.single()
        assertEquals(SymbolType.TEE, tee.type)
        assertEquals(p(4, 0), tee.at)
        assertEquals(3, s.pipes.size)
        // Same total pipe length as before plus the branch: a tee adds and loses nothing.
        assertEquals(10.0 + 5.0, s.pipes.sumOf { SchemaGeometry.length(it.points) }, 1e-9)
        assertEquals(PortRef(tee.id, 2), s.pipes.single { it.id == "b" }.start)
        // Moving the tee keeps all three connected.
        val moved = SchemaOps.moveSymbol(s, tee.id, p(4, 2))
        moved.pipes.forEach { pipe -> assertOrthogonal(pipe.points) }
        assertTrue(moved.pipes.all { pipe -> pipe.points.first() == p(4, 2) || pipe.points.last() == p(4, 2) })
        // Material: counted with the dimension of its pipes, 5 % allowance rounds up to 2.
        val list = MaterialPlanner.plan(MaterialPlanner.fromSchema(s))
        val line = list.lines.single { it.group == MaterialGroup.FITTING }
        assertEquals("T-Stück Cu 22 × 1", line.text)
        assertEquals(2.0, line.quantity, 0.0)
    }

    @Test fun otherMediumOrEndsMakeNoTee() {
        var n = 0
        val other = SchemaOps.addPipeWithTees(base, "b", PipeKind.RETURN, p(4, 0), p(4, 5), null) { "id${n++}" }
        assertTrue(other.symbols.isEmpty())
        assertEquals(2, other.pipes.size)
        val atEnd = SchemaOps.addPipeWithTees(base, "b", PipeKind.FLOW, p(10, 0), p(10, 5), null) { "id${n++}" }
        assertTrue(atEnd.symbols.isEmpty())
    }

    @Test fun teeSharesATypedLength() {
        val typed = base.copy(pipes = listOf(base.pipes[0].copy(lengthM = 20.0)))
        val s = SchemaOps.splitWithTee(typed, "a", p(4, 0), "t", "a2")
        assertEquals(20.0, s.pipes.sumOf { it.lengthM!! }, 1e-9)
        assertEquals(8.0, s.pipes.first { it.id == "a" }.lengthM!!, 1e-9)
        assertEquals(typed, SchemaOps.splitWithTee(typed, "a", p(4, 3), "t", "a2"))
    }

    @Test fun undoRestoresBendMove() {
        val start = Schema("s", "x", pipes = listOf(SchemaPipe("a", PipeKind.FLOW, listOf(p(0, 0), p(5, 0), p(5, 5)))))
        val stack = UndoStack(start)
        stack.push(SchemaOps.moveCorner(start, "a", 1, p(3.2, 2.1)))
        assertTrue(p(3, 2) in stack.current.pipes[0].points)
        assertEquals(start, stack.undo())
    }

    // ---- Übungsaufgaben

    @Test fun everyFormulaHasAnExercise() {
        assertEquals(Formulas.all.map { it.id }.toSet(), FormulaExercises.formulaIds)
    }

    /** Two independent paths: the domain function gives the answer, the math engine evaluates the shown calculation. */
    @Test fun exercisesAreCorrect() {
        Formulas.all.forEach { f ->
            repeat(200) { seed ->
                val e = FormulaExercises.generate(f.id, Random(seed))!!
                val byEngine = MathEngine.evaluate(e.expression).value
                val tolerance = when (f.id) {
                    "bar_mws", "hydrostatic" -> 0.03 * kotlin.math.abs(e.answer)
                    else -> 1e-6 * kotlin.math.abs(e.answer)
                }
                assertEquals("${f.id}: ${e.expression}", e.answer, byEngine, tolerance)
                assertTrue(f.id, e.answer.isFinite())
                assertTrue(f.id, e.question.isNotBlank() && e.steps.size >= 3)
                assertTrue(f.id, e.accepts(e.answer))
                assertFalse(f.id, e.accepts(e.answer * 1.5 + 1.0))
                assertTrue(f.id, e.steps.last().contains(GermanNumber.format(e.answer, e.decimals)))
            }
        }
    }

    @Test fun exercisesVaryAndAcceptRounding() {
        assertTrue((0 until 30).map { FormulaExercises.generate("ohm", Random(it))!!.question }.toSet().size > 2)
        assertNull(FormulaExercises.generate("unbekannt"))
        val e = Exercise("x", "q", listOf(), "1", 123.456, "W", 1)
        assertTrue(e.accepts(123.5))
        assertTrue(e.accepts(124.0)) // innerhalb 1 %
        assertFalse(e.accepts(130.0))
    }

    @Test fun exercisesSeedGivesKelvinWithNegativeValues() {
        val negative = (0 until 50).map { FormulaExercises.generate("kelvin", Random(it))!! }.first { it.answer < 273.15 }
        assertEquals(negative.answer, MathEngine.evaluate(negative.expression).value, 1e-9)
    }
}
