package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ActiveTransportKind
import com.example.model.DeviceRole
import com.example.model.TransportPreference
import com.example.ui.StudioUiState
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioLinkScreen(
    uiState: StudioUiState,
    onStartHost: () -> Unit,
    onStartDirector: () -> Unit,
    onStopSession: () -> Unit,
    onRefreshProbe: () -> Unit,
    onOpenUsbTetherSettings: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenAccessibilityDisclosure: () -> Unit,
    onToggleRemoteLock: (Boolean) -> Unit,
    onClearTelestrator: () -> Unit,
    onAcknowledgeCue: (Long) -> Unit,
    onTransportPrefChange: (TransportPreference) -> Unit,
    onManualIpChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var ipInput by remember(uiState.savedSettings.manualPeerIp) {
        mutableStateOf(uiState.savedSettings.manualPeerIp)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.role == DeviceRole.HOST) {
            UltraLightHostTallyCard(
                uiState = uiState,
                onStopSession = onStopSession,
                onToggleRemoteLock = onToggleRemoteLock,
                onClearTelestrator = onClearTelestrator,
                onAcknowledgeCue = onAcknowledgeCue
            )
        } else {
            // USB Hardware Probe & Transport Card
            UsbLinkProbeCard(
                uiState = uiState,
                ipInput = ipInput,
                onIpInputChange = {
                    ipInput = it
                    onManualIpChange(it)
                },
                onRefreshProbe = onRefreshProbe,
                onOpenUsbTetherSettings = onOpenUsbTetherSettings,
                onRequestUsbPermission = onRequestUsbPermission,
                onTransportPrefChange = onTransportPrefChange
            )

            // Preflight Readiness Strip
            PreflightReadinessCard(
                hasMic = uiState.hasMicPermission,
                isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                onRequestMic = onRequestMicPermission,
                onEnableAccessibility = onOpenAccessibilityDisclosure
            )

            // Primary Role Action Cards
            RoleLaunchDeck(
                onStartHost = onStartHost,
                onStartDirector = onStartDirector
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsbLinkProbeCard(
    uiState: StudioUiState,
    ipInput: String,
    onIpInputChange: (String) -> Unit,
    onRefreshProbe: () -> Unit,
    onOpenUsbTetherSettings: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onTransportPrefChange: (TransportPreference) -> Unit
) {
    val probe = uiState.usbProbe
    val linkReady = probe.hasTetherInterface || probe.attachedAccessory != null || probe.attachedUsbDevicesCount > 0

    Surface(
        color = RackSlate,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (linkReady) LinkEmerald.copy(alpha = 0.5f) else RackBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (linkReady) LinkEmeraldContainer else RackSlateElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = "USB cable status",
                            tint = if (linkReady) LinkEmerald else StudioTextMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "USB Bridge Transport",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary
                        )
                        val subtitle = when {
                            uiState.activeTransport != ActiveTransportKind.NONE ->
                                "${uiState.activeTransport.label} • ${uiState.peerAddress}"
                            probe.hasTetherInterface ->
                                "Tether ${probe.interfaceName} (${probe.localIp})"
                            probe.attachedAccessory != null ->
                                "USB Accessory Ready (${probe.attachedAccessory.model})"
                            probe.attachedUsbDevicesCount > 0 ->
                                "${probe.attachedUsbDevicesCount} USB Host Device(s) Attached"
                            else -> "Connect USB-C cable & enable USB Tethering or Accessory"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (linkReady) LinkEmerald else StudioTextSecondary
                        )
                    }
                }

                TextButton(
                    onClick = onRefreshProbe,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_usb_probe_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh USB status",
                        tint = SignalCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TransportPreference.entries.forEach { pref ->
                    val selected = uiState.savedSettings.transportPreference == pref
                    FilterChip(
                        selected = selected,
                        onClick = { onTransportPrefChange(pref) },
                        label = { Text(pref.label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalCyanContainer,
                            selectedLabelColor = SignalCyan,
                            containerColor = StudioObsidian,
                            labelColor = StudioTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = RackBorder,
                            selectedBorderColor = SignalCyan
                        ),
                        modifier = Modifier.testTag("transport_chip_${pref.name.lowercase()}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenUsbTetherSettings,
                    border = BorderStroke(1.dp, RackBorder),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("open_usb_tether_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cable,
                        contentDescription = "USB tethering settings",
                        tint = SignalCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("USB Tether Settings", color = StudioTextPrimary, maxLines = 1)
                }

                if (probe.attachedAccessory != null || probe.attachedUsbDevicesCount > 0) {
                    OutlinedButton(
                        onClick = onRequestUsbPermission,
                        border = BorderStroke(1.dp, PeakAmber.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("grant_usb_aoa_button")
                    ) {
                        Text("Grant USB", color = PeakAmber)
                    }
                }
            }

            OutlinedTextField(
                value = ipInput,
                onValueChange = onIpInputChange,
                label = { Text("Optional Peer USB IP Override (Auto-discovered by default)") },
                placeholder = { Text("e.g. 192.168.42.129") },
                singleLine = true,
                textStyle = TelemetryTextStyle.copy(color = StudioTextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SignalCyan,
                    unfocusedBorderColor = RackBorder,
                    focusedContainerColor = StudioObsidian,
                    unfocusedContainerColor = StudioObsidian,
                    focusedLabelColor = SignalCyan,
                    unfocusedLabelColor = StudioTextMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_peer_ip_input")
            )
        }
    }
}

@Composable
private fun PreflightReadinessCard(
    hasMic: Boolean,
    isAccessibilityEnabled: Boolean,
    onRequestMic: () -> Unit,
    onEnableAccessibility: () -> Unit
) {
    Surface(
        color = RackSlate,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, RackBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "STUDIO PREFLIGHT CHECK",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PreflightBadgeButton(
                    title = "Mic Mixer",
                    ready = hasMic,
                    readyLabel = "Ready",
                    pendingLabel = "Grant Mic",
                    icon = Icons.Filled.Mic,
                    onClick = onRequestMic,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("preflight_mic_button")
                )
                PreflightBadgeButton(
                    title = "Remote Touch",
                    ready = isAccessibilityEnabled,
                    readyLabel = "Enabled",
                    pendingLabel = "Enable A11y",
                    icon = Icons.Filled.TouchApp,
                    onClick = onEnableAccessibility,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("preflight_accessibility_button")
                )
            }
        }
    }
}

@Composable
private fun PreflightBadgeButton(
    title: String,
    ready: Boolean,
    readyLabel: String,
    pendingLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (ready) LinkEmeraldContainer.copy(alpha = 0.55f) else PeakAmber.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (ready) LinkEmerald.copy(alpha = 0.5f) else PeakAmber.copy(alpha = 0.5f)),
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (ready) Icons.Filled.CheckCircle else icon,
                contentDescription = title,
                tint = if (ready) LinkEmerald else PeakAmber,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = StudioTextPrimary)
                Text(
                    text = if (ready) readyLabel else pendingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ready) LinkEmerald else PeakAmber
                )
            }
        }
    }
}

