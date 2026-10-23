package com.bydmate.app.voice

import kotlinx.coroutines.flow.Flow

/** Segment-level ASR for the continuous session. Emits one Utterance per
 *  VAD-detected phrase; SilenceTick lets the session loop track auto-stop. */
sealed interface ContinuousAsrEvent {
    data class Utterance(val text: String) : ContinuousAsrEvent
    object SpeechStart : ContinuousAsrEvent
    data class SilenceTick(val silentMs: Long) : ContinuousAsrEvent
}

interface ContinuousAsr {
    fun isReady(): Boolean
    /** Readiness for one profile captured by a voice session. Implementations that multiplex
     * profiles must not re-read the active profile after this point. */
    fun isReady(profile: VoiceSessionProfile): Boolean = isReady()
    /** True once everything transcribe() builds before it reads the first frame is built. */
    fun isWarm(): Boolean = isReady()
    fun isWarm(profile: VoiceSessionProfile): Boolean = isWarm()
    /** True when listening must not open before [isWarm] (Android 10 head units): there the
     *  build runs after the start cue and swallows the driver's first words. */
    fun requiresWarmBeforeListening(): Boolean = false
    fun requiresWarmBeforeListening(profile: VoiceSessionProfile): Boolean = requiresWarmBeforeListening()
    /** Cold flow: collecting consumes pcm frames (16kHz ShortArray), cancelling stops. */
    fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent>
    /** Starts one captured profile. A profile-aware implementation rejects this flow if a later
     * settings switch made the capture stale, rather than silently selecting the new profile. */
    fun transcribe(profile: VoiceSessionProfile, pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = transcribe(pcm)
    /** Pre-build the recognizer ahead of the first PTT so its cold model load doesn't
     *  delay transcribe(). Default no-op so fakes/tests don't need to implement it. */
    fun warmUp() {}
    fun warmUp(profile: VoiceSessionProfile) = warmUp()
}

/** A local recognizer whose native objects can be released before its files change. */
interface ReleasableAsr : ContinuousAsr {
    suspend fun releaseNative()
}
