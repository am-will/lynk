package dev.androidagent.voice.transcription

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

internal data class TranscriptionCapturePolicy(
    val maxBytes: Long = 25L * 1024L * 1024L,
    val maxDurationMs: Long = 120_000L,
    val initialSilenceMs: Long = 10_000L,
    val noProgressMs: Long = 3_000L,
    val trailingSilenceMs: Long = 1_500L,
    val levelUpdateIntervalMs: Long = 100L,
    val silenceFloor: Float = 0.02f
)

internal enum class CaptureStopReason {
    MAX_BYTES,
    MAX_DURATION,
    INITIAL_SILENCE,
    NO_PROGRESS,
    TRAILING_SILENCE
}

internal data class CaptureUpdate(
    val stopReason: CaptureStopReason? = null,
    val levelToPublish: Float? = null
)

internal data class CapturedPcm(
    val file: File,
    val sampleRate: Int,
    val byteCount: Long,
    val heardSpeech: Boolean
) {
    fun isTooShort(): Boolean = byteCount < (sampleRate * 2L * TranscriptionAudio.MIN_RECORDING_SECONDS).toLong()

    fun createWavFile(directory: File): File {
        val wav = File.createTempFile("lynk-transcription-", ".wav", directory)
        try {
            FileOutputStream(wav).buffered().use { output ->
                writeWavHeader(output, byteCount, sampleRate)
                file.inputStream().buffered().use { it.copyTo(output) }
            }
            return wav
        } catch (error: Throwable) {
            wav.delete()
            throw error
        }
    }
}

internal class StreamingPcmCapture(
    private val file: File,
    private val sampleRate: Int,
    private val policy: TranscriptionCapturePolicy = TranscriptionCapturePolicy(),
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val output = BufferedOutputStream(FileOutputStream(file))
    private val startedAt = clockMs()
    private var lastProgressAt = startedAt
    private var lastSpeechAt = startedAt
    private var lastLevelPublishedAt = Long.MIN_VALUE
    private var byteCount = 0L
    private var heardSpeech = false
    private var closed = false

    @Synchronized
    fun append(samples: ShortArray, count: Int, level: Float): CaptureUpdate {
        check(!closed) { "Capture is already closed" }
        require(count in 0..samples.size) { "Invalid PCM sample count $count for buffer ${samples.size}" }
        val now = clockMs()
        if (count == 0) return boundsAt(now)

        val incomingBytes = count * 2L
        if (byteCount + incomingBytes > policy.maxBytes) {
            return CaptureUpdate(CaptureStopReason.MAX_BYTES)
        }
        val bytes = ByteArray(count * 2)
        repeat(count) { index ->
            val sample = samples[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample ushr 8).toByte()
        }
        output.write(bytes)
        byteCount += incomingBytes
        lastProgressAt = now
        if (level > policy.silenceFloor) {
            heardSpeech = true
            lastSpeechAt = now
        }
        val stop = boundsAt(now).stopReason
        val publish = if (lastLevelPublishedAt == Long.MIN_VALUE || now - lastLevelPublishedAt >= policy.levelUpdateIntervalMs) {
            lastLevelPublishedAt = now
            level
        } else null
        return CaptureUpdate(stop, publish)
    }

    @Synchronized
    fun noProgress(): CaptureUpdate {
        check(!closed) { "Capture is already closed" }
        return boundsAt(clockMs())
    }

    @Synchronized
    fun finish(): CapturedPcm {
        closeOutput()
        return CapturedPcm(file, sampleRate, byteCount, heardSpeech)
    }

    @Synchronized
    fun abort() {
        closeOutput()
        file.delete()
    }

    private fun boundsAt(now: Long): CaptureUpdate {
        val reason = when {
            byteCount >= policy.maxBytes -> CaptureStopReason.MAX_BYTES
            now - startedAt >= policy.maxDurationMs -> CaptureStopReason.MAX_DURATION
            !heardSpeech && now - startedAt >= policy.initialSilenceMs -> CaptureStopReason.INITIAL_SILENCE
            now - lastProgressAt >= policy.noProgressMs -> CaptureStopReason.NO_PROGRESS
            heardSpeech && now - lastSpeechAt >= policy.trailingSilenceMs -> CaptureStopReason.TRAILING_SILENCE
            else -> null
        }
        return CaptureUpdate(reason)
    }

    private fun closeOutput() {
        if (closed) return
        closed = true
        output.close()
    }
}

private fun writeWavHeader(output: java.io.OutputStream, dataBytes: Long, sampleRate: Int) {
    require(dataBytes <= UInt.MAX_VALUE.toLong() - 36L) { "PCM capture is too large for WAV" }
    fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
    fun little16(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }
    fun little32(value: Long) {
        repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }
    ascii("RIFF")
    little32(36L + dataBytes)
    ascii("WAVEfmt ")
    little32(16)
    little16(1)
    little16(1)
    little32(sampleRate.toLong())
    little32(sampleRate * 2L)
    little16(2)
    little16(16)
    ascii("data")
    little32(dataBytes)
}
