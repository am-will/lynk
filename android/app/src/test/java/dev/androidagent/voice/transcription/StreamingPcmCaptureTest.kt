package dev.androidagent.voice.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamingPcmCaptureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun enforcesByteLimitWithoutWritingPastIt() {
        val file = temporaryFolder.newFile("bounded.pcm")
        val capture = StreamingPcmCapture(
            file,
            sampleRate = 16_000,
            policy = policy(maxBytes = 4)
        )

        assertEquals(CaptureStopReason.MAX_BYTES, capture.append(shortArrayOf(1, 2), 2, 1f).stopReason)
        assertEquals(CaptureStopReason.MAX_BYTES, capture.append(shortArrayOf(3), 1, 1f).stopReason)
        val result = capture.finish()
        assertEquals(4L, result.byteCount)
        assertEquals(4L, file.length())
    }

    @Test
    fun stopsInitialSilenceNoProgressAndDurationDeterministically() {
        var now = 0L
        val initial = StreamingPcmCapture(
            temporaryFolder.newFile("initial.pcm"), 16_000,
            policy(initialSilenceMs = 10, noProgressMs = 20, maxDurationMs = 30),
            clockMs = { now }
        )
        now = 10
        assertEquals(CaptureStopReason.INITIAL_SILENCE, initial.noProgress().stopReason)
        initial.abort()

        now = 0
        val stalled = StreamingPcmCapture(
            temporaryFolder.newFile("stalled.pcm"), 16_000,
            policy(initialSilenceMs = 50, noProgressMs = 10, maxDurationMs = 30),
            clockMs = { now }
        )
        now = 10
        assertEquals(CaptureStopReason.NO_PROGRESS, stalled.noProgress().stopReason)
        stalled.abort()

        now = 0
        val duration = StreamingPcmCapture(
            temporaryFolder.newFile("duration.pcm"), 16_000,
            policy(initialSilenceMs = 50, noProgressMs = 50, maxDurationMs = 10),
            clockMs = { now }
        )
        now = 10
        assertEquals(CaptureStopReason.MAX_DURATION, duration.noProgress().stopReason)
        duration.abort()
    }

    @Test
    fun throttlesLevelsAndStopsAfterSpeechSilence() {
        var now = 0L
        val capture = StreamingPcmCapture(
            temporaryFolder.newFile("levels.pcm"), 16_000,
            policy(trailingSilenceMs = 10, levelUpdateIntervalMs = 5),
            clockMs = { now }
        )
        assertNotNull(capture.append(shortArrayOf(100), 1, 1f).levelToPublish)
        now = 2
        assertNull(capture.append(shortArrayOf(0), 1, 0f).levelToPublish)
        now = 5
        assertNotNull(capture.append(shortArrayOf(0), 1, 0f).levelToPublish)
        now = 10
        assertEquals(CaptureStopReason.TRAILING_SILENCE, capture.append(shortArrayOf(0), 1, 0f).stopReason)
        capture.abort()
    }

    @Test
    fun wavIsStreamedAndAllTemporaryFilesCanBeDeleted() {
        val raw = temporaryFolder.newFile("audio.pcm")
        val capture = StreamingPcmCapture(raw, 16_000)
        capture.append(shortArrayOf(0x1234, (-2).toShort()), 2, 1f)
        val captured = capture.finish()
        val wav = captured.createWavFile(temporaryFolder.root)

        assertEquals(48L, wav.length())
        assertEquals("RIFF", wav.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
        assertTrue(raw.delete())
        assertTrue(wav.delete())
        assertFalse(raw.exists())
        assertFalse(wav.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidReadCounts() {
        val capture = StreamingPcmCapture(temporaryFolder.newFile("invalid.pcm"), 16_000)
        try {
            capture.append(shortArrayOf(1), 2, 1f)
        } finally {
            capture.abort()
        }
    }

    private fun policy(
        maxBytes: Long = 1_024,
        maxDurationMs: Long = 1_000,
        initialSilenceMs: Long = 1_000,
        noProgressMs: Long = 1_000,
        trailingSilenceMs: Long = 1_000,
        levelUpdateIntervalMs: Long = 100
    ) = TranscriptionCapturePolicy(
        maxBytes = maxBytes,
        maxDurationMs = maxDurationMs,
        initialSilenceMs = initialSilenceMs,
        noProgressMs = noProgressMs,
        trailingSilenceMs = trailingSilenceMs,
        levelUpdateIntervalMs = levelUpdateIntervalMs
    )
}
