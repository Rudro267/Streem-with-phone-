package com.example.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.CueUrgency
import com.example.model.InteractionMode
import com.example.model.LinkStatus
import com.example.model.RemoteTouchAction
import com.example.model.TelestratorPoint
import com.example.ui.StudioUiState
import com.example.ui.components.SegmentedVuMeter
import com.example.ui.components.TelestratorOverlayCanvas
import com.example.ui.components.TelemetryPill
import com.example.ui.theme.LinkEmerald
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectorMonitorScreen(
    uiState: StudioUiState,
    onAttachSurface: (Surface?) -> Unit,
    onInteractionModeChange: (InteractionMode) -> Unit,
    onBrushColorChange: (Long) -> Unit,
    onRemoteTouch: (RemoteTouchAction, Float, Float) -> Unit,
    onUpsertStroke: (Long, List<TelestratorPoint>) -> Unit,
    onClearTelestrator: () -> Unit,
    onSendCue: (String, CueUrgency) -> Unit,
    onToggleAudioMute: (Boolean) -> Unit,
    onRequestKeyframe: () -> Unit,
    onStopSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onStopSession()
    }

    var showCueDrawer by remember { mutableStateOf(false) }
    var customCueText by remember { mutableStateOf("") }

    val videoAspect = remember(uiState.telemetry.videoWidth, uiState.telemetry.videoHeight) {
        val w = uiState.telemetry.videoWidth.coerceAtLeast(1)
        val h = uiState.telemetry.videoHeight.coerceAtLeast(1)
        w.toFloat() / h.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioObsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Studio Monitor Strip
        Surface(
            color = RackSlate,
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onStopSession,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("director_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Exit Director Deck", tint = TallyCrimson)
                    }
                    Column {
                        Text(
                            text = if (uiState.linkStatus == LinkStatus.CONNECTED) "LIVE DIRECTOR DECK" else uiState.linkStatus.label.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (uiState.linkStatus == LinkStatus.CONNECTED) SignalCyan else PeakAmber
                        )
                        Text(
                            text = "${uiState.telemetry.videoWidth}x${uiState.telemetry.videoHeight} • ${uiState.telemetry.bitrateKbps} kbps",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TelemetryPill(
                        label = "${uiState.telemetry.actualFps}FPS",
                        accentColor = LinkEmerald
                    )
                    TelemetryPill(
                        label = "${uiState.telemetry.rttMs}ms",
                        accentColor = SignalCyan
                    )
                    IconButton(
                        onClick = { onToggleAudioMute(!uiState.isDirectorAudioMuted) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("director_audio_mute_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isDirectorAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Toggle audio monitor",
                            tint = if (uiState.isDirectorAudioMuted) PeakAmber else LinkEmerald
                        )
                    }
                }
            }
        }

        // Main Video Viewport + Interactive Touch/Telestrator Layer
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val containerRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 9f / 16f
            val viewportModifier = if (containerRatio > videoAspect) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(videoAspect)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspect)
            }

            Box(
                modifier = viewportModifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                var activeSurface by remember { mutableStateOf<Surface?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        onAttachSurface(null)
                        activeSurface?.release()
                        activeSurface = null
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val surface = Surface(st)
                                    activeSurface = surface
                                    onAttachSurface(surface)
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    onAttachSurface(null)
                                    activeSurface?.release()
                                    activeSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Interactive Overlay Surface (Touch Injection or Telestrator)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.interactionMode, uiState.selectedBrushColorArgb) {
                            if (uiState.interactionMode == InteractionMode.VIEW_ONLY) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val startX = (down.position.x / w).coerceIn(0f, 1f)
                                val startY = (down.position.y / h).coerceIn(0f, 1f)

                                if (uiState.interactionMode == InteractionMode.TOUCH_CONTROL) {
                                    onRemoteTouch(RemoteTouchAction.DOWN, startX, startY)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val nx = (change.position.x / w).coerceIn(0f, 1f)
                                        val ny = (change.position.y / h).coerceIn(0f, 1f)
                                        if (!change.pressed) {
                                            onRemoteTouch(RemoteTouchAction.UP, nx, ny)
                                            break
                                        } else if (change.position != change.previousPosition) {
                                            onRemoteTouch(RemoteTouchAction.MOVE, nx, ny)
                                        }
                                    }
                                } else if (uiState.interactionMode == InteractionMode.TELESTRATOR_DRAW) {
                                    val strokeId = System.currentTimeMillis()
                                    val points = mutableListOf(TelestratorPoint(startX, startY))
                                    onUpsertStroke(strokeId, points.toList())
                                    var counter = 0
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val nx = (change.position.x / w).coerceIn(0f, 1f)
                                        val ny = (change.position.y / h).coerceIn(0f, 1f)
                                        points.add(TelestratorPoint(nx, ny))
                                        counter++
                                        if (!change.pressed) {
                                            onUpsertStroke(strokeId, points.toList())
                                            break
                                        } else if (counter % 3 == 0) {
                                            onUpsertStroke(strokeId, points.toList())
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    TelestratorOverlayCanvas(
                        strokes = uiState.strokes,
                        touchPulse = uiState.touchPulse,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Standby / Connecting Overlay
                if (uiState.linkStatus != LinkStatus.CONNECTED) {
                    Surface(
                        color = StudioObsidian.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, SignalCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Waiting for Host USB Stream",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = "Start Broadcast Host on the connected phone via USB-C (${uiState.savedSettings.transportPreference.label}).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }

                // Host Remote Lock Banner
                if (uiState.interactionMode == InteractionMode.TOUCH_CONTROL && !uiState.peerRemoteControlEnabled) {
                    Surface(
                        color = PeakAmber.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = StudioObsidian, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Host Paused Remote Touch", style = MaterialTheme.typography.labelSmall, color = StudioObsidian)
                        }
                    }
                }
            }
        }

        // Collapsible Streamer Cue Drawer
        AnimatedVisibility(visible = showCueDrawer) {
            Surface(
                color = RackSlate,
                border = BorderStroke(1.dp, RackBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("SEND STREAMER CUE TO HOST", style = MaterialTheme.typography.labelSmall, color = PeakAmber)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.savedSettings.quickCues.forEach { cueLabel ->
                            AssistChip(
                                onClick = { onSendCue(cueLabel, CueUrgency.ACTION) },
                                label = { Text(cueLabel, color = StudioTextPrimary) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = RackSlateElevated),
                                border = BorderStroke(1.dp, PeakAmber.copy(alpha = 0.45f)),
                                modifier = Modifier.testTag("director_quick_cue_${cueLabel.hashCode()}")
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customCueText,
                            onValueChange = { customCueText = it },
                            placeholder = { Text("Custom cue note...") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SignalCyan,
                                unfocusedBorderColor = RackBorder,
                                focusedContainerColor = StudioObsidian,
                                unfocusedContainerColor = StudioObsidian
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("director_custom_cue_input")
                        )
                        IconButton(
                            onClick = {
                                if (customCueText.isNotBlank()) {
                                    onSendCue(customCueText, CueUrgency.ALERT)
                                    customCueText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(TallyCrimson)
                                .testTag("director_send_custom_cue_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send cue", tint = StudioTextPrimary)
                        }
                    }
                }
            }
        }

        // Bottom Control Dock: Mode Switcher + Telestrator Brushes + Nav Keys + Audio VU
        Surface(
            color = RackSlate,
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SegmentedVuMeter(
                    level = uiState.audioLevels.masterRms,
                    isMuted = uiState.isDirectorAudioMuted,
                    isClipping = uiState.audioLevels.isClipping,
                    segments = 24
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Interaction Modes
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InteractionModeChip(
                            selected = uiState.interactionMode == InteractionMode.TOUCH_CONTROL,
                            label = "Touch",
                            icon = Icons.Filled.TouchApp,
                            onClick = { onInteractionModeChange(InteractionMode.TOUCH_CONTROL) },
                            tag = "mode_touch_chip"
                        )
                        InteractionModeChip(
                            selected = uiState.interactionMode == InteractionMode.TELESTRATOR_DRAW,
                            label = "Draw",
                            icon = Icons.Filled.Brush,
                            onClick = { onInteractionModeChange(InteractionMode.TELESTRATOR_DRAW) },
                            tag = "mode_draw_chip"
                        )
                        InteractionModeChip(
                            selected = uiState.interactionMode == InteractionMode.VIEW_ONLY,
                            label = "View",
                            icon = Icons.Filled.Visibility,
                            onClick = { onInteractionModeChange(InteractionMode.VIEW_ONLY) },
                            tag = "mode_view_chip"
                        )
                    }

                    // Cue Drawer Toggle
                    FilterChip(
                        selected = showCueDrawer,
                        onClick = { showCueDrawer = !showCueDrawer },
                        label = { Text("Cues") },
                        leadingIcon = { Icon(Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TallyCrimsonContainer,
                            selectedLabelColor = TallyCrimson,
                            selectedLeadingIconColor = TallyCrimson
                        ),
                        modifier = Modifier.testTag("toggle_cue_drawer_chip")
                    )
                }

                // Contextual sub-bar (Telestrator colors OR Remote System Nav buttons)
                if (uiState.interactionMode == InteractionMode.TELESTRATOR_DRAW) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(0xFFFF2D46L, 0xFF38BDF8L, 0xFFF59E0BL, 0xFF10B981L).forEach { argb ->
                                val selected = uiState.selectedBrushColorArgb == argb
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .clickable { onBrushColorChange(argb) }
                                        .padding(3.dp)
                                        .testTag("brush_color_${argb.toString(16)}")
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(Color.Transparent)
                                        )
                                    }
                                }
                            }
                        }
                        AssistChip(
                            onClick = onClearTelestrator,
                            label = { Text("Clear Drawing") },
                            leadingIcon = { Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("director_clear_drawing_chip")
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onRemoteTouch(RemoteTouchAction.GLOBAL_BACK, 0f, 0f) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("remote_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Remote Back", tint = StudioTextPrimary)
                        }
                        IconButton(
                            onClick = { onRemoteTouch(RemoteTouchAction.GLOBAL_HOME, 0f, 0f) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("remote_home_button")
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = "Remote Home", tint = StudioTextPrimary)
                        }
                        IconButton(
                            onClick = { onRemoteTouch(RemoteTouchAction.GLOBAL_RECENTS, 0f, 0f) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("remote_recents_button")
                        ) {
                            Icon(Icons.Filled.ViewAgenda, contentDescription = "Remote Recents", tint = StudioTextPrimary)
                        }
                        IconButton(
                            onClick = onRequestKeyframe,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("request_keyframe_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync keyframe", tint = SignalCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionModeChip(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tag: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = SignalCyanContainer,
            selectedLabelColor = SignalCyan,
            selectedLeadingIconColor = SignalCyan,
            containerColor = StudioObsidian,
            labelColor = StudioTextSecondary
        ),
        modifier = Modifier.testTag(tag)
    )
}
