package com.bydmate.app.voice

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

class AudioCaptureDuckTest {
    private class Fixture(initial: Int = 10) {
        var volume = initial
        var failSet = false
        var marker = -1
        val audio = mockk<AudioManager>(relaxed = true)
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val capture: AudioCapture
        init {
            every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { volume }
            every { audio.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers {
                if (failSet) throw SecurityException("set failed")
                volume = secondArg()
            }
            every { prefs.getInt(AudioCapture.KEY_PRE_DUCK_VOLUME, -1) } answers { marker }
            every { prefs.edit() } returns editor
            every { editor.putInt(AudioCapture.KEY_PRE_DUCK_VOLUME, any()) } answers { marker = secondArg(); editor }
            every { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) } answers { marker = -1; editor }
            capture = AudioCapture(audio, prefs)
        }
    }

    @Test fun `a failed explicit volume write does not change the restore target`() {
        val f = Fixture()
        val lease = f.capture.acquireSuppression(0)
        f.failSet = true
        assertEquals(true, runCatching { f.capture.applyExplicitVolume(7) }.isFailure)
        assertEquals(10, f.capture.pendingRestoreVolume())
        assertEquals(10, f.marker)
        f.failSet = false
        lease.close()
        assertEquals(10, f.volume)
    }

    @Test fun `a new owner preserves recovery after the previous restore failed`() {
        val f = Fixture()
        val first = f.capture.acquireSuppression(0)
        f.failSet = true
        first.close()
        assertEquals(10, f.marker)
        f.failSet = false
        val next = f.capture.acquireSuppression(AudioCapture.DUCK_VOLUME_INDEX)
        assertEquals(10, f.capture.pendingRestoreVolume())
        next.close()
        assertEquals(10, f.volume)
        assertEquals(-1, f.marker)
    }

    @Test fun `an explicit quiet volume overrides a failed restore marker`() {
        val f = Fixture()
        val first = f.capture.acquireSuppression(0)
        f.failSet = true
        first.close()
        f.failSet = false
        f.capture.applyExplicitVolume(0)
        assertEquals(-1, f.marker)
        val next = f.capture.acquireSuppression(AudioCapture.DUCK_VOLUME_INDEX)
        next.close()
        assertEquals(0, f.volume)
    }

    @Test fun `capture mutes before opening a mic and restores when it fails`() = runBlocking {
        val f = Fixture()
        mockkStatic(AudioRecord::class)
        mockkConstructor(AudioRecord::class)
        try {
            every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 16000
            every { anyConstructed<AudioRecord>().state } answers {
                assertEquals(0, f.volume)
                AudioRecord.STATE_UNINITIALIZED
            }
            every { anyConstructed<AudioRecord>().release() } returns Unit
            runCatching { f.capture.captureSession { Unit }.collect() }
                .onSuccess { fail("capture should fail when no microphone opens") }
            assertEquals(10, f.volume)
        } finally {
            unmockkAll()
        }
    }

    @Test fun `legacy owner exists when media is quiet and not playing`() {
        val f = Fixture(1)
        every { f.audio.isMusicActive } returns false
        val saved = f.capture.duckMusic()
        assertEquals(1, saved)
        assertEquals(1, f.capture.pendingRestoreVolume())
        f.capture.restoreMusic(saved)
    }

    @Test fun `capture mute and reply leases release in either order`() {
        for (captureFirst in listOf(true, false)) {
            val f = Fixture()
            val capture = f.capture.acquireSuppression(0)
            assertEquals(0, f.volume)
            val reply = f.capture.acquireSuppression(AudioCapture.DUCK_VOLUME_INDEX)
            if (captureFirst) {
                capture.close(); assertEquals(1, f.volume)
                reply.close()
            } else {
                reply.close(); assertEquals(0, f.volume)
                capture.close()
            }
            assertEquals(10, f.volume)
            capture.close(); reply.close()
            assertEquals(10, f.volume)
            assertEquals(-1, f.marker)
        }
    }

    @Test fun `quiet and inactive media still get suppression owners`() {
        val f = Fixture(0)
        every { f.audio.isMusicActive } returns false
        val lease = f.capture.acquireSuppression(0)
        assertEquals(0, f.capture.pendingRestoreVolume())
        f.capture.applyExplicitVolume(7)
        assertEquals(0, f.volume)
        lease.close()
        assertEquals(7, f.volume)
    }

    @Test fun `legacy and leases share ownership even when already suppressed`() {
        val f = Fixture()
        val capture = f.capture.acquireSuppression(0)
        val first = f.capture.duckMusic()
        val second = f.capture.duckMusic()
        assertEquals(10, first)
        assertEquals(10, second)
        f.capture.restoreMusic(first)
        assertEquals(0, f.volume)
        capture.close()
        assertEquals(1, f.volume)
        f.capture.restoreMusic(second)
        assertEquals(10, f.volume)
        f.capture.restoreMusic(second)
        assertEquals(10, f.volume)
    }

    @Test fun `explicit volume changes restore target without raising suppressed media`() {
        val f = Fixture()
        val reply = f.capture.acquireSuppression(1)
        val capture = f.capture.acquireSuppression(0)
        f.capture.applyExplicitVolume(7)
        assertEquals(0, f.volume)
        assertEquals(7, f.marker)
        capture.close()
        assertEquals(1, f.volume)
        reply.close()
        assertEquals(7, f.volume)
        f.capture.applyExplicitVolume(5)
        assertEquals(5, f.volume)
    }

