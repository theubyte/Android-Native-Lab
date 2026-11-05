package de.millione.schulranzen.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanPlanTest {
    @Test fun endOfNotebook() {
        assertEquals(3, ScanPlan.insertPosition(listOf(0, 1, 2), null))
        assertEquals(3, ScanPlan.insertPosition(listOf(0, 1, 2), 99))
        assertEquals(0, ScanPlan.insertPosition(emptyList(), null))
    }

    @Test fun afterAChosenPage() {
        assertEquals(1, ScanPlan.insertPosition(listOf(0, 1, 2), 1))
        assertEquals(0, ScanPlan.insertPosition(listOf(0, 1, 2), 0))
    }

    @Test fun holesInPositionsDoNotMisplaceTheScan() {
        assertEquals(7, ScanPlan.insertPosition(listOf(2, 7, 9), 1))
        assertEquals(10, ScanPlan.insertPosition(listOf(2, 7, 9), null))
    }

    @Test fun firstNewPageIndex() {
        assertEquals(5, ScanPlan.firstIndex(5, null))
        assertEquals(2, ScanPlan.firstIndex(5, 2))
        assertEquals(5, ScanPlan.firstIndex(5, 50))
    }

    @Test fun reorder() {
        assertEquals(listOf("b", "a", "c"), ScanPlan.moved(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("c", "a", "b"), ScanPlan.moved(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), ScanPlan.moved(listOf("a", "b"), 0, 5))
        assertEquals(listOf("a", "b"), ScanPlan.moved(listOf("a", "b"), -1, 0))
    }

    @Test fun rotationWraps() {
        assertEquals(90, ScanPlan.turned(0))
        assertEquals(0, ScanPlan.turned(270))
    }

    @Test fun bigPhotosShrinkSmallOnesStay() {
        assertEquals(3000 to 4000, ScanPlan.fit(3000, 4000))
        assertEquals(3000 to 4000, ScanPlan.fit(6000, 8000))
        assertEquals(800 to 600, ScanPlan.fit(800, 600))
        assertEquals(4000 to 1, ScanPlan.fit(100_000, 1))
    }

    @Test fun samplingNeverDropsBelowTheTarget() {
        assertEquals(1, ScanPlan.sampleSize(3000, 2000, 2400))
        assertEquals(2, ScanPlan.sampleSize(6000, 4000, 2400))
        assertEquals(4, ScanPlan.sampleSize(12000, 8000, 2400))
    }

    @Test fun posterPdfStaysInMemory() {
        val s = ScanPlan.pdfScale(595, 20_000)
        assertTrue(20_000 * s <= ScanPlan.PDF_HEIGHT * 1.5f + 1)
        assertEquals(2480 / 595f, ScanPlan.pdfScale(595, 842), 0.001f)
    }

    @Test fun onlyForeignContentIsAccepted() {
        val own = "de.millione.schulranzen.neu.files"
        assertTrue(ScanPlan.acceptsShare("content", "com.android.gallery", own, "image/jpeg"))
        assertTrue(ScanPlan.acceptsShare("content", "com.android.gallery", own, "application/pdf"))
        assertTrue(ScanPlan.acceptsShare("content", "com.android.gallery", own, null))
        assertFalse(ScanPlan.acceptsShare("file", null, own, "image/jpeg"))
        assertFalse(ScanPlan.acceptsShare("content", own, own, "image/jpeg"))
        assertFalse(ScanPlan.acceptsShare("content", "x", own, "text/plain"))
        assertFalse(ScanPlan.acceptsShare(null, "x", own, "image/png"))
    }
}
