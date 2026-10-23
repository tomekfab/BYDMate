package com.bydmate.app.voice

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** JNI-free boundary for the native recognizer, so JVM tests never load sherpa-onnx. */
internal interface RecognizerHandle {
    fun decode(samples: FloatArray): String
    fun close()
}

/** JNI-free boundary for the native VAD. A collection owns its VAD instance. */
internal interface VadHandle {
    fun acceptWaveform(samples: FloatArray)
    fun isSpeechDetected(): Boolean
    fun empty(): Boolean
    fun front(): FloatArray
    fun pop()
    fun close()
}

/** Plain recognizer configuration shared by model families. */
internal sealed interface RecognizerConfig {
    val featureDim: Int
    val numThreads: Int
    val provider: String
    val decodingMethod: String

    data class NemoCtc(
        val model: String,
        val tokens: String,
        override val featureDim: Int = SegmentingAsrEngine.FEATURE_DIM,
        override val numThreads: Int = 4,
        override val provider: String = "cpu",
        override val decodingMethod: String = "greedy_search",
    ) : RecognizerConfig

    data class NemoTransducer(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val modelType: String,
        override val featureDim: Int,
        override val numThreads: Int,
        override val provider: String,
        override val decodingMethod: String,
    ) : RecognizerConfig
}

internal class RealRecognizerHandle(config: RecognizerConfig) : RecognizerHandle {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SegmentingAsrEngine.SAMPLE_RATE,
                featureDim = config.featureDim,
            ),
            modelConfig = when (config) {
                is RecognizerConfig.NemoCtc -> OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = config.model),
                    tokens = config.tokens,
                    numThreads = config.numThreads,
                    provider = config.provider,
                )
                is RecognizerConfig.NemoTransducer -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = config.encoder,
                        decoder = config.decoder,
                        joiner = config.joiner,
                    ),
                    tokens = config.tokens,
                    numThreads = config.numThreads,
                    provider = config.provider,
                    modelType = config.modelType,
                )
            },
            decodingMethod = config.decodingMethod,
        ),
    )

    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SegmentingAsrEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

internal class RealVadHandle(vadPath: String) : VadHandle {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath,
                threshold = SegmentingAsrEngine.VAD_THRESHOLD,
                minSilenceDuration = SegmentingAsrEngine.VAD_MIN_SILENCE_SEC,
                minSpeechDuration = SegmentingAsrEngine.VAD_MIN_SPEECH_SEC,
                maxSpeechDuration = SegmentingAsrEngine.VAD_MAX_SPEECH_SEC,
            ),
            sampleRate = SegmentingAsrEngine.SAMPLE_RATE,
        ),
    )

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
    override fun empty(): Boolean = vad.empty()
    override fun front(): FloatArray = vad.front().samples
    override fun pop() = vad.pop()
    override fun close() = vad.release()
}

interface AsrAssets {
    fun isReady(): Boolean
    fun vadPath(): String
}

/**
 * Shared continuous-ASR pipeline. It keeps the recognizer across sessions, while every
 * collection owns and closes its VAD. This protects native lifecycle, cancellation and stale
 * session behaviour independently of the selected model family.
 */
internal open class SegmentingAsrEngine(
    private val assets: AsrAssets,
    private val recognizerFactory: () -> RecognizerHandle,
    private val vadFactory: () -> VadHandle,
    private val protection: RecognizerLoadProtection,
    private val warmVad: Boolean,
    private val prewarmExecutor: Executor,
) : ReleasableAsr {
    override fun isReady(): Boolean = assets.isReady() && !protection.isTripped()

    @Volatile private var cachedRecognizer: RecognizerHandle? = null
    @Volatile private var spareVad: VadHandle? = null
    private val spareInFlight = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0)

    override fun isWarm(): Boolean =
        if (warmVad) isReady() && cachedRecognizer != null && spareVad != null else isReady()

    override fun requiresWarmBeforeListening(): Boolean = warmVad

    override suspend fun releaseNative() = synchronized(this) {
        lifecycleGeneration.incrementAndGet()
        cachedRecognizer?.let { runCatching { it.close() } }
        cachedRecognizer = null
        spareVad?.let { runCatching { it.close() } }
        spareVad = null
    }

    @Synchronized
    override fun warmUp() {
        if (!isReady()) return
        val recognizer = runCatching { obtainRecognizer() }
        if (warmVad && recognizer.isSuccess && spareVad == null) {
            runCatching { spareVad = buildVad() }
        }
    }

    @Synchronized
    private fun obtainRecognizer(): RecognizerHandle = cachedRecognizer ?: run {
        protection.noteLoadBegin()
        val handle = recognizerFactory()
        protection.noteLoadSuccess()
        handle.also { cachedRecognizer = it }
    }

    private fun buildVad(): VadHandle {
        protection.guard?.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        val vad = vadFactory()
        protection.guard?.noteLoadSuccess(AsrLoadGuard.ARTIFACT_VAD)
        return vad
    }

    private fun prewarmNextVad() {
        if (!spareInFlight.compareAndSet(false, true)) return
        val generation = lifecycleGeneration.get()
        prewarmExecutor.execute {
            try {
                synchronized(this@SegmentingAsrEngine) {
                    if (generation == lifecycleGeneration.get() && spareVad == null && isReady()) {
                        runCatching { spareVad = buildVad() }
                            .onFailure { Log.w(TAG, "spare vad build failed", it) }
                    }
                }
            } finally {
                spareInFlight.set(false)
            }
        }
    }

    override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
        if (!isReady()) return@flow
        val recognizer = obtainRecognizer()
        val vadStartNs = System.nanoTime()
        val spare = if (warmVad) synchronized(this@SegmentingAsrEngine) {
            spareVad.also { spareVad = null }
        } else {
            null
        }
        val vad = spare ?: buildVad()
        try {
            val vadMs = (System.nanoTime() - vadStartNs) / NANOS_PER_MS
            Log.i(TAG, "vad ready: ms=$vadMs prebuilt=${spare != null}")
            Trace.event(TraceArea.VOICE, "vad-ready", "ms" to vadMs, "prebuilt" to (spare != null))
            if (warmVad) prewarmNextVad()
            var speaking = false
            var silentMs = 0L
            pcm.collect { shorts ->
                vad.acceptWaveform(FloatArray(shorts.size) { index -> shorts[index] / 32768f })
                if (vad.isSpeechDetected()) {
                    if (!speaking) {
                        speaking = true
                        silentMs = 0L
                        emit(ContinuousAsrEvent.SpeechStart)
                    }
                } else {
                    silentMs += (shorts.size * 1000L) / SAMPLE_RATE
                    emit(ContinuousAsrEvent.SilenceTick(silentMs))
                }
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    speaking = false
                    val text = recognizer.decode(segment)
                    if (text.isNotBlank()) emit(ContinuousAsrEvent.Utterance(text))
                }
            }
        } finally {
            vad.close()
        }
    }

    internal companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val VAD_THRESHOLD = 0.4f
        const val VAD_MIN_SILENCE_SEC = 0.8f
        const val VAD_MIN_SPEECH_SEC = 0.25f
        const val VAD_MAX_SPEECH_SEC = 15.0f
        private const val TAG = "VoiceController"
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** The recognizer guard is model-scoped; the VAD guard remains shared. */
internal class RecognizerLoadProtection(val guard: AsrLoadGuard?, private val scope: String?) {
    fun isTripped(): Boolean = guard?.isTripped(scope) ?: false
    fun noteLoadBegin() = guard?.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER, scope)
    fun noteLoadSuccess() = guard?.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER, scope)
}
