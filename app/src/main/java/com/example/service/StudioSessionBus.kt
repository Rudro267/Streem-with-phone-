package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.data.StreamSettingsRepository
import com.example.media.AudioMixerPipeline
import com.example.media.AudioPlayerPipeline
import com.example.media.VideoDecoderPipeline
import com.example.media.VideoEncoderPipeline
import com.example.model.ActiveTransportKind
import com.example.model.AudioLevels
import com.example.model.AudioMixerConfig
import com.example.model.CueUrgency
import com.example.model.DeviceRole
import com.example.model.DiagnosticLogEntry
import com.example.model.LinkStatus
import com.example.model.RemoteTouchEvent
import com.example.model.StreamQualityProfile
import com.example.model.StreamerCue
import com.example.model.TelemetrySnapshot
import com.example.model.TelestratorStroke
import com.example.transport.PacketCodec
import com.example.transport.PacketType
import com.example.transport.UnifiedUsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StudioSessionBus private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepo = StreamSettingsRepository(appContext)
    private val transport = UnifiedUsbTransport(appContext)

    // Role-isolated lazy pipelines: Host allocates encoder/mixer only; Director allocates decoder/player only.
    private var videoEncoder: VideoEncoderPipeline? = null
    private var audioMixer: AudioMixerPipeline? = null
    private var mediaProjection: MediaProjection? = null

    @Volatile private var videoDecoder: VideoDecoderPipeline? = null
    @Volatile private var audioPlayer: AudioPlayerPipeline? = null

    // Reusable 968-byte buffer (8B header + 480 samples * 2B) for zero-allocation 10ms audio frames
    private val audioPacketScratch = ByteArray(8 + 480 * 2)

    private val _role = MutableStateFlow(DeviceRole.IDLE)
    val role: StateFlow<DeviceRole> = _role.asStateFlow()

    val linkStatus: StateFlow<LinkStatus> = transport.status
    val activeTransport: StateFlow<ActiveTransportKind> = transport.activeKind
    val peerAddress: StateFlow<String> = transport.peerAddress

    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _audioLevels = MutableStateFlow(AudioLevels())
    val audioLevels: StateFlow<AudioLevels> = _audioLevels.asStateFlow()

    private val _isRemoteControlEnabled = MutableStateFlow(true)
    val isRemoteControlEnabled: StateFlow<Boolean> = _isRemoteControlEnabled.asStateFlow()

    private val _peerRemoteControlEnabled = MutableStateFlow(true)
    val peerRemoteControlEnabled: StateFlow<Boolean> = _peerRemoteControlEnabled.asStateFlow()

    private val _isDirectorAudioMuted = MutableStateFlow(false)
    val isDirectorAudioMuted: StateFlow<Boolean> = _isDirectorAudioMuted.asStateFlow()

    private val _telestratorStrokes = MutableStateFlow<List<TelestratorStroke>>(emptyList())
    val telestratorStrokes: StateFlow<List<TelestratorStroke>> = _telestratorStrokes.asStateFlow()

    private val _cues = MutableStateFlow<List<StreamerCue>>(emptyList())
    val cues: StateFlow<List<StreamerCue>> = _cues.asStateFlow()

    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    private val _latestTouchPulse = MutableStateFlow<Pair<Float, Float>?>(null)
    val latestTouchPulse: StateFlow<Pair<Float, Float>?> = _latestTouchPulse.asStateFlow()

    private val uiForeground = AtomicBoolean(true)
    private val logIdGen = AtomicLong(1)
    private var heartbeatJob: Job? = null
    private var directorConnectJob: Job? = null
    private var lastQualityProfile = StreamQualityProfile()
    private var lastMixerConfig = AudioMixerConfig()

    init {
        scope.launch {
            transport.activeKind.collect { kind ->
                _telemetry.value = _telemetry.value.copy(activeTransport = kind)
            }
        }
        scope.launch {
            transport.peerAddress.collect { addr ->
                _telemetry.value = _telemetry.value.copy(peerAddress = addr)
            }
        }
        scope.launch(Dispatchers.IO) {
            transport.incomingPackets.collect { packet ->
                handleIncomingPacket(packet.type, packet.flags, packet.timestampUs, packet.payload)
            }
        }
        scope.launch {
            settingsRepo.settingsFlow.collect { saved ->
                lastQualityProfile = saved.qualityProfile
                lastMixerConfig = saved.audioMixer
                audioMixer?.updateConfig(saved.audioMixer)
                if (videoEncoder != null) {
                    val (sw, sh) = getScreenDimensions()
                    videoEncoder?.updateProfile(saved.qualityProfile, sw, sh)
                }
            }
        }
    }

    fun setUiForeground(visible: Boolean) {
        uiForeground.set(visible)
        if (!visible) {
            _latestTouchPulse.value = null
        }
    }

    fun isUiForeground(): Boolean = uiForeground.get()

    fun startHostSession(projection: MediaProjection) {
        stopSessionInternal(notifyService = false)
        _role.value = DeviceRole.HOST
        mediaProjection = projection
        appendLog("SESSION", "Host broadcast active (Lite memory profile)", false)

        scope.launch {
            val saved = settingsRepo.settingsFlow.first()
            lastQualityProfile = saved.qualityProfile
            lastMixerConfig = saved.audioMixer

            transport.start(
                role = DeviceRole.HOST,
                preference = saved.transportPreference,
                manualPeerIp = saved.manualPeerIp,
                onLog = ::appendLog
            )

            val (screenW, screenH) = getScreenDimensions()
            val densityDpi = appContext.resources.displayMetrics.densityDpi

            val encoder = VideoEncoderPipeline(
                mediaProjection = projection,
                densityDpi = densityDpi,
                initialProfile = saved.qualityProfile,
                onVideoConfig = { w, h, csd ->
                    val payload = PacketCodec.encodeVideoConfig(w, h, 0, csd)
                    transport.sendPacket(PacketType.VIDEO_CONFIG, payload)
                },
                onEncodedFrame = { data, isKey, _ ->
                    val flags = if (isKey) PacketCodec.FLAG_KEYFRAME else 0
                    transport.sendPacket(PacketType.VIDEO_FRAME, data, flags)
                },
                onTelemetrySample = { fps, kbps, latMs, w, h ->
                    if (uiForeground.get()) {
                        _telemetry.value = _telemetry.value.copy(
                            actualFps = fps,
                            bitrateKbps = kbps,
                            encodeLatencyMs = latMs,
                            videoWidth = w,
                            videoHeight = h
                        )
                    }
                },
                onProjectionStopped = {
                    stopSession()
                },
                onLog = ::appendLog
            )
            videoEncoder = encoder
            encoder.start(screenW, screenH)

            val hasMic = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val mixer = AudioMixerPipeline(
                mediaProjection = projection,
                hasMicPermission = hasMic,
                initialConfig = saved.audioMixer,
                onMixedPcmFrame = { sr, ch, pcm, len ->
                    val pktLen = PacketCodec.encodeAudioPcmInto(sr, ch, pcm, len, audioPacketScratch)
                    transport.sendPacketSlice(PacketType.AUDIO_PCM, audioPacketScratch, pktLen)
                },
                onAudioLevels = { levels ->
                    if (_role.value == DeviceRole.HOST && uiForeground.get()) {
                        _audioLevels.value = levels
                    }
                },
                shouldEmitUiMeters = { uiForeground.get() },
                onLog = ::appendLog
            )
            audioMixer = mixer
            mixer.start()

            startHeartbeatLoop()
        }
    }

    fun startDirectorSession() {
        stopSessionInternal(notifyService = false)
        _role.value = DeviceRole.DIRECTOR
        appendLog("SESSION", "Starting Director Deck receiver", false)

        videoDecoder = VideoDecoderPipeline(
            onResolutionChanged = { w, h ->
                _telemetry.value = _telemetry.value.copy(videoWidth = w, videoHeight = h)
            },
            onTelemetrySample = { fps, kbps, w, h ->
                if (uiForeground.get()) {
                    _telemetry.value = _telemetry.value.copy(
                        actualFps = fps,
                        bitrateKbps = kbps,
                        videoWidth = w,
                        videoHeight = h
                    )
                }
            },
            onLog = ::appendLog
        )

        audioPlayer = AudioPlayerPipeline(
            onAudioLevels = { levels ->
                if (_role.value == DeviceRole.DIRECTOR && uiForeground.get()) {
                    _audioLevels.value = levels
                }
            }
        ).apply {
            setMuted(_isDirectorAudioMuted.value)
        }

        scope.launch {
            val saved = settingsRepo.settingsFlow.first()
            transport.start(
                role = DeviceRole.DIRECTOR,
                preference = saved.transportPreference,
                manualPeerIp = saved.manualPeerIp,
                onLog = ::appendLog
            )
            startHeartbeatLoop()
            directorConnectJob = scope.launch {
                linkStatus.collect { status ->
                    if (status == LinkStatus.CONNECTED && _role.value == DeviceRole.DIRECTOR) {
                        delay(150)
                        transport.sendPacket(PacketType.KEYFRAME_REQUEST, ByteArray(0))
                    }
                }
            }
        }
    }

    fun attachDirectorSurface(surface: Surface?) {
        videoDecoder?.attachSurface(surface)
        if (surface != null && _role.value == DeviceRole.DIRECTOR) {
            scope.launch(Dispatchers.IO) {
                transport.sendPacket(PacketType.KEYFRAME_REQUEST, ByteArray(0))
            }
        }
    }

    fun setRemoteControlEnabled(enabled: Boolean) {
        _isRemoteControlEnabled.value = enabled
        appendLog("REMOTE", if (enabled) "Remote touch unlocked" else "Remote touch LOCKED by Host", false)
        scope.launch(Dispatchers.IO) {
            transport.sendPacket(
                PacketType.CONTROL_PING,
                PacketCodec.encodePing(System.currentTimeMillis(), enabled)
            )
        }
        if (_role.value == DeviceRole.HOST) {
            val refreshIntent = Intent(appContext, StudioStreamService::class.java).apply {
                action = StudioStreamService.ACTION_REFRESH_NOTIFICATION
            }
            try {
                appContext.startService(refreshIntent)
            } catch (_: Exception) {}
        }
    }

    fun setDirectorAudioMuted(muted: Boolean) {
        _isDirectorAudioMuted.value = muted
        audioPlayer?.setMuted(muted)
    }

    fun sendRemoteTouch(event: RemoteTouchEvent) {
        if (_role.value != DeviceRole.DIRECTOR) return
        if (uiForeground.get()) {
            _latestTouchPulse.value = event.normX to event.normY
        }
        scope.launch(Dispatchers.IO) {
            transport.sendPacket(PacketType.REMOTE_TOUCH, PacketCodec.encodeRemoteTouch(event))
        }
    }

    fun upsertTelestratorStroke(stroke: TelestratorStroke, syncToPeer: Boolean) {
        val current = _telestratorStrokes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == stroke.id }
        if (idx >= 0) {
            current[idx] = stroke
        } else {
            current.add(stroke)
        }
        _telestratorStrokes.value = current.takeLast(30)
        if (syncToPeer) {
            scope.launch(Dispatchers.IO) {
                transport.sendPacket(PacketType.TELESTRATOR_STROKE, PacketCodec.encodeTelestratorStroke(stroke))
            }
        }
    }

    fun clearTelestrator(syncToPeer: Boolean = true) {
        _telestratorStrokes.value = emptyList()
        if (syncToPeer) {
            scope.launch(Dispatchers.IO) {
                transport.sendPacket(PacketType.TELESTRATOR_STROKE, PacketCodec.encodeTelestratorClear())
            }
        }
    }

    fun sendStreamerCue(text: String, urgency: CueUrgency) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val sender = if (_role.value == DeviceRole.HOST) "Host" else "Director"
        val cue = StreamerCue(
            id = System.currentTimeMillis(),
            text = clean,
            urgency = urgency,
            senderRole = sender
        )
        _cues.value = listOf(cue) + _cues.value.take(19)
        scope.launch(Dispatchers.IO) {
            transport.sendPacket(PacketType.STREAMER_CUE, PacketCodec.encodeStreamerCue(cue))
        }
    }

    fun acknowledgeCue(cueId: Long) {
        _cues.value = _cues.value.map { if (it.id == cueId) it.copy(acknowledged = true) else it }
        scope.launch(Dispatchers.IO) {
            transport.sendPacket(PacketType.CUE_ACK, PacketCodec.encodeCueAck(cueId))
        }
    }

    fun requestKeyframe() {
        if (_role.value == DeviceRole.HOST) {
            videoEncoder?.requestKeyFrame()
        } else if (_role.value == DeviceRole.DIRECTOR) {
            scope.launch(Dispatchers.IO) {
                transport.sendPacket(PacketType.KEYFRAME_REQUEST, ByteArray(0))
            }
        }
    }

    fun stopSession() {
        stopSessionInternal(notifyService = true)
    }

    private fun stopSessionInternal(notifyService: Boolean) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        directorConnectJob?.cancel()
        directorConnectJob = null
        videoEncoder?.stop()
        videoEncoder = null
        audioMixer?.stop()
        audioMixer = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        transport.stop()
        _role.value = DeviceRole.IDLE
        _audioLevels.value = AudioLevels()
        _telemetry.value = TelemetrySnapshot()
        _latestTouchPulse.value = null

        if (notifyService) {
            val stopIntent = Intent(appContext, StudioStreamService::class.java).apply {
                action = StudioStreamService.ACTION_STOP_SESSION
            }
            try {
                appContext.stopService(stopIntent)
            } catch (_: Exception) {}
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (transport.status.value == LinkStatus.CONNECTED) {
                    transport.sendPacket(
                        PacketType.CONTROL_PING,
                        PacketCodec.encodePing(System.currentTimeMillis(), _isRemoteControlEnabled.value)
                    )
                }
            }
        }
    }

    private fun handleIncomingPacket(type: PacketType, flags: Byte, ptsUs: Long, payload: ByteArray) {
        when (type) {
            PacketType.VIDEO_CONFIG -> {
                val cfg = PacketCodec.decodeVideoConfig(payload) ?: return
                videoDecoder?.onVideoConfig(cfg.width, cfg.height, cfg.csd)
            }
            PacketType.VIDEO_FRAME -> {
                val isKey = (flags.toInt() and PacketCodec.FLAG_KEYFRAME.toInt()) != 0
                videoDecoder?.submitFrame(payload, ptsUs, isKey)
            }
            PacketType.AUDIO_PCM -> {
                val audio = PacketCodec.decodeAudioPcm(payload) ?: return
                audioPlayer?.writePcm(audio.sampleRate, audio.channels, audio.pcm)
            }
            PacketType.REMOTE_TOUCH -> {
                if (_role.value == DeviceRole.HOST && _isRemoteControlEnabled.value) {
                    val event = PacketCodec.decodeRemoteTouch(payload) ?: return
                    if (uiForeground.get()) {
                        _latestTouchPulse.value = event.normX to event.normY
                    }
                    RemoteTouchService.dispatchRemoteTouch(event)
                }
            }
            PacketType.TELESTRATOR_STROKE -> {
                when (val cmd = PacketCodec.decodeTelestrator(payload)) {
                    PacketCodec.TelestratorCommand.ClearAll -> _telestratorStrokes.value = emptyList()
                    is PacketCodec.TelestratorCommand.UpsertStroke -> {
                        val stroke = cmd.stroke
                        val current = _telestratorStrokes.value.toMutableList()
                        val idx = current.indexOfFirst { it.id == stroke.id }
                        if (idx >= 0) current[idx] = stroke else current.add(stroke)
                        _telestratorStrokes.value = current.takeLast(30)
                    }
                    null -> Unit
                }
            }
            PacketType.STREAMER_CUE -> {
                val cue = PacketCodec.decodeStreamerCue(payload) ?: return
                _cues.value = listOf(cue) + _cues.value.take(19)
                appendLog("CUE", "[${cue.senderRole}] ${cue.text}", false)
                transport.sendPacket(PacketType.CUE_ACK, PacketCodec.encodeCueAck(cue.id))
            }
            PacketType.CUE_ACK -> {
                val cueId = PacketCodec.decodeCueAck(payload) ?: return
                _cues.value = _cues.value.map { if (it.id == cueId) it.copy(acknowledged = true) else it }
            }
            PacketType.CONTROL_PING -> {
                val ping = PacketCodec.decodePing(payload) ?: return
                _peerRemoteControlEnabled.value = ping.remoteControlEnabled
                transport.sendPacket(PacketType.CONTROL_PONG, PacketCodec.encodePong(ping.sentAtMs))
            }
            PacketType.CONTROL_PONG -> {
                val sentAt = PacketCodec.decodePong(payload) ?: return
                if (uiForeground.get()) {
                    val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceIn(0, 9999)
                    _telemetry.value = _telemetry.value.copy(rttMs = rtt)
                }
            }
            PacketType.KEYFRAME_REQUEST -> {
                if (_role.value == DeviceRole.HOST) {
                    videoEncoder?.requestKeyFrame()
                }
            }
        }
    }

    fun appendLog(tag: String, message: String, isError: Boolean) {
        val entry = DiagnosticLogEntry(
            id = logIdGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            tag = tag,
            message = message,
            isError = isError
        )
        _logs.value = listOf(entry) + _logs.value.take(24)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val wm = appContext.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = wm.maximumWindowMetrics.bounds
            b.width().coerceAtLeast(720) to b.height().coerceAtLeast(1280)
        } else {
            val dm = appContext.resources.displayMetrics
            dm.widthPixels.coerceAtLeast(720) to dm.heightPixels.coerceAtLeast(1280)
        }
    }

    companion object {
        @Volatile
        private var instance: StudioSessionBus? = null

        fun getInstance(context: Context): StudioSessionBus {
            return instance ?: synchronized(this) {
                instance ?: StudioSessionBus(context.applicationContext).also { instance = it }
            }
        }
    }
}
