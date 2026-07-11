package dev.androidagent.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface RealtimeVoiceSession {
    suspend fun createOffer(): String
    suspend fun applyAnswer(answerSdp: String)
    fun setMuted(muted: Boolean)
    fun sendJsonEvent(event: JSONObject): Boolean
    fun close()
}

class RealtimeWebRtcSession(
    private val context: Context,
    private val onDataChannelEvent: (String) -> Unit,
    private val onConnectionState: (String) -> Unit
) : RealtimeVoiceSession {
    private val disposed = AtomicBoolean(false)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphone: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var eventsChannel: DataChannel? = null
    private val iceGatheringComplete = CompletableDeferred<Unit>()

    override suspend fun createOffer(): String = withContext(Dispatchers.Main) {
        val appContext = context.applicationContext
        ensureFactoryInitialized(appContext)
        acquireAudioFocus()

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val createdFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = createdFactory

        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val createdPeerConnection = createdFactory.createPeerConnection(config, observer())
            ?: throw IllegalStateException("Failed to create WebRTC peer connection")
        peerConnection = createdPeerConnection

        audioSource = createdFactory.createAudioSource(MediaConstraints())
        audioTrack = createdFactory.createAudioTrack("phone-microphone", audioSource).also { track ->
            createdPeerConnection.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
        }

        eventsChannel = createdPeerConnection.createDataChannel("oai-events", DataChannel.Init()).also { channel ->
            channel.registerObserver(dataChannelObserver())
        }

        val offer = createdPeerConnection.awaitOffer()
        createdPeerConnection.awaitSetLocalDescription(offer)
        withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) {
            iceGatheringComplete.await()
        }
        createdPeerConnection.localDescription?.description ?: offer.description
    }

    override suspend fun applyAnswer(answerSdp: String) = withContext(Dispatchers.Main) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.awaitSetRemoteDescription(answer)
            ?: throw IllegalStateException("No active WebRTC peer connection")
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    override fun sendJsonEvent(event: JSONObject): Boolean {
        val channel = eventsChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) {
            return false
        }
        val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) {
            return
        }
        val channel = eventsChannel
        val track = audioTrack
        val source = audioSource
        val connection = peerConnection
        val createdFactory = factory
        val adm = audioDeviceModule
        eventsChannel = null
        audioTrack = null
        audioSource = null
        peerConnection = null
        factory = null
        audioDeviceModule = null

        runCatching { channel?.unregisterObserver() }
        runCatching { channel?.close() }
        runCatching { track?.setEnabled(false) }
        runCatching { connection?.close() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { createdFactory?.dispose() }
        runCatching { adm?.release() }
        runCatching { releaseAudioFocus() }
    }

    private fun observer(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onConnectionState(state.name.lowercase())
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE && !iceGatheringComplete.isCompleted) {
                    iceGatheringComplete.complete(Unit)
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                channel.registerObserver(dataChannelObserver())
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
        }
    }

    private fun dataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    return
                }
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onDataChannelEvent(String(bytes, StandardCharsets.UTF_8))
            }
        }
    }

    private fun acquireAudioFocus() {
        previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = previousMode
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = previousSpeakerphone
    }

    companion object {
        private const val ICE_GATHERING_TIMEOUT_MS = 2_500L
        private val initialized = AtomicBoolean(false)

        private fun ensureFactoryInitialized(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }
}

private suspend fun PeerConnection.awaitOffer(): SessionDescription {
    return suspendCancellableCoroutine { continuation ->
        createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                continuation.resume(description)
            }

            override fun onCreateFailure(error: String) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }, MediaConstraints())
    }
}

private suspend fun PeerConnection.awaitSetLocalDescription(description: SessionDescription) {
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }, description)
    }
}

private suspend fun PeerConnection.awaitSetRemoteDescription(description: SessionDescription) {
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }, description)
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}
