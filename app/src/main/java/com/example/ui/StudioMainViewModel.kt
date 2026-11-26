package com.example.ui

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.StreamSettingsRepository
import com.example.data.StudioSavedSettings
import com.example.model.ActiveTransportKind
import com.example.model.AudioLevels
import com.example.model.AudioMixerConfig
import com.example.model.CueUrgency
import com.example.model.DeviceRole
import com.example.model.DiagnosticLogEntry
import com.example.model.InteractionMode
import com.example.model.LinkStatus
import com.example.model.RemoteTouchAction
import com.example.model.RemoteTouchEvent
import com.example.model.StreamQualityProfile
import com.example.model.StreamerCue
import com.example.model.TelemetrySnapshot
import com.example.model.TelestratorPoint
import com.example.model.TelestratorStroke
import com.example.model.TransportPreference
import com.example.model.UsbProbeResult
import com.example.service.RemoteTouchService
import com.example.service.StudioSessionBus
import com.example.service.StudioStreamService
import com.example.transport.UsbDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StudioUiState(
    val role: DeviceRole = DeviceRole.IDLE,
    val linkStatus: LinkStatus = LinkStatus.DISCONNECTED,
    val activeTransport: ActiveTransportKind = ActiveTransportKind.NONE,
    val peerAddress: String = "--",
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val audioLevels: AudioLevels = AudioLevels(),
    val isRemoteControlEnabled: Boolean = true,
    val peerRemoteControlEnabled: Boolean = true,
    val isDirectorAudioMuted: Boolean = false,
    val interactionMode: InteractionMode = InteractionMode.TOUCH_CONTROL,
    val selectedBrushColorArgb: Long = 0xFFFF2D46,
    val strokes: List<TelestratorStroke> = emptyList(),
    val cues: List<StreamerCue> = emptyList(),
    val logs: List<DiagnosticLogEntry> = emptyList(),
    val touchPulse: Pair<Float, Float>? = null,
    val savedSettings: StudioSavedSettings = StudioSavedSettings(),
    val usbProbe: UsbProbeResult = UsbProbeResult(),
    val hasMicPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isAccessibilityEnabled: Boolean = false,
    val showAccessibilityDialog: Boolean = false
)

class StudioMainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settingsRepo = StreamSettingsRepository(context)
    private val sessionBus = StudioSessionBus.getInstance(context)

    private val _usbProbe = MutableStateFlow(UsbProbeResult())
    private val _interactionMode = MutableStateFlow(InteractionMode.TOUCH_CONTROL)
    private val _selectedBrushColor = MutableStateFlow(0xFFFF2D46L)
    private val _hasMicPermission = MutableStateFlow(false)
    private val _hasNotificationPermission = MutableStateFlow(true)
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    private val _showAccessibilityDialog = MutableStateFlow(false)

    private data class CoreSessionSnapshot(
        val role: DeviceRole,
        val linkStatus: LinkStatus,
        val activeTransport: ActiveTransportKind,
        val peerAddress: String,
        val telemetry: TelemetrySnapshot,
        val audioLevels: AudioLevels
    )

    private data class CollabSnapshot(
        val remoteEnabled: Boolean,
        val peerRemoteEnabled: Boolean,
        val directorMuted: Boolean,
        val strokes: List<TelestratorStroke>,
        val cues: List<StreamerCue>,
        val logs: List<DiagnosticLogEntry>,
        val touchPulse: Pair<Float, Float>?
    )

    private data class LocalControlsSnapshot(
        val saved: StudioSavedSettings,
        val probe: UsbProbeResult,
        val interactionMode: InteractionMode,
        val brushColor: Long,
        val hasMic: Boolean,
        val hasNotif: Boolean,
        val a11yEnabled: Boolean,
        val showA11yDialog: Boolean
    )

    private val coreFlow = combine(
        sessionBus.role,
        sessionBus.linkStatus,
        sessionBus.activeTransport,
        sessionBus.peerAddress,
        sessionBus.telemetry,
        sessionBus.audioLevels
    ) { arr ->
        CoreSessionSnapshot(
            role = arr[0] as DeviceRole,
            linkStatus = arr[1] as LinkStatus,
            activeTransport = arr[2] as ActiveTransportKind,
            peerAddress = arr[3] as String,
            telemetry = arr[4] as TelemetrySnapshot,
            audioLevels = arr[5] as AudioLevels
        )
    }

    @Suppress("UNCHECKED_CAST")
    private val collabFlow = combine(
        sessionBus.isRemoteControlEnabled,
        sessionBus.peerRemoteControlEnabled,
        sessionBus.isDirectorAudioMuted,
        sessionBus.telestratorStrokes,
        sessionBus.cues,
        sessionBus.logs,
        sessionBus.latestTouchPulse
    ) { arr ->
        CollabSnapshot(
            remoteEnabled = arr[0] as Boolean,
            peerRemoteEnabled = arr[1] as Boolean,
            directorMuted = arr[2] as Boolean,
            strokes = arr[3] as List<TelestratorStroke>,
            cues = arr[4] as List<StreamerCue>,
            logs = arr[5] as List<DiagnosticLogEntry>,
            touchPulse = arr[6] as Pair<Float, Float>?
        )
    }

    private val localControlsFlow = combine(
        settingsRepo.settingsFlow,
        _usbProbe,
        _interactionMode,
        _selectedBrushColor,
        _hasMicPermission,
        _hasNotificationPermission,
        _isAccessibilityEnabled,
        _showAccessibilityDialog
    ) { arr ->
        LocalControlsSnapshot(
            saved = arr[0] as StudioSavedSettings,
            probe = arr[1] as UsbProbeResult,
            interactionMode = arr[2] as InteractionMode,
            brushColor = arr[3] as Long,
            hasMic = arr[4] as Boolean,
            hasNotif = arr[5] as Boolean,
            a11yEnabled = arr[6] as Boolean,
            showA11yDialog = arr[7] as Boolean
        )
    }

    val uiState: StateFlow<StudioUiState> = combine(
        coreFlow,
        collabFlow,
        localControlsFlow
    ) { core, collab, local ->
        StudioUiState(
            role = core.role,
            linkStatus = core.linkStatus,
            activeTransport = core.activeTransport,
            peerAddress = core.peerAddress,
            telemetry = core.telemetry,
            audioLevels = core.audioLevels,
            isRemoteControlEnabled = collab.remoteEnabled,
            peerRemoteControlEnabled = collab.peerRemoteEnabled,
            isDirectorAudioMuted = collab.directorMuted,
            interactionMode = local.interactionMode,
            selectedBrushColorArgb = local.brushColor,
            strokes = collab.strokes,
            cues = collab.cues,
            logs = collab.logs,
            touchPulse = collab.touchPulse,
            savedSettings = local.saved,
            usbProbe = local.probe,
            hasMicPermission = local.hasMic,
            hasNotificationPermission = local.hasNotif,
            isAccessibilityEnabled = local.a11yEnabled,
            showAccessibilityDialog = local.showA11yDialog
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioUiState())

    init {
        refreshPermissionsAndProbe()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500)
                if (sessionBus.role.value == DeviceRole.IDLE && sessionBus.isUiForeground()) {
                    _usbProbe.value = UsbDiscovery.probe(context)
                    _isAccessibilityEnabled.value = RemoteTouchService.isEnabledInSettings(context)
                }
            }
        }
    }

    fun setUiForeground(visible: Boolean) {
        sessionBus.setUiForeground(visible)
        if (visible && sessionBus.role.value == DeviceRole.IDLE) {
            refreshPermissionsAndProbe()
        }
    }

    fun refreshPermissionsAndProbe() {
        _hasMicPermission.value = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        _hasNotificationPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        _isAccessibilityEnabled.value = RemoteTouchService.isEnabledInSettings(context)
        viewModelScope.launch(Dispatchers.IO) {
            _usbProbe.value = UsbDiscovery.probe(context)
        }
    }

    fun startHostWithProjection(resultCode: Int, projectionData: Intent) {
        val intent = Intent(context, StudioStreamService::class.java).apply {
            action = StudioStreamService.ACTION_START_HOST
            putExtra(StudioStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StudioStreamService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun startDirector() {
        val intent = Intent(context, StudioStreamService::class.java).apply {
            action = StudioStreamService.ACTION_START_DIRECTOR
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopSession() {
        sessionBus.stopSession()
    }

    fun attachDirectorSurface(surface: Surface?) {
        sessionBus.attachDirectorSurface(surface)
    }

    fun setRemoteControlEnabled(enabled: Boolean) {
        sessionBus.setRemoteControlEnabled(enabled)
    }

    fun setDirectorAudioMuted(muted: Boolean) {
        sessionBus.setDirectorAudioMuted(muted)
    }

    fun setInteractionMode(mode: InteractionMode) {
        _interactionMode.value = mode
    }

    fun setBrushColor(colorArgb: Long) {
        _selectedBrushColor.value = colorArgb
    }

    fun sendRemoteTouch(action: RemoteTouchAction, normX: Float, normY: Float) {
        sessionBus.sendRemoteTouch(RemoteTouchEvent(action, normX, normY))
    }

    fun upsertStroke(id: Long, points: List<TelestratorPoint>) {
        val stroke = TelestratorStroke(
            id = id,
            colorArgb = _selectedBrushColor.value,
            widthNorm = 0.008f,
            points = points
        )
        sessionBus.upsertTelestratorStroke(stroke, syncToPeer = true)
    }

    fun clearTelestrator() {
        sessionBus.clearTelestrator(syncToPeer = true)
    }

    fun sendCue(text: String, urgency: CueUrgency = CueUrgency.ACTION) {
        sessionBus.sendStreamerCue(text, urgency)
    }

    fun acknowledgeCue(cueId: Long) {
        sessionBus.acknowledgeCue(cueId)
    }

    fun requestKeyframe() {
        sessionBus.requestKeyframe()
    }

    fun updateQualityProfile(profile: StreamQualityProfile) {
        viewModelScope.launch { settingsRepo.updateQualityProfile(profile) }
    }

    fun updateAudioMixer(config: AudioMixerConfig) {
        viewModelScope.launch { settingsRepo.updateAudioMixer(config) }
    }

    fun updateTransportPreference(pref: TransportPreference) {
        viewModelScope.launch { settingsRepo.updateTransportPreference(pref) }
    }

    fun updateManualPeerIp(ip: String) {
        viewModelScope.launch { settingsRepo.updateManualPeerIp(ip) }
    }

    fun addQuickCueShortcut(label: String) {
        val clean = label.trim()
        if (clean.isEmpty()) return
        val current = uiState.value.savedSettings.quickCues
        if (current.any { it.equals(clean, ignoreCase = true) }) return
        viewModelScope.launch {
            settingsRepo.updateQuickCues((current + clean).take(12))
        }
    }

    fun removeQuickCueShortcut(label: String) {
        val current = uiState.value.savedSettings.quickCues
        viewModelScope.launch {
            settingsRepo.updateQuickCues(current.filterNot { it == label })
        }
    }

    fun setShowAccessibilityDialog(show: Boolean) {
        _showAccessibilityDialog.value = show
    }

    fun requestUsbPermissionIfNeeded() {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return
        val probe = _usbProbe.value
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = Intent("com.example.USB_PERMISSION").apply {
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(context, 100, permissionIntent, flags)
        probe.attachedAccessory?.let { acc ->
            if (!usbManager.hasPermission(acc)) {
                usbManager.requestPermission(acc, pi)
                return
            }
        }
        usbManager.deviceList.values.firstOrNull()?.let { dev ->
            if (!usbManager.hasPermission(dev)) {
                usbManager.requestPermission(dev, pi)
            }
        }
    }

    fun clearLogs() {
        sessionBus.clearLogs()
    }
}
