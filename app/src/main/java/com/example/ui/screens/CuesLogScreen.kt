package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.example.model.CueUrgency
import com.example.ui.StudioUiState
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
import com.example.ui.theme.TelemetryTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CuesLogScreen(
    uiState: StudioUiState,
    onSendCue: (String, CueUrgency) -> Unit,
    onAcknowledgeCue: (Long) -> Unit,
    onAddQuickCue: (String) -> Unit,
    onRemoveQuickCue: (String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cueInput by remember { mutableStateOf("") }
    var selectedUrgency by remember { mutableStateOf(CueUrgency.ACTION) }
    var newShortcutInput by remember { mutableStateOf("") }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Streamer Cue Pad Card
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Campaign, contentDescription = null, tint = PeakAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Streamer Collaboration Cues", style = MaterialTheme.typography.titleLarge, color = StudioTextPrimary)
                }

                // Quick Cue Tap Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.savedSettings.quickCues.forEach { preset ->
                        InputChip(
                            selected = false,
                            onClick = { onSendCue(preset, selectedUrgency) },
                            label = { Text(preset) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove $preset",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemoveQuickCue(preset) }
                                        .testTag("remove_cue_chip_${preset.hashCode()}")
                                )
                            },
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = StudioObsidian,
                                labelColor = StudioTextPrimary,
                                trailingIconColor = StudioTextMuted
                            ),
                            border = InputChipDefaults.inputChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = RackBorder
                            ),
                            modifier = Modifier.testTag("send_quick_cue_${preset.hashCode()}")
                        )
                    }
                }

                // Urgency Selector + Custom Cue Sender
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CueUrgency.entries.forEach { urg ->
                        val selected = selectedUrgency == urg
                        FilterChip(
                            selected = selected,
                            onClick = { selectedUrgency = urg },
                            label = { Text(urg.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when (urg) {
                                    CueUrgency.ALERT -> TallyCrimsonContainer
                                    CueUrgency.ACTION -> SignalCyanContainer
                                    CueUrgency.INFO -> RackSlateElevated
                                },
                                selectedLabelColor = when (urg) {
                                    CueUrgency.ALERT -> TallyCrimson
                                    CueUrgency.ACTION -> SignalCyan
                                    CueUrgency.INFO -> StudioTextPrimary
                                }
                            ),
                            modifier = Modifier.testTag("cue_urgency_${urg.name.lowercase()}")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = cueInput,
                        onValueChange = { cueInput = it },
                        label = { Text("Send live cue to connected phone") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SignalCyan,
                            unfocusedBorderColor = RackBorder,
                            focusedContainerColor = StudioObsidian,
                            unfocusedContainerColor = StudioObsidian
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cue_message_input")
                    )
                    IconButton(
                        onClick = {
                            if (cueInput.isNotBlank()) {
                                onSendCue(cueInput, selectedUrgency)
                                cueInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TallyCrimson)
                            .testTag("send_cue_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send cue", tint = StudioTextPrimary)
                    }
                }

                // Add Quick Cue Shortcut
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newShortcutInput,
                        onValueChange = { newShortcutInput = it },
                        placeholder = { Text("Save new quick cue preset...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LinkEmerald,
                            unfocusedBorderColor = RackBorder,
                            focusedContainerColor = StudioObsidian,
                            unfocusedContainerColor = StudioObsidian
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_cue_preset_input")
                    )
                    AssistChip(
                        onClick = {
                            if (newShortcutInput.isNotBlank()) {
                                onAddQuickCue(newShortcutInput)
                                newShortcutInput = ""
                            }
                        },
                        label = { Text("Save") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = RackSlateElevated),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("save_cue_preset_button")
                    )
                }

                // Active Cue Feed
                if (uiState.cues.isEmpty()) {
                    Surface(
                        color = StudioObsidian,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RackBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No cues sent yet. Tap a shortcut chip above during your stream to alert your co-host or game phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextMuted,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.cues.take(8).forEach { cue ->
                            Surface(
                                color = StudioObsidian,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (cue.acknowledged) LinkEmerald.copy(alpha = 0.4f) else PeakAmber.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${cue.senderRole} • ${timeFormatter.format(Date(cue.timestampMs))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioTextMuted
                                        )
                                        Text(cue.text, style = MaterialTheme.typography.bodyLarge, color = StudioTextPrimary)
                                    }
                                    if (cue.acknowledged) {
                                        Icon(Icons.Filled.Check, contentDescription = "Acknowledged", tint = LinkEmerald)
                                    } else {
                                        TextButton(
                                            onClick = { onAcknowledgeCue(cue.id) },
                                            modifier = Modifier.testTag("ack_cue_${cue.id}")
                                        ) {
                                            Text("ACK", color = PeakAmber)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // USB Transport & Codec Diagnostic Log
        Surface(
            color = RackSlate,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, RackBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = SignalCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("USB & Codec Telemetry Log", style = MaterialTheme.typography.titleMedium, color = StudioTextPrimary)
                    }
                    TextButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs", tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                if (uiState.logs.isEmpty()) {
                    Text(
                        text = "Transport events and encoder handshakes will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.logs.take(15).forEach { log ->
                            Text(
                                text = "[${timeFormatter.format(Date(log.timestampMs))}] ${log.tag}: ${log.message}",
                                style = TelemetryTextStyle,
                                color = if (log.isError) TallyCrimson else StudioTextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
