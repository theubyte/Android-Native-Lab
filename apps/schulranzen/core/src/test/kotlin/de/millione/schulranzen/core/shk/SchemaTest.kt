package de.millione.schulranzen.core.shk

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTest {
    private fun pump(id: String, x: Double, y: Double, rot: Int = 0) = SchemaSymbol(id, SymbolType.PUMP, Pt(x, y), rot)
    private fun orthogonal(points: List<Pt>) = points.zipWithNext().all { (a, b) -> a.x == b.x || a.y == b.y }

    @Test fun transformsRotateClockwiseAndMirror() {
        val s = pump("a", 5.0, 5.0)
        assertEquals(listOf(Pt(4.0, 5.0), Pt(6.0, 5.0)), SchemaGeometry.ports(s))
        // A quarter turn clockwise: the inlet (left) moves to the top.
        assertEquals(listOf(Pt(5.0, 4.0), Pt(5.0, 6.0)), SchemaGeometry.ports(s.copy(rotation = 1)))
        assertEquals(listOf(Pt(6.0, 5.0), Pt(4.0, 5.0)), SchemaGeometry.ports(s.copy(rotation = 2)))
        assertEquals(listOf(Pt(6.0, 5.0), Pt(4.0, 5.0)), SchemaGeometry.ports(s.copy(mirrored = true)))
        assertEquals(SchemaGeometry.ports(s), SchemaGeometry.ports(s.copy(rotation = 4)))
    }

    @Test fun snapPrefersPortsThenGrid() {
        val schema = Schema("s", "Test", symbols = listOf(pump("a", 0.0, 0.0)))
        val near = SchemaGeometry.snap(schema, Pt(1.3, 0.2))
        assertEquals(Pt(1.0, 0.0), near.at)
        assertEquals(PortRef("a", 1), near.port)
        val far = SchemaGeometry.snap(schema, Pt(3.4, 2.6))
        assertEquals(Pt(3.0, 3.0), far.at)
        assertNull(far.port)
        assertNull(SchemaGeometry.snap(schema, Pt(1.1, 0.0), ignoreSymbol = "a").port)
    }

    @Test fun pipesRouteOrthogonallyAndFollowSymbols() {
        var schema = Schema("s", "Test", symbols = listOf(pump("a", 0.0, 0.0), pump("b", 5.0, 3.0)))
        schema = SchemaOps.addPipe(schema, "p", PipeKind.FLOW, Pt(1.1, 0.1), Pt(3.9, 3.0))
        val pipe = schema.pipes.single()
        assertEquals(PortRef("a", 1), pipe.start)
        assertEquals(PortRef("b", 0), pipe.end)
        assertEquals(Pt(1.0, 0.0), pipe.points.first())
        assertEquals(Pt(4.0, 3.0), pipe.points.last())
        assertTrue(orthogonal(pipe.points))

        schema = SchemaOps.moveSymbol(schema, "b", Pt(5.2, 6.4))
        var moved = schema.pipes.single()
        assertEquals(Pt(4.0, 6.0), moved.points.last())
        assertEquals(Pt(1.0, 0.0), moved.points.first())
        assertTrue(orthogonal(moved.points))

        schema = SchemaOps.rotate(schema, "a")
        moved = schema.pipes.single()
        assertEquals(SchemaGeometry.ports(schema.symbols.first { it.id == "a" })[1], moved.points.first())
        assertTrue(orthogonal(moved.points))

        schema = SchemaOps.mirror(schema, "b")
        assertEquals(Pt(6.0, 6.0), schema.pipes.single().points.last())
        assertTrue(orthogonal(schema.pipes.single().points))

        schema = SchemaOps.deleteSymbol(schema, "b")
        assertNull(schema.pipes.single().end)
        assertEquals(PortRef("a", 1), schema.pipes.single().start)
    }

    @Test fun degeneratePipeIsIgnored() {
        val schema = Schema("s", "Test")
        assertEquals(schema, SchemaOps.addPipe(schema, "p", PipeKind.COLD, Pt(1.1, 1.0), Pt(0.9, 1.2)))
    }

    @Test fun moveEndKeepsLongerRoutesOrthogonal() {
        val pts = listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 4.0), Pt(8.0, 4.0))
        val moved = SchemaGeometry.moveEnd(pts, atStart = false, to = Pt(9.0, 6.0))
        assertEquals(Pt(9.0, 6.0), moved.last())
        assertEquals(Pt(0.0, 0.0), moved.first())
        assertTrue(orthogonal(moved))
        val movedStart = SchemaGeometry.moveEnd(pts, atStart = true, to = Pt(-1.0, 2.0))
        assertEquals(Pt(-1.0, 2.0), movedStart.first())
        assertEquals(Pt(8.0, 4.0), movedStart.last())
        assertTrue(orthogonal(movedStart))
    }

    @Test fun simplifyDropsCollinearPoints() {
        assertEquals(
            listOf(Pt(0.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 2.0)),
            SchemaGeometry.simplify(listOf(Pt(0.0, 0.0), Pt(2.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 0.0), Pt(4.0, 2.0))),
        )
    }

    @Test fun hitTest() {
        var schema = Schema("s", "Test", symbols = listOf(pump("a", 0.0, 0.0)), labels = listOf(SchemaLabel("l", Pt(0.0, -5.0), "Heizkreis 1")))
        schema = SchemaOps.addPipe(schema, "p", PipeKind.RETURN, Pt(1.0, 0.0), Pt(6.0, 0.0))
        assertEquals(SchemaHit.Symbol("a"), SchemaGeometry.hit(schema, Pt(0.2, 0.3)))
        assertEquals(SchemaHit.Pipe("p", 0), SchemaGeometry.hit(schema, Pt(4.0, 0.2)))
        assertEquals(SchemaHit.Label("l"), SchemaGeometry.hit(schema, Pt(0.5, -5.1)))
        assertNull(SchemaGeometry.hit(schema, Pt(4.0, 3.0)))
        assertEquals(0.2, SchemaGeometry.distanceToSegment(Pt(4.0, 0.2), Pt(1.0, 0.0), Pt(6.0, 0.0)), 1e-9)
        val b = SchemaGeometry.bounds(schema)!!
        assertTrue(b.contains(Pt(6.0, 0.0)) && b.contains(Pt(0.0, -5.0)))
        assertNull(SchemaGeometry.bounds(Schema("e", "leer")))
    }

    @Test fun pipesFeedTheMaterialList() {
        var schema = Schema("s", "Test", metersPerUnit = 0.5, symbols = listOf(pump("a", 0.0, 0.0), SchemaSymbol("r", SymbolType.RADIATOR, Pt(10.0, -3.0))))
        schema = SchemaOps.addPipe(schema, "p", PipeKind.FLOW, Pt(1.0, 0.0), Pt(11.0, 0.0), pipeId = "cu18")
        schema = SchemaOps.addPipe(schema, "q", PipeKind.RETURN, Pt(1.0, 4.0), Pt(5.0, 4.0))
        schema = SchemaOps.updatePipe(schema, "q") { it.copy(lengthM = 7.5) }
        assertEquals(5.0, SchemaGeometry.lengthM(schema, schema.pipes[0]), 1e-9)
        assertEquals(7.5, SchemaGeometry.lengthM(schema, schema.pipes[1]), 1e-9)
        val input = MaterialPlanner.fromSchema(schema)
        assertEquals(listOf(PipeRun("cu18", 5.0, insulate = true)), input.runs)
        assertTrue(input.items.any { it.title == "Heizkörper" && it.group == MaterialGroup.RADIATOR })
        assertTrue(input.items.any { it.group == MaterialGroup.PIPE && it.quantity == 7.5 })
        val list = MaterialPlanner.plan(input)
        assertEquals(2.0, list.lines.first { it.group == MaterialGroup.PIPE && it.text.startsWith("Cu 18") }.quantity, 0.0)
    }

    @Test fun symbolsLinkToCalculators() {
        val p = pump("a", 0.0, 0.0).copy(props = mapOf("p" to "12", "l" to "", "dt" to "15"))
        val link = SchemaLinks.forSymbol(p)!!
        assertEquals("pump_head", link.calculatorId)
        assertEquals(mapOf("p" to "12", "dt" to "15"), link.inputs)
        assertEquals("pipe_sizing", SchemaLinks.forPipe(SchemaPipe("x", PipeKind.FLOW, emptyList(), props = mapOf("p" to "5"))).calculatorId)
        assertTrue(SchemaLinks.propertiesOf(SymbolType.RADIATOR).any { it.key == "pn" })
        assertTrue(SchemaLinks.propertiesOf(SymbolType.SENSOR).isEmpty())
        assertEquals(listOf("p", "dt"), SchemaLinks.pipeProperties().map { it.key })
    }

    @Test fun libraryIsComplete() {
        SymbolLibrary.all.forEach { d ->
            assertTrue(d.title, d.ports.isNotEmpty())
            assertTrue(d.title, d.prims.isNotEmpty())
            d.calculatorId?.let { assertNotNull(d.title, ShkCalculators.byId(it)) }
            // Ports sit on the grid so pipes snap cleanly.
            d.ports.forEach { assertTrue(d.title, it.at.x % 1.0 == 0.0 && it.at.y % 1.0 == 0.0) }
        }
        assertEquals(SymbolType.entries.size, SymbolLibrary.all.size)
    }

    @Test fun undoStack() {
        val u = UndoStack(0, limit = 3)
        assertFalse(u.canUndo)
        u.push(1); u.push(2); u.push(2); u.push(3); u.push(4)
        assertEquals(3, u.undo()); assertEquals(2, u.undo()); assertEquals(1, u.undo())
        assertFalse(u.canUndo) // limited to three steps
        assertEquals(1, u.undo())
        assertEquals(2, u.redo())
        u.push(9)
        assertFalse(u.canRedo)
        u.replace(10)
        assertEquals(2, u.undo())
    }

    @Test fun schemaRoundTripsAsJson() {
        var schema = Schema("s", "Heizung EG", symbols = listOf(pump("a", 0.0, 0.0, rot = 1).copy(props = mapOf("p" to "8"))))
        schema = SchemaOps.addPipe(schema, "p", PipeKind.CIRCULATION, Pt(0.0, 1.0), Pt(0.0, 6.0), "ml20")
        schema = schema.copy(labels = listOf(SchemaLabel("l", Pt(2.0, 2.0), "Zirkulation")))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(schema, json.decodeFromString(Schema.serializer(), json.encodeToString(Schema.serializer(), schema)))
    }
}
