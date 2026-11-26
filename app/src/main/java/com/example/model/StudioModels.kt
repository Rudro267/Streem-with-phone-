package com.example.model

import android.hardware.usb.UsbAccessory

enum class DeviceRole {
    IDLE,
    HOST,
    DIRECTOR
}

enum class TransportPreference(val label: String) {
    AUTO("Auto (USB Socket + AOA)"),
    USB_TETHER_SOCKET("USB Tethering Socket"),
    USB_ACCESSORY("USB Accessory (AOA)")
}

enum class ActiveTransportKind(val label: String) {
    NONE("No USB Link"),
    USB_TETHER_SOCKET("USB Tether Socket"),
    USB_ACCESSORY("USB Accessory AOA")
}

enum class LinkStatus(val label: String) {
    DISCONNECTED("Standby"),
    PROBING("Probing USB"),
    HANDSHAKING("Handshaking"),
    CONNECTED("Linked"),
    RECONNECTING("Reconnecting"),
    ERROR("Link Error")
}

enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    HD_720P(1280, 720, "720p"),
    FHD_1080P(1920, 1080, "1080p")
}

enum class InteractionMode(val label: String) {
    TOUCH_CONTROL("Touch"),
    TELESTRATOR_DRAW("Draw"),
    VIEW_ONLY("View")
}

enum class CueUrgency(val label: String) {
    INFO("Cue"),
    ACTION("Action"),
    ALERT("Alert")
}

data class StreamQualityProfile(
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val targetFps: Int = 60,
    val bitrateMbps: Int = 12,
    val lowLatencyEnabled: Boolean = true
)

data class AudioMixerConfig(
    val internalGain: Float = 0.85f,
    val micGain: Float = 1.0f,
    val isInternalMuted: Boolean = false,
    val isMicMuted: Boolean = false,
    val sampleRate: Int = 48000
)

data class AudioLevels(
    val internalRms: Float = 0f,
    val micRms: Float = 0f,
    val masterRms: Float = 0f,
    val isClipping: Boolean = false
)

data class TelemetrySnapshot(
    val actualFps: Int = 0,
    val bitrateKbps: Int = 0,
    val rttMs: Int = 0,
    val encodeLatencyMs: Int = 0,
    val droppedFrames: Int = 0,
    val activeTransport: ActiveTransportKind = ActiveTransportKind.NONE,
    val peerAddress: String = "--",
    val videoWidth: Int = 1080,
    val videoHeight: Int = 1920
)

enum class RemoteTouchAction(val code: Byte) {
    DOWN(0),
    MOVE(1),
    UP(2),
    CANCEL(3),
    GLOBAL_BACK(4),
    GLOBAL_HOME(5),
    GLOBAL_RECENTS(6);

    companion object {
        fun fromCode(code: Byte): RemoteTouchAction =
            entries.firstOrNull { it.code == code } ?: CANCEL
    }
}

data class RemoteTouchEvent(
    val action: RemoteTouchAction,
    val normX: Float,
    val normY: Float,
    val pointerId: Int = 0
)

data class TelestratorPoint(
    val normX: Float,
    val normY: Float
)

data class TelestratorStroke(
    val id: Long,
    val colorArgb: Long,
    val widthNorm: Float,
    val points: List<TelestratorPoint>,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class StreamerCue(
    val id: Long,
    val text: String,
    val urgency: CueUrgency,
    val senderRole: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val acknowledged: Boolean = false
)

data class UsbProbeResult(
    val hasTetherInterface: Boolean = false,
    val interfaceName: String? = null,
    val localIp: String? = null,
    val candidatePeerIps: List<String> = emptyList(),
    val attachedAccessory: UsbAccessory? = null,
    val attachedUsbDevicesCount: Int = 0
)

data class DiagnosticLogEntry(
    val id: Long,
    val timestampMs: Long,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)
