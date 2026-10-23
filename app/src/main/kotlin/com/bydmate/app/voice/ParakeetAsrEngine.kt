package com.bydmate.app.voice

import java.util.concurrent.Executor
import kotlin.concurrent.thread

/** Polish recognizer: Parakeet TDT 0.6B v3 int8 through sherpa's NeMo transducer. Same audio,
 *  VAD segmentation, warm-up and crash protection; only the recognizer
 *  config and the crash-guard scope differ. The model detects the language itself: this engine
 *  does not and cannot force Polish. */
internal class ParakeetAsrEngine(
    modelManager: PolishModelManager,
    recognizerFactory: () -> RecognizerHandle = { RealRecognizerHandle(modelManager.asrRecognizerConfig()) },
    vadFactory: () -> VadHandle = { RealVadHandle(modelManager.vadPath()) },
    loadGuard: AsrLoadGuard? = null,
    warmVad: Boolean = false,
    prewarmExecutor: Executor = Executor { task ->
        thread(name = "parakeet-vad-prewarm", isDaemon = true) { task.run() }
    },
) : SegmentingAsrEngine(
    assets = modelManager,
    recognizerFactory = recognizerFactory,
    vadFactory = vadFactory,
    protection = RecognizerLoadProtection(loadGuard, modelManager.guardScope),
    warmVad = warmVad,
    prewarmExecutor = prewarmExecutor,
)
