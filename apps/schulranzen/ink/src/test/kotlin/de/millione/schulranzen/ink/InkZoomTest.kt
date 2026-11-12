package de.millione.schulranzen.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkZoomTest {
    @Test fun stepsAreTwentyPercent() {
        assertEquals(0, InkZoom.step(1f))
        assertEquals(1, InkZoom.step(1.2f))
        assertEquals(-1, InkZoom.step(1f / 1.2f))
        assertEquals(1.2f, InkZoom.scaleOf(1), 1e-5f)
        for (s in -10..10) assertEquals(s, InkZoom.step(InkZoom.scaleOf(s)))
    }

    @Test fun zoomingOutKeepsTheSharperList() {
        val b = FrameBudget(clock = { 0L })
        b.begin()
        assertFalse(b.refresh(recorded = 3, current = 1, zooming = true))
        assertTrue(b.afterZoom)
        assertFalse(b.later)
    }

    @Test fun zoomingInRecordsAndAtRestToo() {
        val b = FrameBudget(clock = { 0L })
        b.begin()
        assertTrue(b.refresh(recorded = 1, current = 3, zooming = true))
        assertTrue(b.refresh(recorded = 3, current = 1, zooming = false))
        assertFalse(b.refresh(recorded = 2, current = 2, zooming = false))
    }

    @Test fun overBudgetWaitsForTheNextFrame() {
        var now = 0L
        val b = FrameBudget(nanos = 4_000_000L, clock = { now })
        b.begin()
        assertTrue(b.refresh(recorded = 3, current = 1, zooming = false))
        now = 5_000_000L
        assertFalse(b.refresh(recorded = 3, current = 1, zooming = false))
        assertTrue(b.later)
        b.begin()
        assertTrue(b.refresh(recorded = 3, current = 1, zooming = false))
        assertFalse(b.later)
    }
}
