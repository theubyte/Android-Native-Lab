package de.millione.schulranzen.core

import de.millione.schulranzen.core.Geometry.pointSegmentDistance
import de.millione.schulranzen.core.Geometry.segmentDistance
import kotlin.test.Test
import kotlin.test.assertEquals

class GeometryTest {
    @Test fun `point to segment uses the nearest end beyond the segment`() {
        assertEquals(5f, pointSegmentDistance(3f, 4f, 0f, 0f, 0f, 0f), 1e-5f)
        assertEquals(2f, pointSegmentDistance(5f, 2f, 0f, 0f, 10f, 0f), 1e-5f)
        assertEquals(5f, pointSegmentDistance(13f, 4f, 0f, 0f, 10f, 0f), 1e-5f)
    }

    @Test fun `crossing segments touch`() {
        assertEquals(0f, segmentDistance(0f, -5f, 0f, 5f, -5f, 0f, 5f, 0f), 1e-5f)
    }

    @Test fun `fast eraser stroke between two sparse ink points still hits`() {
        // Ink from (0,0) to (100,0); eraser jumps from (50,-30) to (50,30) in one event.
        assertEquals(0f, segmentDistance(0f, 0f, 100f, 0f, 50f, -30f, 50f, 30f), 1e-5f)
    }

    @Test fun `parallel segments keep their gap`() {
        assertEquals(3f, segmentDistance(0f, 0f, 10f, 0f, 2f, 3f, 8f, 3f), 1e-5f)
    }
}
