package com.bydmate.app.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.voice.HEARD_MAX_CHARS
import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoiceStage
import com.bydmate.app.voice.VoiceStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private fun previewScalePart(scale: Float): String = scale.toString().replace('.', '_')

/**
 * Read-only visual evidence for the floating widget. This test renders fixed snapshots only:
 * it does not construct or start VoiceController, read vehicle state, or dispatch any action.
 *
 * PNGs are intentionally retained at externalCacheDir/widget-preview so the operator can pull
 * and inspect them after the instrumented run.
 */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewCaptureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val outputDir = File(targetContext.externalCacheDir, OUTPUT_DIRECTORY)

    @Test
    fun capturesNamedWidgetPreviewMatrix() {
        outputDir.mkdirs()
        require(outputDir.isDirectory) { "Could not create preview directory: $outputDir" }
        outputDir.listFiles { file -> file.extension == "png" }?.forEach { file ->
            check(file.delete()) { "Could not remove previous preview: $file" }
        }

        ActivityScenario.launch(WidgetPreviewActivity::class.java).use { scenario ->
            val cases = previewCases()
            assertTrue("Capture matrix changed: ${cases.size}", cases.size == EXPECTED_CAPTURE_COUNT)
            assertTrue("Duplicate capture filenames", cases.map { it.fileName }.distinct().size == cases.size)
            cases.forEachIndexed { index, preview -> capture(scenario, preview, caseId = index + 1) }
        }

        val files = outputDir.listFiles { file -> file.extension == "png" }.orEmpty()
        assertTrue("Expected $EXPECTED_CAPTURE_COUNT PNGs, found ${files.size} in $outputDir", files.size == EXPECTED_CAPTURE_COUNT)
    }

    private fun capture(
        scenario: ActivityScenario<WidgetPreviewActivity>,
        preview: PreviewCase,
        caseId: Int,
    ) {
        var widgetBounds: ComposeRect? = null
        val geometry = AtomicReference<FrameGeometry?>(null)
        val drawn = CountDownLatch(1)
        // Resolve per capture, rather than using an old wall-clock timestamp in every fixture.
        val sessionStartedAt = preview.tripDurationMillis?.let { System.currentTimeMillis() - it }

        scenario.onActivity { activity ->
            val contentView = activity.findViewById<android.view.View>(android.R.id.content)
            activity.setContent {
                val previewContext = previewContext(activity, preview.fontScale)
                val density = previewContext.resources.displayMetrics.density
                val configuration = previewContext.resources.configuration
                CompositionLocalProvider(
                    LocalContext provides previewContext,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density = density, fontScale = configuration.fontScale),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .previewBackground(preview.background)
                            .drawWithContent {
                                drawContent()
                                val bounds = widgetBounds
                                if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                                    // This marker is drawn after this case's widget, in the same tree.
                                    // A draw callback alone cannot prove SurfaceFlinger captured it.
                                    for (row in 0..1) for (column in 0 until PreviewFrameMarker.COLUMNS) {
                                        drawRect(
                                            color = Color(PreviewFrameMarker.colorAt(caseId, column, row)),
                                            topLeft = Offset(
                                                (column * PreviewFrameMarker.CELL_SIZE).toFloat(),
                                                (row * PreviewFrameMarker.CELL_SIZE).toFloat(),
                                            ),
                                            size = Size(PreviewFrameMarker.CELL_SIZE.toFloat(), PreviewFrameMarker.CELL_SIZE.toFloat()),
                                        )
                                    }
                                    val location = IntArray(2)
                                    contentView.getLocationOnScreen(location)
                                    geometry.set(FrameGeometry(
                                        widget = Rect(
                                            location[0] + bounds.left.roundToInt(),
                                            location[1] + bounds.top.roundToInt(),
                                            location[0] + bounds.right.roundToInt(),
                                            location[1] + bounds.bottom.roundToInt(),
                                        ),
                                        markerX = location[0],
                                        markerY = location[1],
                                    ))
                                    drawn.countDown()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // A fresh node must report bounds even when consecutive cases have the same size.
                        key(preview.fileName) {
                            Box(Modifier.onGloballyPositioned { widgetBounds = it.boundsInRoot() }) {
                                FloatingWidgetView(
                                    soc = preview.soc,
                                    rangeKm = preview.rangeKm,
                                    consumption = preview.consumption,
                                    trend = preview.trend,
                                    sessionStartedAt = sessionStartedAt,
                                    tripDistanceKm = preview.tripDistanceKm,
                                    insideTemp = preview.insideTemp,
                                    batTemp = preview.batTemp,
                                    voltage12v = preview.voltage12v,
                                    alpha = 1f,
                                    scaleFactor = preview.scaleFactor,
                                    listening = preview.listening,
                                    voice = preview.voice,
                                    kind = preview.kind,
                                    fuelPercent = preview.fuelPercent,
                                    fuelRangeKm = preview.fuelRangeKm,
                                )
                            }
                        }
                    }
                }
            }
        }

        assertTrue(
            "Widget did not draw within 10 seconds for ${preview.fileName}",
            drawn.await(10, TimeUnit.SECONDS),
        )
        val deadline = SystemClock.uptimeMillis() + 10_000
        var attempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = requireNotNull(geometry.get())
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            attempts++
            if (bitmap == null) continue
            try {
                if (attempts == 1 || SystemClock.uptimeMillis() >= deadline) {
                    val evidence = File(targetContext.filesDir, "widget-preview-evidence").apply { mkdirs() }
                    writePng(bitmap, File(evidence, "${preview.fileName}.full.png"))
                    File(evidence, "${preview.fileName}.geometry.txt").writeText(
                        "caseId=$caseId attempt=$attempts bitmap=${bitmap.width}x${bitmap.height} frame=$frame\n" +
                            (0 until PreviewFrameMarker.HEIGHT).joinToString("\n") { y ->
                                (0 until PreviewFrameMarker.WIDTH).joinToString(" ") { x ->
                                    if (frame.markerX + x in 0 until bitmap.width && frame.markerY + y in 0 until bitmap.height)
                                        Integer.toHexString(bitmap.getPixel(frame.markerX + x, frame.markerY + y)) else "outside"
                                }
                            }
                    )
                }
                val markerFits = frame.markerX >= 0 && frame.markerY >= 0 &&
                    frame.markerX + PreviewFrameMarker.WIDTH <= bitmap.width &&
                    frame.markerY + PreviewFrameMarker.HEIGHT <= bitmap.height
                if (!markerFits || !PreviewFrameMarker.matches(caseId) { x, y ->
                        bitmap.getPixel(frame.markerX + x, frame.markerY + y)
                    } || frame != geometry.get()
                ) continue
                val markerBounds = Rect(frame.markerX, frame.markerY,
                    frame.markerX + PreviewFrameMarker.WIDTH, frame.markerY + PreviewFrameMarker.HEIGHT)
                assertTrue("${preview.fileName} marker overlaps widget crop", !Rect.intersects(markerBounds, frame.widget))
                // Identity is checked on this bitmap. Never take a second screenshot for the crop.
                val crop = cropToWidget(bitmap, frame.widget, preview.fileName)
                try {
                    writePng(crop, File(outputDir, preview.fileName))
                } finally {
                    crop.recycle()
                }
                return
            } finally {
                bitmap.recycle()
            }
        }
        error("No screenshot with current-case marker $caseId for ${preview.fileName} after $attempts attempts (10s)")
    }

    private data class FrameGeometry(val widget: Rect, val markerX: Int, val markerY: Int)

    private fun previewContext(activity: WidgetPreviewActivity, fontScale: Float): Context {
        if (fontScale == 1f) return activity
        val configuration = Configuration(activity.resources.configuration).apply {
            this.fontScale = fontScale
        }
        return activity.createConfigurationContext(configuration)
    }

    private fun cropToWidget(screenshot: Bitmap, bounds: Rect, fileName: String): Bitmap {
        val crop = Rect(bounds)
        assertTrue("$fileName has an empty widget bounds: $crop", crop.width() > 0 && crop.height() > 0)
        assertTrue(
            "$fileName widget bounds $crop are outside screenshot ${screenshot.width}x${screenshot.height}",
            crop.left >= 0 && crop.top >= 0 && crop.right <= screenshot.width && crop.bottom <= screenshot.height,
        )
        return Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height())
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not write $file" }
        }
        check(file.length() > 0) { "Wrote an empty PNG: $file" }
    }

    private fun previewCases(): List<PreviewCase> = buildList {
        Background.entries.forEach { background ->
            addAll(dataCases(background, scaleFactor = 1f))
            VoiceStage.entries.forEach { stage ->
                add(background.polishVoice(stage, TWENTY_CHARACTER_POLISH, speaking = false, scaleFactor = 1f))
            }
        }

        listOf(Background.LIGHT, Background.DARK).forEach { background ->
            VoiceStage.entries.forEach { stage ->
                add(background.polishVoice(stage, SIXTY_FOUR_CHARACTER_POLISH, speaking = true, scaleFactor = 1f))
            }
        }

        listOf(0.7f, 2f).forEach { scaleFactor ->
            Background.entries.forEach { background ->
                add(background.dataOk(scaleFactor))
                add(background.polishVoice(VoiceStage.PROCESSING, SIXTY_FOUR_CHARACTER_POLISH, false, scaleFactor))
                add(background.polishVoice(VoiceStage.REFUSED, null, false, scaleFactor))
            }
        }

        Background.entries.forEach { background ->
            add(background.dataOk(scaleFactor = 1f, fontScale = 1.3f))
            add(background.polishVoice(VoiceStage.CONFIRM_ON_SCREEN, null, speaking = false, scaleFactor = 1f, fontScale = 1.3f))
        }
    }

    private fun dataCases(background: Background, scaleFactor: Float): List<PreviewCase> = listOf(
        background.dataOk(scaleFactor),
        PreviewCase(background, scaleFactor, case = "data_warn", soc = 25),
        PreviewCase(background, scaleFactor, case = "data_crit", soc = 9, voltage12v = 11.7),
        PreviewCase(background, scaleFactor, case = "data_null", soc = null, rangeKm = null, consumption = null,
            tripDurationMillis = null, tripDistanceKm = null, insideTemp = null, batTemp = null, voltage12v = null),
        PreviewCase(background, scaleFactor, case = "data_hybrid", kind = VehicleKind.HYBRID, fuelPercent = 61, fuelRangeKm = 478),
        PreviewCase(background, scaleFactor, case = "data_listening", listening = true,
            voice = VoiceStatus(VoiceStage.LISTENING, null, false, VoiceLanguage.PL)),
    )

    private fun Background.dataOk(scaleFactor: Float, fontScale: Float = 1f) = PreviewCase(
        background = this,
        scaleFactor = scaleFactor,
        fontScale = fontScale,
        case = "data_ok",
    )

    private fun Background.polishVoice(
        stage: VoiceStage,
        heard: String?,
        speaking: Boolean,
        scaleFactor: Float,
        fontScale: Float = 1f,
    ) = PreviewCase(
        this,
        scaleFactor,
        fontScale,
        "pl_${stage.filePart}${if (speaking) "_speaking" else ""}",
        voice = VoiceStatus(stage, heard, speaking, VoiceLanguage.PL),
    )

    private data class PreviewCase(
        val background: Background,
        val scaleFactor: Float,
        val fontScale: Float = 1f,
        val case: String,
        val soc: Int? = 74,
        val rangeKm: Double? = 318.0,
        val consumption: Double? = 16.2,
        val trend: Trend = Trend.FLAT,
        val tripDurationMillis: Long? = TimeUnit.MINUTES.toMillis(24),
        val tripDistanceKm: Double? = 18.4,
        val insideTemp: Int? = 22,
        val batTemp: Int? = 25,
        val voltage12v: Double? = 12.4,
        val listening: Boolean = false,
        val voice: VoiceStatus = VoiceStatus.IDLE,
        val kind: VehicleKind = VehicleKind.EV,
        val fuelPercent: Int? = null,
        val fuelRangeKm: Int? = null,
    ) {
        val fileName: String
            get() = "${background.filePart}_${previewScalePart(scaleFactor)}_${case}${if (fontScale == 1f) "" else "_font_${previewScalePart(fontScale)}"}.png"
    }

    private fun Modifier.previewBackground(backdrop: Background): Modifier = when (backdrop) {
        Background.LIGHT -> background(Color.White)
        Background.DARK -> background(Color.Black)
        Background.SKY -> background(Brush.linearGradient(listOf(Color(0xFF718EAB), Color(0xFF344E6B))))
    }

    private enum class Background(val filePart: String) {
        LIGHT("light"),
        DARK("dark"),
        SKY("sky"),
    }

    private val VoiceStage.filePart: String
        get() = name.lowercase()

    private companion object {
        const val OUTPUT_DIRECTORY = "widget-preview"
        const val EXPECTED_CAPTURE_COUNT = 107
        const val TWENTY_CHARACTER_POLISH = "zamknij okno, proszę"
        const val SIXTY_FOUR_CHARACTER_POLISH = "proszę zamknij wszystkie okna i natychmiast potwierdź wykonanie!"

        init {
            check(TWENTY_CHARACTER_POLISH.length == 20)
            check(SIXTY_FOUR_CHARACTER_POLISH.length == HEARD_MAX_CHARS)
        }

    }
}
