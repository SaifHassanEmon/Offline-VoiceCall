package com.example.lanvoicecaller.network.webrtc

import android.content.Context
import android.util.Log
import com.example.lanvoicecaller.network.signaling.SignalingMessage
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private const val TAG = "WebRtcManager"

/**
 * Manages the WebRTC PeerConnection lifecycle for a 1-on-1 voice call.
 *
 * Features provided automatically by WebRTC:
 *  - Acoustic Echo Cancellation (AEC)
 *  - Noise Suppression (NS)
 *  - Automatic Gain Control (AGC)
 *  - Opus audio codec
 *  - Jitter buffer & packet loss concealment
 */
class WebRtcManager(private val context: Context) {

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    var onLocalSdp: ((type: String, sdp: String) -> Unit)? = null
    var onIceCandidate: ((SignalingMessage.IceCandidate) -> Unit)? = null

    // ── Initialise ───────────────────────────────────────────────────────────

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    // ── Create PeerConnection ────────────────────────────────────────────────

    fun createPeerConnection() {
        val iceServers = emptyList<PeerConnection.IceServer>()  // No STUN/TURN needed on LAN
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(
                    SignalingMessage.IceCandidate(
                        sdp = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state: $newState")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {}
        }

        peerConnection = factory?.createPeerConnection(config, observer)

        // Add local audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource: AudioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack("local_audio", audioSource)
        peerConnection?.addTrack(localAudioTrack, listOf("stream_id"))
    }

    // ── Offer / Answer ───────────────────────────────────────────────────────

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                onLocalSdp?.invoke(sdp.type.canonicalForm(), sdp.description)
            }
        }, constraints)
    }

    fun setRemoteOffer(sdp: String) {
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDesc)
    }

    fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                onLocalSdp?.invoke(sdp.type.canonicalForm(), sdp.description)
            }
        }, constraints)
    }

    fun setRemoteAnswer(sdp: String) {
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDesc)
    }

    fun addIceCandidate(candidate: SignalingMessage.IceCandidate) {
        peerConnection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        )
    }

    // ── Mute ─────────────────────────────────────────────────────────────────

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    fun close() {
        runCatching { peerConnection?.close() }
        runCatching { factory?.dispose() }
        peerConnection = null
        factory = null
    }
}

/** Convenience base class for SdpObserver — only override what you need. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("SdpObserver", "createFailure: $error") }
    override fun onSetFailure(error: String) { Log.e("SdpObserver", "setFailure: $error") }
}
