package com.bydmate.app.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Native checks for the Polish voice profile (plan steps 1 and 1i): the pinned Parakeet TDT 0.6B v3
 * int8 NeMo transducer and Piper Gosia run through the same sherpa-onnx runtime and config style as
 * the app. Nothing here downloads or touches vehicle code.
 *
 * Models are staged by the operator. They live in app-private storage under
 * filesDir/voice-test/pl/<package id>/. Because connectedDebugAndroidTest uninstalls the app
 * (wiping that directory), a package missing there is copied from the shell-owned staging directory
 * /data/local/tmp/bydmate-pl-models/<package id>/ first and then verified against the pinned
 * manifest. Staging command: docs/voice/polish-native-feasibility.md.
 *
 * Three different questions, three different tests, never merged:
 *  - [provisionedAssetsMatchThePinnedManifest]: asset integrity (sizes and SHA-256).
 *  - [parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants]: the runtime loads the pinned export and
 *    decodes every fixture; negation, forbidden tokens and cross-fixture satisfaction are checked.
 *    This is NOT the transcript-accuracy gate and may pass while the gate fails.
 *  - [parakeetTranscribesPolishUtterancesKeepingNegation]: the original, unchanged transcript-accuracy
 *    gate. It is expected to fail while any fixture is misrecognised.
 * Utterance-level results go to logcat (tag PolishSmoke) and filesDir/voice-test/pl/report-asr-*.json.
 */