@Composable
private fun RoleLaunchDeck(
    onStartHost: () -> Unit,
    onStartDirector: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Host Broadcast Source Card (flat zero-overdraw container)
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, TallyCrimson.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.ScreenShare,
                        contentDescription = "Broadcast host icon",
                        tint = TallyCrimson,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Broadcast Host (Game / Camera Phone)",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Streams screen + internal & mic audio over USB and accepts remote touch & cues.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextSecondary
                        )
                    }
                }

                Button(
                    onClick = onStartHost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TallyCrimson,
                        contentColor = StudioTextPrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_host_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GO LIVE AS HOST", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Director Deck Card
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, SignalCyan.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.TabletAndroid,
                        contentDescription = "Director deck icon",
                        tint = SignalCyan,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Director Deck (Monitor & Remote Phone)",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Receives low-latency USB video + audio, controls Host touch, draws overlays, and sends cues.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextSecondary
                        )
                    }
                }

                Button(
                    onClick = onStartDirector,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalCyan,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_director_button")
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN DIRECTOR DECK", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun UltraLightHostTallyCard(
    uiState: StudioUiState,
    onStopSession: () -> Unit,
    onToggleRemoteLock: (Boolean) -> Unit,
    onClearTelestrator: () -> Unit,
    onAcknowledgeCue: (Long) -> Unit
) {
    val tel = uiState.telemetry
    Surface(
        color = RackSlate,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, TallyCrimson),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ON AIR • LITE HOST", style = MaterialTheme.typography.titleMedium, color = TallyCrimson)
                    Text(
                        text = "${tel.videoWidth}x${tel.videoHeight} • ${tel.bitrateKbps} kbps • ${uiState.activeTransport.label}",
                        style = TelemetryTextStyle,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "Switch to your game now—UI telemetry pauses automatically to free RAM.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextMuted
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onStopSession,
                    colors = ButtonDefaults.buttonColors(containerColor = TallyCrimson),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("stop_host_session_button")
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop broadcast", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("STOP")
                }
            }

            // Compact Remote Touch Lock Row
            Surface(
                color = StudioObsidian,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, RackBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (uiState.isRemoteControlEnabled) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = "Remote touch lock status",
                            tint = if (uiState.isRemoteControlEnabled) LinkEmerald else PeakAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isRemoteControlEnabled) "Remote Touch Active" else "Remote Touch Paused",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextPrimary
                        )
                    }
                    Switch(
                        checked = uiState.isRemoteControlEnabled,
                        onCheckedChange = onToggleRemoteLock,
                        colors = SwitchDefaults.colors(checkedThumbColor = LinkEmerald, checkedTrackColor = LinkEmeraldContainer),
                        modifier = Modifier.testTag("host_remote_lock_switch")
                    )
                }
            }

            // Compact Telestrator Stroke Counter + Clear (no Canvas mirror on Host)
            Surface(
                color = StudioObsidian,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, RackBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Brush, contentDescription = null, tint = SignalCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Telestrator: ${uiState.strokes.size} strokes",
                            style = TelemetryTextStyle,
                            color = SignalCyan
                        )
                    }
                    TextButton(
                        onClick = onClearTelestrator,
                        modifier = Modifier.testTag("host_clear_telestrator_button")
                    ) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "Clear annotations", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", color = StudioTextSecondary)
                    }
                }
            }

            // Compact Incoming Director Cue Strip
            val latestCue = uiState.cues.firstOrNull()
            if (latestCue != null) {
                Surface(
                    color = TallyCrimsonContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TallyCrimson.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Campaign, contentDescription = "Streamer cue", tint = PeakAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${latestCue.senderRole}: ${latestCue.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextPrimary
                            )
                        }
                        if (!latestCue.acknowledged) {
                            TextButton(
                                onClick = { onAcknowledgeCue(latestCue.id) },
                                modifier = Modifier.testTag("ack_latest_cue_button")
                            ) {
                                Text("ACK", color = LinkEmerald, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text("ACKED", style = MaterialTheme.typography.labelSmall, color = LinkEmerald)
                        }
                    }
                }
            }
        }
    }
}