    @Test fun `legacy acquisition failure returns null and leaves no owner`() {
        val f = Fixture()
        f.failSet = true
        assertNull(f.capture.duckMusic())
        assertNull(f.capture.pendingRestoreVolume())
    }

    @Test fun `failed final restore keeps marker for recovery`() {
        val f = Fixture()
        val lease = f.capture.acquireSuppression(0)
        f.failSet = true
        lease.close()
        assertEquals(10, f.marker)
        f.capture.restoreStuckDuck()
        assertEquals(10, f.marker)
        f.failSet = false
        f.capture.restoreStuckDuck()
        assertEquals(10, f.volume)
        assertEquals(-1, f.marker)
    }

    @Test fun `recovery respects live owner and manually raised volume`() {
        val f = Fixture()
        val lease = f.capture.acquireSuppression(0)
        f.capture.restoreStuckDuck()
        assertEquals(0, f.volume)
        assertEquals(10, f.marker)
        lease.close()
        f.marker = 10
        f.volume = 8
        f.capture.restoreStuckDuck()
        assertEquals(8, f.volume)
        assertEquals(-1, f.marker)
    }
    private fun prefsMock(pending: Int = -1): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(AudioCapture.KEY_PRE_DUCK_VOLUME, -1) } returns pending
        every { prefs.edit() } returns editor
        return prefs to editor
    }


    @Test
    fun `restoreMusic is a no-op when nothing was ducked`() {
        val audioManager = mockk<AudioManager>()
        val capture = AudioCapture(audioManager, prefsMock().first)

        capture.restoreMusic(null)

        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), any()) }
    }

    @Test
    fun `restoreStuckDuck restores persisted volume when still ducked`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1
        val (prefs, editor) = prefsMock(pending = 12)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 1) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreStuckDuck keeps a user-raised volume but clears the marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 8
        val (prefs, editor) = prefsMock(pending = 12)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 1) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreStuckDuck is a no-op without a persisted value`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        val (prefs, _) = prefsMock(pending = -1)
        AudioCapture(audioManager, prefs).restoreStuckDuck()
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `restoreStuckDuck skips while a live session in this process owns the duck`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 12
        val (prefs, editor) = prefsMock(pending = 12)
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.restoreStuckDuck()
        // Only duckMusic's own lowering happened; no restore to 12, marker untouched.
        verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 0) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test
    fun `restoreMusic re-arms restoreStuckDuck for the next process-death marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 12 andThen 1
        val (prefs, editor) = prefsMock(pending = 12)
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.restoreMusic(12)
        capture.restoreStuckDuck()
        // Once restoreMusic released the duck, a persisted marker is trusted again.
        verify(exactly = 2) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) }
        verify(exactly = 2) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test fun `pendingRestoreVolume visible only while a duck is active`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val capture = AudioCapture(audioManager, prefsMock().first)
        assertNull(capture.pendingRestoreVolume())
        val saved = capture.duckMusic()
        assertEquals(10, capture.pendingRestoreVolume())
        capture.applyExplicitVolume(5)
        assertEquals(5, capture.pendingRestoreVolume())
        capture.restoreMusic(saved)
        assertNull(capture.pendingRestoreVolume())
    }

    @Test fun `apply refreshes the persisted stuck-duck marker`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val (prefs, editor) = prefsMock()
        val capture = AudioCapture(audioManager, prefs)
        capture.duckMusic()
        capture.applyExplicitVolume(5)
        verify(exactly = 1) { editor.putInt(AudioCapture.KEY_PRE_DUCK_VOLUME, 5) }
    }

    @Test fun `set landing after the teardown restore still wins`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        var vol = 10
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { vol }
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers { vol = secondArg<Int>() }
        val capture = AudioCapture(audioManager, prefsMock().first)
        val saved = capture.duckMusic()
        capture.restoreMusic(saved)            // teardown won the race and restored 10 first
        capture.applyExplicitVolume(5)         // the command's set lands after -> must stick
        assertEquals(5, vol)
    }

    @Test fun `marker survives when the physical restore fails`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        val (prefs, editor) = prefsMock()
        val capture = AudioCapture(audioManager, prefs)
        val saved = capture.duckMusic()
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0) } throws SecurityException("boom")
        capture.restoreMusic(saved)   // physical restore fails -> the marker must stay for restoreStuckDuck()
        verify(exactly = 0) { editor.remove(AudioCapture.KEY_PRE_DUCK_VOLUME) }
    }

    @Test fun `set between a window restore and the session teardown still wins`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        var vol = 10
        every { audioManager.isMusicActive } returns true
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { vol }
        every { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, any(), 0) } answers { vol = secondArg<Int>() }
        val capture = AudioCapture(audioManager, prefsMock().first)
        val session = capture.duckMusic()      // session-level duck: 10 -> 1
        capture.applyExplicitVolume(13)        // turn 1 command
        val window = capture.duckMusic()       // turn 2 listen window: 13 -> 1
        capture.restoreMusic(window)           // window closes -> 13; the SESSION duck still owns
        assertEquals(13, capture.pendingRestoreVolume())  // ownership must survive the inner restore
        capture.applyExplicitVolume(5)         // turn 2 command, after the window restore
        capture.restoreMusic(session)          // session teardown must keep the user's 5
        assertEquals(5, vol)
    }
}
