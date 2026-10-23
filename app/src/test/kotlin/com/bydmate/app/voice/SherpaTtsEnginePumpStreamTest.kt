package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTtsEnginePumpStreamTest {
    @Test fun `stream stall remains longer than two engine synthesis timeouts`() {
        assertTrue(SherpaTtsEngine.STREAM_STALL_MS > 2 * SherpaTtsEngine.SYNTH_TIMEOUT_MS)
    }

    @Test fun `Gosia synthesis does not rewrite text`() {
        assertEquals("gotowe", SherpaTtsEngine.textForSynthesis("gotowe"))
    }
}
