package com.bydmate.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameMarkerTest {
    private fun pixels(caseId: Int): (Int, Int) -> Int = { x, y ->
        PreviewFrameMarker.colorAt(caseId, x / PreviewFrameMarker.CELL_SIZE, y / PreviewFrameMarker.CELL_SIZE)
    }

    @Test fun rejectsPreviousCaseEvenWhenLayoutBoundsAreIdentical() {
        assertFalse("Previous case pixels must not pass the current case barrier",
            PreviewFrameMarker.matches(2, pixels(1)))
    }

    @Test fun acceptsEveryCurrentCaseAndRejectsEveryOtherCase() {
        checkMeasuredAndroid10RasterizedEdges()
        for (expected in 1..107) for (rendered in 1..107) {
            val matches = PreviewFrameMarker.matches(expected, pixels(rendered))
            assertTrue("expected=$expected rendered=$rendered", matches == (expected == rendered))
        }
    }

    @Test fun rejectsBlankBlackAndWhiteFrames() {
        assertFalse(PreviewFrameMarker.matches(1) { _, _ -> PreviewFrameMarker.BLACK })
        assertFalse(PreviewFrameMarker.matches(1) { _, _ -> PreviewFrameMarker.WHITE })
    }

    @Test fun rejectsIncompleteMarker() {
        val correct = pixels(1)
        assertFalse(PreviewFrameMarker.matches(1) { x, y ->
            if (y >= PreviewFrameMarker.CELL_SIZE) PreviewFrameMarker.BLACK else correct(x, y)
        })
    }

    private fun checkMeasuredAndroid10RasterizedEdges() {
        // Android 10 full-bitmap evidence: 87/576 edge pixels changed, max channel error 5.
        val correct = pixels(1)
        val rasterized: (Int, Int) -> Int = { x, y ->
            if (correct(x, y) == PreviewFrameMarker.BLACK) 0xff050505.toInt() else 0xfffafafa.toInt()
        }
        assertTrue(PreviewFrameMarker.matches(1, rasterized))
        assertFalse(PreviewFrameMarker.matches(2, rasterized))
        assertFalse(PreviewFrameMarker.matches(1) { _, _ -> 0xff808080.toInt() })
    }

    @Test fun rejectsSingleCorruptedPixel() {
        val correct = pixels(1)
        assertFalse(PreviewFrameMarker.matches(1) { x, y ->
            if (x == 5 && y == 1) correct(x, y) xor 0x00ffffff else correct(x, y)
        })
    }
}
