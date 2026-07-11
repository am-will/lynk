package dev.androidagent.voice.transcription

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VoiceTranscriptionState(
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val audioLevel: Float = 0f,
    val error: String? = null
)

class VoiceTranscriptionManager(
    private val onStateChanged: (VoiceTranscriptionState) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val captureLock = Any()
    private val capturePolicy = TranscriptionCapturePolicy()
    private val stateLock = Any()
    private var state = VoiceTranscriptionState()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var capture: StreamingPcmCapture? = null
    private var deviceSampleRate = TranscriptionAudio.DEFAULT_DEVICE_SAMPLE_RATE
    private var silenceStopRequested = false

    @Volatile
    private var heardSpeechDuringSession = false

    @Volatile
    private var recording = false

    fun currentState(): VoiceTranscriptionState = synchronized(stateLock) { state }

    fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, onSilenceDetected: () -> Unit = {}): Boolean {
        if (recording || currentState().isTranscribing) {
            return false
        }
        if (!hasMicPermission(context)) {
            updateState(VoiceTranscriptionState(error = "Microphone permission is required for transcription."))
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            deviceSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            updateState(VoiceTranscriptionState(error = "Could not initialize microphone recording buffer."))
            return false
        }

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                deviceSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        }.getOrElse { error ->
            updateState(VoiceTranscriptionState(error = "Could not initialize microphone: ${error.message ?: error}"))
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            updateState(VoiceTranscriptionState(error = "Microphone recorder failed to initialize."))
            return false
        }

        val nextCapture = runCatching {
            StreamingPcmCapture(
                file = File.createTempFile("lynk-transcription-", ".pcm", context.cacheDir),
                sampleRate = deviceSampleRate,
                policy = capturePolicy
            )
        }.getOrElse { error ->
            recorder.release()
            updateState(VoiceTranscriptionState(error = "Could not create bounded transcription capture: ${error.message ?: error}"))
            return false
        }
        synchronized(captureLock) { capture = nextCapture }
        audioRecord = recorder
        silenceStopRequested = false
        heardSpeechDuringSession = false
        recording = true

        runCatching { recorder.startRecording() }
            .onFailure { error ->
                recording = false
                audioRecord = null
                recorder.release()
                synchronized(captureLock) { capture?.abort(); capture = null }
                updateState(VoiceTranscriptionState(error = "Could not start microphone recording: ${error.message ?: error}"))
                return false
            }

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recording = false
            audioRecord = null
            recorder.release()
            synchronized(captureLock) { capture?.abort(); capture = null }
            updateState(VoiceTranscriptionState(error = "Microphone recorder did not start."))
            return false
        }

        updateState(VoiceTranscriptionState(isRecording = true))
        recordingThread = Thread(
            { readAudioLoop(recorder, minBufferSize, onSilenceDetected) },
            "VoiceTranscriptionRecorder"
        ).also { it.start() }
        return true
    }

    suspend fun stopAndTranscribe(openAiApiKey: String): String? {
        val captured = stopRecordingAndTake()
        val heardSpeech = heardSpeechDuringSession
        heardSpeechDuringSession = false
        updateState(currentState().copy(isRecording = false, audioLevel = 0f))

        if (captured == null || captured.byteCount == 0L || !heardSpeech) {
            captured?.file?.delete()
            updateState(VoiceTranscriptionState())
            return null
        }
        if (captured.isTooShort()) {
            captured.file.delete()
            updateState(VoiceTranscriptionState())
            return null
        }

        val token = openAiApiKey.trim()
        if (token.isBlank()) {
            captured.file.delete()
            updateState(VoiceTranscriptionState(error = "OpenAI API key is required for transcription."))
            return null
        }

        val wav = try {
            withContext(Dispatchers.IO) { captured.createWavFile(requireNotNull(captured.file.parentFile)) }
        } catch (error: Exception) {
            captured.file.delete()
            updateState(VoiceTranscriptionState(error = error.message ?: error.toString()))
            return null
        }
        captured.file.delete()

        updateState(VoiceTranscriptionState(isTranscribing = true))
        return try {
            val raw = withContext(Dispatchers.IO) { transcribeOpenAi(wav, token) }
            val clean = sanitizeTranscript(raw)
            if (clean.isNullOrBlank()) {
                updateState(VoiceTranscriptionState())
                null
            } else {
                updateState(VoiceTranscriptionState())
                clean
            }
        } catch (error: Exception) {
            updateState(VoiceTranscriptionState(error = error.message ?: error.toString()))
            null
        } finally {
            wav.delete()
        }
    }

    private fun sanitizeTranscript(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()
        text = text.replace(Regex("\\([^)]{0,40}\\)"), "")
        text = text.replace(Regex("\\[[^\\]]{0,40}\\]"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        val lower = text.lowercase()
        val silenceWords = setOf(
            "", ".", "...", "…",
            "you", "you.", "you you", "you you you",
            "thanks", "thank you", "thank you.",
            "silence", "silent",
            "blank audio", "blank_audio", "no audio",
            "music", "soft music", "quiet music", "background music"
        )
        if (lower in silenceWords) return null
        if (text.all { !it.isLetterOrDigit() }) return null
        return text
    }

    fun cancelRecording() {
        stopRecordingAndDiscard()
        updateState(VoiceTranscriptionState())
    }

    fun close() {
        cancelRecording()
        client.dispatcher.executorService.shutdown()
    }

    private fun readAudioLoop(recorder: AudioRecord, minBufferSize: Int, onSilenceDetected: () -> Unit) {
        val buffer = ShortArray((minBufferSize / 2).coerceAtLeast(1))
        while (recording) {
            val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
            val activeCapture = synchronized(captureLock) { capture } ?: break
            if (read <= 0) {
                if (activeCapture.noProgress().stopReason != null) requestCaptureStop(onSilenceDetected)
                continue
            }

            val level = TranscriptionAudio.rmsLevel(buffer, read)
            val update = runCatching { activeCapture.append(buffer, read, level) }.getOrElse { error ->
                updateState(currentState().copy(error = "Transcription capture failed: ${error.message ?: error}"))
                requestCaptureStop(onSilenceDetected)
                break
            }
            update.levelToPublish?.let {
                updateState(currentState().copy(isRecording = true, audioLevel = it, error = null))
            }
            if (level > capturePolicy.silenceFloor) {
                heardSpeechDuringSession = true
            }
            if (update.stopReason != null) requestCaptureStop(onSilenceDetected)
        }
    }

    private fun requestCaptureStop(onStopRequested: () -> Unit) {
        if (silenceStopRequested) return
        silenceStopRequested = true
        mainHandler.post(onStopRequested)
    }

    private fun stopRecorder() {
        recording = false
        audioRecord?.let { recorder ->
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
        }
        audioRecord = null
        recordingThread?.join(1_000)
        recordingThread = null
        silenceStopRequested = false
    }

    private fun stopRecordingAndTake(): CapturedPcm? {
        stopRecorder()
        return synchronized(captureLock) { capture?.finish().also { capture = null } }
    }

    private fun stopRecordingAndDiscard() {
        stopRecorder()
        synchronized(captureLock) {
            capture?.abort()
            capture = null
        }
    }

    private fun transcribeOpenAi(wav: File, token: String): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", OPENAI_TRANSCRIPTION_MODEL)
            .addFormDataPart(
                "file",
                "audio.wav",
                wav.asRequestBody(WAV_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url(OPENAI_TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("Transcription failed (${response.code}): ${body.ifBlank { response.message }}")
            }
            return parseTranscript(body)
        }
    }

    private fun parseTranscript(body: String): String? {
        return runCatching {
            JSONObject(body).optString("text").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.trim().takeIf { it.isNotBlank() }
    }

    private fun updateState(next: VoiceTranscriptionState) {
        synchronized(stateLock) {
            state = next
        }
        mainHandler.post { onStateChanged(next) }
    }

    companion object {
        private const val OPENAI_TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val OPENAI_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}
