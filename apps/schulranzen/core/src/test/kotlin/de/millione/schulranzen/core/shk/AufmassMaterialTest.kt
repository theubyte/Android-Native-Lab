package de.millione.schulranzen.core.shk

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AufmassMaterialTest {
    private fun pos(id: String, kind: PositionKind, expr: String, pipe: String? = null, text: String = "", insulate: Boolean = false) =
        AufmassPosition(id, kind, text, expr, pipe, insulate)

    private val project = AufmassProject(
        "p1", "EFH Müller", "Hauptstr. 1",
        rooms = listOf(
            AufmassRoom(
                "r1", "Bad",
                listOf(
                    pos("a", PositionKind.PIPE, "2,5*3+1,2", "cu22", insulate = true),
                    pos("b", PositionKind.FITTING, "6", text = "Bogen 90° Cu 22"),
                    pos("c", PositionKind.RADIATOR, "1", text = "Badheizkörper 600 × 1200"),
                ),
            ),
            AufmassRoom(
                "r2", "Flur",
                listOf(
                    pos("d", PositionKind.PIPE, "3,3", "cu22", insulate = true),
                    pos("e", PositionKind.FITTING, "4", text = "Bogen 90° Cu 22"),
                    pos("f", PositionKind.AREA, "2,5*(3+1)", text = "Fliesen"),
                    pos("g", PositionKind.PIECE, "2+", text = "kaputt"),
                ),
            ),
        ),
    )

    @Test fun expressionsAreEvaluated() {
        assertEquals(8.7, AufmassPosition.evaluate("2,5*3+1,2")!!, 1e-9)
        assertEquals(10.0, AufmassPosition.evaluate("2,5·(3+1)")!!, 1e-9)
        assertNull(AufmassPosition.evaluate(""))
        assertNull(AufmassPosition.evaluate("2+"))
        assertEquals("Cu 22 × 1", project.rooms[0].positions[0].title)
    }

    @Test fun sumsGroupEqualPositions() {
        val sums = AufmassMath.sums(project)
        val pipe = sums.single { it.kind == PositionKind.PIPE }
        assertEquals(12.0, pipe.quantity, 1e-9)
        assertEquals("m", pipe.unit)
        assertEquals(10.0, sums.single { it.title == "Bogen 90° Cu 22" }.quantity, 1e-9)
        assertEquals(10.0, sums.single { it.kind == PositionKind.AREA }.quantity, 1e-9)
        assertEquals(listOf("g"), AufmassMath.invalid(project).map { it.id })
        // Ordered by kind: pipes first.
        assertEquals(PositionKind.PIPE, sums.first().kind)
    }

    @Test fun csvIsExcelFriendly() {
        val csv = ShkCsv.aufmass(project)
        assertTrue(csv.startsWith(ShkCsv.BOM))
        assertTrue(csv.contains("Bad;Rohrleitung;Cu 22 × 1;Cu 22 × 1;2,5*3+1,2;8,7;m;ja;"))
        assertEquals("\"a;b\"", ShkCsv.cell("a;b"))
        assertEquals("\"sag \"\"hi\"\"\"", ShkCsv.cell("sag \"hi\""))
        assertEquals("-1,5", ShkCsv.num(-1.5))
        assertEquals("1234,5", ShkCsv.num(1234.5))
    }

    // 12 m Cu 22: 13,2 m with 10 % → 3 bars à 5 m; clamps 6 + 3 (two runs) = 9 → +10 % = 10;
    // insulation 20 mm, 13,2 m → 7 sleeves à 2 m; bends 10 + 5 % → 11.
    @Test fun materialListFromAufmass() {
        val list = MaterialPlanner.plan(MaterialPlanner.fromAufmass(project))
        val g = list.byGroup()
        val pipe = g.getValue(MaterialGroup.PIPE).single()
        assertEquals("Cu 22 × 1", pipe.text)
        assertEquals(3.0, pipe.quantity, 0.0)
        assertTrue(pipe.unit.contains("5"))
        val clamp = g.getValue(MaterialGroup.CLAMP).single()
        assertEquals(ShkDesign.clampCount(8.7, 2.0) + ShkDesign.clampCount(3.3, 2.0), 9)
        assertEquals(10.0, clamp.quantity, 0.0)
        val ins = g.getValue(MaterialGroup.INSULATION).single()
        assertTrue(ins.text.endsWith("20 mm"))
        assertEquals(7.0, ins.quantity, 0.0)
        assertEquals(11.0, g.getValue(MaterialGroup.FITTING).single().quantity, 0.0)
        assertEquals(1.0, g.getValue(MaterialGroup.RADIATOR).single().quantity, 0.0)
        assertEquals(10.0, g.getValue(MaterialGroup.OTHER).single().quantity, 1e-9)
        // Groups come out in a fixed order.
        assertEquals(list.lines.map { it.group.ordinal }.sorted(), list.lines.map { it.group.ordinal })
    }

    @Test fun allowancesAreAdjustable() {
        val input = MaterialInput(runs = listOf(PipeRun("cu22", 10.0, insulate = true)))
        val none = MaterialPlanner.plan(input, MaterialSettings(pipeWastePercent = 0.0, clampExtraPercent = 0.0, insulationWastePercent = 0.0))
        assertEquals(2.0, none.lines.first { it.group == MaterialGroup.PIPE }.quantity, 0.0)
        assertEquals(6.0, none.lines.first { it.group == MaterialGroup.CLAMP }.quantity, 0.0)
        assertEquals(5.0, none.lines.first { it.group == MaterialGroup.INSULATION }.quantity, 0.0)
        val half = MaterialPlanner.plan(input, MaterialSettings(insulationCase = InsulationCase.HALF))
        assertTrue(half.lines.first { it.group == MaterialGroup.INSULATION }.text.endsWith("13 mm"))
        // Drain pipes are not insulated.
        val ht = MaterialPlanner.plan(MaterialInput(runs = listOf(PipeRun("ht100", 4.0, insulate = true))))
        assertTrue(ht.lines.none { it.group == MaterialGroup.INSULATION })
    }

    @Test fun materialCsv() {
        val csv = ShkCsv.materials("Test", MaterialPlanner.plan(MaterialPlanner.fromAufmass(project)))
        assertTrue(csv.lines()[3].startsWith("Rohre;Cu 22 × 1;3;Stangen"))
    }

    @Test fun projectRoundTripsAsJson() {
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(AufmassProject.serializer(), project)
        assertEquals(project, json.decodeFromString(AufmassProject.serializer(), text))
        assertEquals(MaterialSettings(), json.decodeFromString(MaterialSettings.serializer(), "{}"))
    }
}
