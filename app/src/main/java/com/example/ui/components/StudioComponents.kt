package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DeviceRole
import com.example.model.LinkStatus
import com.example.model.TelestratorStroke
import com.example.ui.theme.LinkEmerald
import com.example.ui.theme.PeakAmber
import com.example.ui.theme.RackBorder
import com.example.ui.theme.RackSlate
import com.example.ui.theme.RackSlateElevated
import com.example.ui.theme.SignalCyan
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.TallyCrimson
import com.example.ui.theme.TallyCrimsonContainer
import com.example.ui.theme.TelemetryTextStyle

@Composable
fun TallyHeaderBar(
    role: DeviceRole,
    linkStatus: LinkStatus,
    rttMs: Int,
    fps: Int,
    modifier: Modifier = Modifier
) {
    val isLive = role != DeviceRole.IDLE
    val badgeColor = when {
        role == DeviceRole.HOST && linkStatus == LinkStatus.CONNECTED -> TallyCrimson
        role == DeviceRole.HOST -> PeakAmber
        role == DeviceRole.DIRECTOR && linkStatus == LinkStatus.CONNECTED -> SignalCyan
        linkStatus == LinkStatus.CONNECTED -> LinkEmerald
        else -> StudioTextMuted
    }

    val tallyText = when (role) {
        DeviceRole.HOST -> if (linkStatus == LinkStatus.CONNECTED) "ON AIR • HOST" else "HOST • ${linkStatus.label.uppercase()}"
        DeviceRole.DIRECTOR -> if (linkStatus == LinkStatus.CONNECTED) "DIRECTOR • LIVE" else "DIRECTOR • ${linkStatus.label.uppercase()}"
        DeviceRole.IDLE -> "STUDIO STANDBY"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RackSlate,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isLive) badgeColor.copy(alpha = 0.55f) else RackBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "TALLYLINK STUDIO",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = tallyText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryPill(
                    label = "${fps}FPS",
                    accentColor = if (fps >= 45) LinkEmerald else StudioTextSecondary
                )
                TelemetryPill(
                    label = if (linkStatus == LinkStatus.CONNECTED) "${rttMs}ms" else "--ms",
                    accentColor = when {
                        linkStatus != LinkStatus.CONNECTED -> StudioTextMuted
                        rttMs <= 25 -> LinkEmerald
                        rttMs <= 60 -> PeakAmber
                        else -> TallyCrimson
                    }
                )
            }
        }
    }
}

@Composable
fun TelemetryPill(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = StudioObsidian,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, RackBorder)
    ) {
        Text(
            text = label,
            style = TelemetryTextStyle,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun SegmentedVuMeter(
    level: Float,
    isMuted: Boolean,
    isClipping: Boolean = false,
    segments: Int = 20,
    modifier: Modifier = Modifier
) {
    val activeSegments = if (isMuted) 0 else (level.coerceIn(0f, 1f) * segments).toInt()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
    ) {
        val gap = 4.dp.toPx()
        val segWidth = ((size.width - gap * (segments - 1)) / segments).coerceAtLeast(2f)
        for (i in 0 until segments) {
            val fraction = (i + 1).toFloat() / segments
            val onColor = when {
                fraction > 0.85f || (isClipping && i == segments - 1) -> TallyCrimson
                fraction > 0.65f -> PeakAmber
                else -> LinkEmerald
            }
            val color = if (i < activeSegments) onColor else RackSlateElevated
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (segWidth + gap), 0f),
                size = Size(segWidth, size.height),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
        }
    }
}

@Composable
fun TelestratorOverlayCanvas(
    strokes: List<TelestratorStroke>,
    touchPulse: Pair<Float, Float>?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val color = Color(stroke.colorArgb)
            val strokePx = (stroke.widthNorm * minOf(w, h)).coerceAtLeast(4f)
            if (stroke.points.size == 1) {
                val pt = stroke.points.first()
                drawCircle(
                    color = color,
                    radius = strokePx * 0.7f,
                    center = Offset(pt.normX * w, pt.normY * h)
                )
            } else {
                val path = Path().apply {
                    val first = stroke.points.first()
                    moveTo(first.normX * w, first.normY * h)
                    for (i in 1 until stroke.points.size) {
                        val p = stroke.points[i]
                        lineTo(p.normX * w, p.normY * h)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        if (touchPulse != null) {
            val cx = touchPulse.first * w
            val cy = touchPulse.second * h
            drawCircle(
                color = SignalCyan.copy(alpha = 0.25f),
                radius = 22.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = SignalCyan,
                radius = 12.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}

@Composable
fun AccessibilityDisclosureDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RackSlate,
        titleContentColor = StudioTextPrimary,
        textContentColor = StudioTextSecondary,
        icon = {
            Icon(
                imageVector = Icons.Filled.TouchApp,
                contentDescription = "Remote touch permission",
                tint = SignalCyan,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Enable Remote Touch Control",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "To let the USB-connected Director phone tap, swipe, and navigate apps on this device, Android requires enabling TallyLink Remote Touch in Accessibility Settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = TallyCrimsonContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TallyCrimson.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "Privacy guarantee: TallyLink never reads screen text, passwords, or notifications—it only injects touch gestures during an active USB broadcast, and you can pause it anytime with the Remote Lock switch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = TallyCrimson),
                modifier = Modifier.testTag("confirm_open_accessibility_button")
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_accessibility_dialog_button")
            ) {
                Text("Not Now", color = StudioTextSecondary)
            }
        }
    )
}
