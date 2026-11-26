package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AudioMixerConfig
import com.example.model.StreamQualityProfile
import com.example.model.VideoResolution
import com.example.ui.StudioUiState
import com.example.ui.components.SegmentedVuMeter
import com.example.ui.theme.LinkEmerald
import com.example.ui.theme.LinkEmeraldContainer
import com.example.ui.theme.PeakAmber
import com.example.ui.theme.RackBorder
import com.example.ui.theme.RackSlate
import com.example.ui.theme.RackSlateElevated
import com.example.ui.theme.SignalCyan
import com.example.ui.theme.SignalCyanContainer
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.TallyCrimson
import com.example.ui.theme.TallyCrimsonContainer
import com.example.ui.theme.TelemetryTextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioQualityScreen(
    uiState: StudioUiState,
    onUpdateAudioMixer: (AudioMixerConfig) -> Unit,
    onUpdateQualityProfile: (StreamQualityProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val mixer = uiState.savedSettings.audioMixer
    val quality = uiState.savedSettings.qualityProfile
    val levels = uiState.audioLevels

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dual-Source Broadcast Audio Mixer
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = TallyCrimson)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("USB Audio Mixer", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
                            Text("48 kHz PCM • Internal App + Mic", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                        }
                    }
                    if (levels.isClipping) {
                        Surface(
                            color = TallyCrimsonContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TallyCrimson)
                        ) {
                            Text(
                                text = "CLIP",
                                style = MaterialTheme.typography.labelSmall,
                                color = TallyCrimson,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Channel 1: Internal Game / App Audio
                MixerChannelControl(
                    title = "Internal App / Game Audio",
                    gain = mixer.internalGain,
                    isMuted = mixer.isInternalMuted,
                    rmsLevel = levels.internalRms,
                    activeIcon = Icons.AutoMirrored.Filled.VolumeUp,
                    mutedIcon = Icons.AutoMirrored.Filled.VolumeOff,
                    accentColor = SignalCyan,
                    onGainChange = { onUpdateAudioMixer(mixer.copy(internalGain = it)) },
                    onToggleMute = { onUpdateAudioMixer(mixer.copy(isInternalMuted = !mixer.isInternalMuted)) },
                    sliderTag = "internal_audio_gain_slider",
                    muteTag = "internal_audio_mute_button"
                )

                // Channel 2: Microphone Commentary
                MixerChannelControl(
                    title = "Microphone Commentary",
                    gain = mixer.micGain,
                    isMuted = mixer.isMicMuted,
                    rmsLevel = levels.micRms,
                    activeIcon = Icons.Filled.Mic,
                    mutedIcon = Icons.Filled.MicOff,
                    accentColor = TallyCrimson,
                    onGainChange = { onUpdateAudioMixer(mixer.copy(micGain = it)) },
                    onToggleMute = { onUpdateAudioMixer(mixer.copy(isMicMuted = !mixer.isMicMuted)) },
                    sliderTag = "mic_gain_slider",
                    muteTag = "mic_mute_button"
                )

                // Master Bus Meter
                Surface(
                    color = StudioObsidian,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RackBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("MASTER USB OUTPUT BUS", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                        SegmentedVuMeter(
                            level = levels.masterRms,
                            isMuted = mixer.isInternalMuted && mixer.isMicMuted,
                            isClipping = levels.isClipping
                        )
                    }
                }
            }
        }

        // Video Encoder Quality & Low Latency Card
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.HighQuality, contentDescription = null, tint = SignalCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Low-Latency Video Encoder", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
                        Text("Hardware Surface H.264 / AVC", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    }
                }

                // Resolution
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CAPTURE RESOLUTION", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoResolution.entries.forEach { res ->
                            val selected = quality.resolution == res
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateQualityProfile(quality.copy(resolution = res)) },
                                label = { Text("${res.label} (${res.width}×${res.height})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SignalCyanContainer,
                                    selectedLabelColor = SignalCyan
                                ),
                                modifier = Modifier.testTag("res_chip_${res.label}")
                            )
                        }
                    }
                }

                // Target FPS
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TARGET FRAME RATE", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60).forEach { fps ->
                            val selected = quality.targetFps == fps
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateQualityProfile(quality.copy(targetFps = fps)) },
                                label = { Text("$fps FPS") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TallyCrimsonContainer,
                                    selectedLabelColor = TallyCrimson
                                ),
                                modifier = Modifier.testTag("fps_chip_$fps")
                            )
                        }
                    }
                }

                // Bitrate Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("STREAM BITRATE", style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                        Text(
                            text = "${quality.bitrateMbps} Mbps",
                            style = TelemetryTextStyle,
                            color = LinkEmerald
                        )
                    }
                    Slider(
                        value = quality.bitrateMbps.toFloat(),
                        onValueChange = { onUpdateQualityProfile(quality.copy(bitrateMbps = it.roundToInt())) },
                        valueRange = 4f..20f,
                        steps = 15,
                        colors = SliderDefaults.colors(
                            thumbColor = TallyCrimson,
                            activeTrackColor = TallyCrimson,
                            inactiveTrackColor = RackSlateElevated
                        ),
                        modifier = Modifier.testTag("video_bitrate_slider")
                    )
                }

                // Low-Latency Codec Switch
                Surface(
                    color = StudioObsidian,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RackBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Speed, contentDescription = null, tint = LinkEmerald)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Zero-B-Frame Low Latency", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                                Text(
                                    text = "Enables Android 12+ hardware low-latency encoder/decoder flags",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StudioTextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = quality.lowLatencyEnabled,
                            onCheckedChange = { onUpdateQualityProfile(quality.copy(lowLatencyEnabled = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = LinkEmerald, checkedTrackColor = LinkEmeraldContainer),
                            modifier = Modifier.testTag("low_latency_codec_switch")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MixerChannelControl(
    title: String,
    gain: Float,
    isMuted: Boolean,
    rmsLevel: Float,
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    mutedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    sliderTag: String,
    muteTag: String
) {
    Surface(
        color = StudioObsidian,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, RackBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    Text(
                        text = if (isMuted) "MUTED" else "${(gain * 100).roundToInt()}% Gain",
                        style = TelemetryTextStyle,
                        color = if (isMuted) PeakAmber else accentColor
                    )
                }
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(muteTag)
                ) {
                    Icon(
                        imageVector = if (isMuted) mutedIcon else activeIcon,
                        contentDescription = "Mute $title",
                        tint = if (isMuted) PeakAmber else accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            SegmentedVuMeter(level = rmsLevel, isMuted = isMuted)

            Slider(
                value = gain,
                onValueChange = onGainChange,
                valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = RackSlateElevated
                ),
                modifier = Modifier.testTag(sliderTag)
            )
        }
    }
}