@RunWith(AndroidJUnit4::class)
class PolishNativeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private val stagingRoot = File(appContext.filesDir, "voice-test/pl")
    private val parakeetDecoder by lazy {
        val paths = InstrumentationRegistry.getArguments().getString("asrMaxActivePaths")?.toInt() ?: 4
        require(paths == 4 || paths == 8) { "Diagnostic asrMaxActivePaths must be 4 or 8" }
        when (val method = InstrumentationRegistry.getArguments().getString("asrDecoder") ?: "greedy_search") {
            "greedy_search", "modified_beam_search" -> ParakeetDecoder(method, maxActivePaths = paths)
            else -> error(
                "Unsupported asrDecoder='$method'. Use greedy_search or modified_beam_search.",
            )
        }
    }

    private fun dirOf(pkg: PolishModelPackage) = File(stagingRoot, pkg.id)

    private class ParakeetDecoder(val method: String, val maxActivePaths: Int)

    private class Decoded(
        val id: String, val category: String, val reference: String, val transcript: String,
        val words: List<String>, val audioSec: Double, val decodeMs: Long,
    )

    @Test
    fun provisionedAssetsMatchThePinnedManifest() {
        PolishModelManifest.ALL.forEach { copyFromShellStagingIfMissing(it) }
        val problems = PolishModelManifest.ALL.flatMap { pkg ->
            PolishModelManifest.validate(pkg) + PolishModelManifest.verifyInstalled(dirOf(pkg), pkg)
        }
        if (problems.isNotEmpty()) {
            fail(
                "Verified Polish model assets are not provisioned under $stagingRoot:\n" +
                    problems.joinToString("\n") +
                    "\nStage them as described in docs/voice/polish-native-feasibility.md.",
            )
        }
    }

    /** Compatibility and safety only. Passing says the pinned export runs here and loses no negation;
     *  it says nothing about transcript accuracy. */
    @Test
    fun parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants() {
        val recognizer = createParakeetRecognizer()
        val decoded: List<Decoded>
        try {
            decoded = decodeCorpus(recognizer)
        } finally {
            recognizer.release()
        }
        val utterances = fixtureArray()
        val byId = (0 until utterances.length()).associate { utterances.getJSONObject(it).getString("id") to utterances.getJSONObject(it) }
        val violations = mutableListOf<String>()
        decoded.forEach { d ->
            val u = byId.getValue(d.id)
            if (d.transcript.isBlank()) violations += "${d.id}: empty transcript"
            if (d.category == "negative" && "nie" !in d.words) violations += "${d.id}: lost negation: '${d.transcript}'"
            val forbidden = u.getJSONArray("forbiddenTokens").strings().filter { it in d.words }
            if (forbidden.isNotEmpty()) violations += "${d.id}: forbidden tokens $forbidden in '${d.transcript}'"
            if (d.category == "quoted" && words(d.reference).first() !in d.words) {
                violations += "${d.id}: quoted utterance lost its quote marker '${words(d.reference).first()}': '${d.transcript}'"
            }
        }
        // A negative or unsupported fixture must never satisfy a positive/compound fixture's required set.
        decoded.filter { it.category == "negative" || it.category == "unrelated" }.forEach { d ->
            decoded.filter { it.category == "positive" || it.category == "compound" }.forEach { p ->
                val req = byId.getValue(p.id).getJSONArray("requiredTokens").strings()
                if (req.all { alts -> alts.split('|').any { it in d.words } }) {
                    violations += "${d.id} [${d.category}] '${d.transcript}' satisfies the required tokens of ${p.id}"
                }
            }
        }
        val mem = memoryKb()
        Log.i(TAG, "COMPAT decoder=${parakeetDecoder.method} maxActivePaths=${parakeetDecoder.maxActivePaths} " +
            "decoded=${decoded.size} violations=${violations.size} peakKb=${mem.hwm}")
        if (violations.isNotEmpty()) fail("Safety invariants failed:\n" + violations.joinToString("\n"))
        assertEquals("every fixture decoded", utterances.length(), decoded.size)
    }

    /** The two private human recordings (Step 1e). Raw transcripts only: the file-to-phrase mapping is
     *  unconfirmed, so nothing is scored. Clips live outside the repo in /data/local/tmp/bydmate-human. */
    @Test
    fun parakeetDecodesPrivateHumanClips() {
        val clips = File(HUMAN_CLIPS).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        if (clips.size != 2) fail("Expected 2 private human WAVs in $HUMAN_CLIPS, found ${clips.map { it.name }}")
        val recognizer = createParakeetRecognizer()
        try {
            clips.forEach { f ->
                val samples = readWav16kMono(f.readBytes())
                val t0 = System.nanoTime()
                val transcript = decode(recognizer, samples)
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "HUMAN file=${f.name} audioSec=" + "%.3f".format(samples.size.toDouble() / SAMPLE_RATE) + " decodeMs=$ms got='$transcript'")
                assertTrue("${f.name} produced an empty transcript", transcript.isNotBlank())
            }
        } finally {
            recognizer.release()
        }
    }

    /** The original transcript-accuracy gate, unchanged: every fixture must contain its required tokens,
     *  none of its forbidden tokens, and negative fixtures must keep "nie". Fails on any miss. */
    @Test
    fun parakeetTranscribesPolishUtterancesKeepingNegation() {
        val rssBeforeLoad = memoryKb()
        val loadStart = System.nanoTime()
        val recognizer = createParakeetRecognizer()
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val rssAfterLoad = memoryKb()

        val utterances = fixtureArray()
        val results = JSONArray()
        val failures = mutableListOf<String>()
        val rssAfterDecode: Mem
        val decoded: List<Decoded>
        try {
            decoded = decodeCorpus(recognizer)
            rssAfterDecode = memoryKb() // before release: the recognizer is still loaded
            decoded.forEachIndexed { i, d ->
                val u = utterances.getJSONObject(i)
                val required = u.getJSONArray("requiredTokens").strings()
                val forbidden = u.getJSONArray("forbiddenTokens").strings()
                val missing = required.filter { alternatives -> alternatives.split('|').none { it in d.words } }
                val present = forbidden.filter { it in d.words }
                val lostNegation = d.category == "negative" && "nie" !in d.words
                val pass = missing.isEmpty() && present.isEmpty() && !lostNegation
                val referenceWords = words(d.reference)
                results.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("category", d.category)
                        .put("reference", d.reference)
                        .put("transcript", d.transcript)
                        .put("missingTokens", JSONArray(missing))
                        .put("forbiddenPresent", JSONArray(present))
                        .put("lostNegation", lostNegation)
                        .put("wordEditDistance", editDistance(referenceWords, d.words))
                        .put("referenceWords", referenceWords.size)
                        .put("audioSec", d.audioSec)
                        .put("decodeMs", d.decodeMs)
                        .put("pass", pass),
                )
                Log.i(TAG, "${d.id} pass=$pass decodeMs=${d.decodeMs} audioSec=%.2f ".format(d.audioSec) +
                    "ref='${d.reference}' got='${d.transcript}' missing=$missing forbidden=$present")
                if (!pass) {
                    failures += "${d.id} [${d.category}]: '${d.transcript}' " +
                        "(missing=$missing forbidden=$present lostNegation=$lostNegation)"
                }
            }
        } finally {
            recognizer.release()
        }

        val rssPeak = memoryKb()
        val s = PolishModelManifest.PARAKEET_SETTINGS
        val report = JSONObject()
            .put("model", PolishModelManifest.PARAKEET_TDT_V3_INT8.id)
            .put("settings", "featureDim=${s.featureDim} modelType=${s.modelType} threads=${s.numThreads} " +
                "provider=${s.provider} decoder=${parakeetDecoder.method} " +
                "maxActivePaths=${parakeetDecoder.maxActivePaths}")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString())
            .put("loadMs", loadMs)
            .put("rssKbBeforeLoad", rssBeforeLoad.rss)
            .put("rssKbAfterLoad", rssAfterLoad.rss)
            .put("rssKbAfterDecode", rssAfterDecode.rss)
            .put("peakRssKb", rssPeak.hwm)
            .put("utterances", results)
        stagingRoot.mkdirs()
        File(stagingRoot, "report-asr-parakeet.json").writeText(report.toString(2))
        Log.i(TAG, "ASR report model=${PolishModelManifest.PARAKEET_TDT_V3_INT8.id} " +
            "decoder=${parakeetDecoder.method} maxActivePaths=${parakeetDecoder.maxActivePaths} " +
            "passed=${decoded.size - failures.size}/${utterances.length()} " +
            "failedIds=${failures.map { it.substringBefore(' ') }} totalDecodeMs=${decoded.sumOf { it.decodeMs }} " +
            "loadMs=$loadMs " +
            "rssKb ${rssBeforeLoad.rss}->${rssAfterLoad.rss}->${rssAfterDecode.rss} peakKb=${rssPeak.hwm} " +
            "failures=${failures.size}/${utterances.length()}")

        if (failures.isNotEmpty()) {
            fail("${failures.size}/${utterances.length()} utterances failed:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun gosiaSynthesizesValidPolishPcm() {
        val pkg = PolishModelManifest.GOSIA_MEDIUM_INT8
        requireProvisioned(pkg)
        val dir = dirOf(pkg)

        val loadStart = System.nanoTime()
        val tts = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "pl_PL-gosia-medium.onnx").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        try {
            val sampleRate = tts.sampleRate()
            assertEquals("Gosia medium is a 22.05 kHz voice", 22050, sampleRate)
            val phrases = listOf(
                "Okna zostały zamknięte.",
                "Temperatura ustawiona na dwadzieścia dwa stopnie.",
                "Poziom baterii wynosi osiemdziesiąt procent. Zasięg to trzysta kilometrów.",
            )
            var previousSec = 0.0
            phrases.forEach { text ->
                val t0 = System.nanoTime()
                val audio = tts.generate(text, 0, 1.0f)
                val genMs = (System.nanoTime() - t0) / 1_000_000
                val samples = audio.samples
                val sec = samples.size.toDouble() / audio.sampleRate
                val rms = Math.sqrt(samples.fold(0.0) { acc, s -> acc + s * s } / samples.size.coerceAtLeast(1))
                val peak = samples.maxOfOrNull { Math.abs(it) } ?: 0f
                Log.i(TAG, "TTS '$text' sec=%.2f genMs=$genMs rms=%.4f peak=%.3f loadMs=$loadMs".format(sec, rms, peak))
                assertEquals(sampleRate, audio.sampleRate)
                assertTrue("'$text' is too short: $sec s", sec >= 0.6)
                assertTrue("'$text' has non-finite samples", samples.all { it.isFinite() })
                assertTrue("'$text' is silent (rms=$rms)", rms > 0.01)
                assertTrue("'$text' clips badly (peak=$peak)", peak <= 1.5f)
                assertTrue("longer text must give longer audio ($sec <= $previousSec)", sec > previousSec * 0.9)
                previousSec = sec
            }
        } finally {
            tts.release()
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Copies the staged package into app-private storage unless it already verifies there. */
    private fun copyFromShellStagingIfMissing(pkg: PolishModelPackage) {
        val target = dirOf(pkg)
        if (PolishModelManifest.verifyInstalled(target, pkg).isEmpty()) return
        val source = File(SHELL_STAGING, pkg.id)
        if (!source.isDirectory) return
        target.deleteRecursively()
        source.copyRecursively(target, overwrite = true)
    }

    private fun requireProvisioned(pkg: PolishModelPackage) {
        copyFromShellStagingIfMissing(pkg)
        val problems = PolishModelManifest.verifyInstalled(dirOf(pkg), pkg)
        if (problems.isNotEmpty()) {
            fail("Model assets are not provisioned or fail verification:\n" + problems.joinToString("\n"))
        }
    }

    private fun fixtureArray(): JSONArray =
        JSONObject(testAssets.open("voice/pl/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("utterances")

    /** Verifies and loads the pinned Parakeet package; fails on any missing or corrupt file. */
    private fun createParakeetRecognizer(): OfflineRecognizer {
        val pkg = PolishModelManifest.PARAKEET_TDT_V3_INT8
        copyFromShellStagingIfMissing(pkg)
        val resolved = PolishModelManifest.resolveNemoTransducer(dirOf(pkg), pkg)
        if (resolved !is PolishAsrResolution.Ready) {
            fail("Model assets are not provisioned or fail verification:\n" +
                (resolved as PolishAsrResolution.NotReady).problems.joinToString("\n"))
        }
        resolved as PolishAsrResolution.Ready
        val c = PolishModelManifest.PARAKEET_SETTINGS
        val decoder = parakeetDecoder
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = c.sampleRate, featureDim = c.featureDim),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = resolved.encoder.absolutePath,
                        decoder = resolved.decoder.absolutePath,
                        joiner = resolved.joiner.absolutePath,
                    ),
                    tokens = resolved.tokens.absolutePath,
                    numThreads = c.numThreads,
                    provider = c.provider,
                    modelType = c.modelType,
                ),
                decodingMethod = decoder.method,
                maxActivePaths = decoder.maxActivePaths,
            ),
        )
    }

    private fun decodeCorpus(recognizer: OfflineRecognizer): List<Decoded> {
        val utterances = fixtureArray()
        return (0 until utterances.length()).map { i ->
            val u = utterances.getJSONObject(i)
            val samples = readWav16kMono(testAssets.open("voice/pl/" + u.getString("file")).readBytes())
            val t0 = System.nanoTime()
            val transcript = decode(recognizer, samples)
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "DECODE decoder=${parakeetDecoder.method} maxActivePaths=${parakeetDecoder.maxActivePaths} " +
                "${u.getString("id")} decodeMs=$ms audioSec=" +
                "%.2f".format(samples.size.toDouble() / SAMPLE_RATE) + " got='$transcript'")
            Decoded(
                u.getString("id"), u.getString("category"), u.getString("reference"), transcript,
                words(transcript), samples.size.toDouble() / SAMPLE_RATE, ms,
            )
        }
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private class Mem(val rss: Long, val hwm: Long)

    private fun memoryKb(): Mem {
        var rss = -1L
        var hwm = -1L
        File("/proc/self/status").forEachLine { line ->
            if (line.startsWith("VmRSS:")) rss = line.filter { it.isDigit() }.toLong()
            if (line.startsWith("VmHWM:")) hwm = line.filter { it.isDigit() }.toLong()
        }
        return Mem(rss, hwm)
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** Lowercase, punctuation removed, diacritics folded, split into words. */
    private fun words(text: String): List<String> =
        Normalizer.normalize(
            text.lowercase().replace("°c", " stopni ").replace("%", " procent ").replace('ł', 'l'),
            Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotEmpty() }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.size]
    }

    /** RIFF PCM16 mono 16 kHz to floats in [-1, 1]; walks chunks so padding chunks are skipped. */
    private fun readWav16kMono(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                assertEquals("PCM only", 1, buf.getShort(body).toInt())
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                assertEquals(1, channels)
                assertEquals(SAMPLE_RATE, rate)
                assertEquals(16, bits)
                val n = minOf(size, bytes.size - body) / 2
                return FloatArray(n) { buf.getShort(body + it * 2) / 32768f }
            }
            pos = body + size + (size and 1)
        }
        fail("no data chunk")
        return FloatArray(0)
    }

    private companion object {
        const val TAG = "PolishSmoke"
        const val SHELL_STAGING = "/data/local/tmp/bydmate-pl-models"
        const val HUMAN_CLIPS = "/data/local/tmp/bydmate-human"
        const val SAMPLE_RATE = 16000
    }
}
