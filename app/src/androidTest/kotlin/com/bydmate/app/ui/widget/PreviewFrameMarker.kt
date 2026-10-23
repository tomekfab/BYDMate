package com.bydmate.app.ui.widget

/** Pixel-only case identity. Kept free of Android APIs for deterministic host verification. */
internal object PreviewFrameMarker {
    const val CELL_SIZE = 4
    const val COLUMNS = 18
    const val WIDTH = COLUMNS * CELL_SIZE
    const val HEIGHT = 2 * CELL_SIZE
    const val BLACK = -0x1000000
    const val WHITE = -0x1

    fun colorAt(caseId: Int, column: Int, row: Int): Int {
        require(caseId in 1..65535)
        val white = when (column) {
            0 -> true
            COLUMNS - 1 -> false
            else -> (caseId and (1 shl (column - 1))) != 0
        }
        return if (white xor (row == 1)) WHITE else BLACK
    }

    fun matches(caseId: Int, pixelAt: (Int, Int) -> Int): Boolean {
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val actual = pixelAt(x, y)
            val expected = colorAt(caseId, x / CELL_SIZE, y / CELL_SIZE)
            // Android 10 screenshot evidence shows up to 5/255 edge resampling error.
            // Check every pixel and RGB channel; keep the two bit values far apart.
            for (shift in listOf(0, 8, 16)) {
                if (kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) > 8) return false
            }
            if ((actual ushr 24) != 255) return false
        }
        return true
    }
}
