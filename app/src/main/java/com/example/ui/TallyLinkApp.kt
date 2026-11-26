package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.DeviceRole
import com.example.ui.components.AccessibilityDisclosureDialog
import com.example.ui.components.TallyHeaderBar
import com.example.ui.screens.AudioQualityScreen
import com.example.ui.screens.CuesLogScreen
import com.example.ui.screens.DirectorMonitorScreen
import com.example.ui.screens.StudioLinkScreen
import com.example.ui.theme.RackSlate
import com.example.ui.theme.SignalCyan
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.TallyCrimson
import com.example.ui.theme.TallyCrimsonContainer

private enum class StudioTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    LINK("Studio Link", Icons.Filled.Cable, Icons.Outlined.Cable, "nav_tab_link"),
    MIXER("Audio & Video", Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq, "nav_tab_mixer"),
    CUES("Cues & Log", Icons.Filled.Campaign, Icons.Outlined.Campaign, "nav_tab_cues")
}

@Composable
fun TallyLinkApp(
    viewModel: StudioMainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf(StudioTab.LINK) }

    // Refresh permissions & pause UI telemetry/polling whenever the Host switches into a game
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setUiForeground(true)
                Lifecycle.Event.ON_RESUME -> viewModel.refreshPermissionsAndProbe()
                Lifecycle.Event.ON_STOP -> viewModel.setUiForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setUiForeground(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var pendingHostLaunch by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startHostWithProjection(result.resultCode, result.data!!)
        }
    }

    val launchMediaProjectionPrompt: () -> Unit = {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm != null) {
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshPermissionsAndProbe()
        if (pendingHostLaunch) {
            pendingHostLaunch = false
            launchMediaProjectionPrompt()
        }
    }

    val startHostWithConsent: () -> Unit = {
        val neededPermissions = buildList {
            if (!uiState.hasMicPermission) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !uiState.hasNotificationPermission) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (neededPermissions.isNotEmpty()) {
            pendingHostLaunch = true
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            launchMediaProjectionPrompt()
        }
    }

    if (uiState.showAccessibilityDialog) {
        AccessibilityDisclosureDialog(
            onOpenSettings = {
                viewModel.setShowAccessibilityDialog(false)
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            onDismiss = { viewModel.setShowAccessibilityDialog(false) }
        )
    }

    // Full-screen Director Monitor Deck when Director role is active
    if (uiState.role == DeviceRole.DIRECTOR) {
        DirectorMonitorScreen(
            uiState = uiState,
            onAttachSurface = viewModel::attachDirectorSurface,
            onInteractionModeChange = viewModel::setInteractionMode,
            onBrushColorChange = viewModel::setBrushColor,
            onRemoteTouch = viewModel::sendRemoteTouch,
            onUpsertStroke = viewModel::upsertStroke,
            onClearTelestrator = viewModel::clearTelestrator,
            onSendCue = viewModel::sendCue,
            onToggleAudioMute = viewModel::setDirectorAudioMuted,
            onRequestKeyframe = viewModel::requestKeyframe,
            onStopSession = viewModel::stopSession
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioObsidian)
    ) {
        val isExpandedScreen = maxWidth >= 840.dp

        if (isExpandedScreen) {
            // Adaptive Tablet / Foldable Layout with NavigationRail + Split Broadcast Deck
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = RackSlate,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    StudioTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        NavigationRailItem(
                            selected = active,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = if (active) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = TallyCrimson,
                                selectedTextColor = StudioTextPrimary,
                                indicatorColor = TallyCrimsonContainer,
                                unselectedIconColor = StudioTextMuted,
                                unselectedTextColor = StudioTextMuted
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    TallyHeaderBar(
                        role = uiState.role,
                        linkStatus = uiState.linkStatus,
                        rttMs = uiState.telemetry.rttMs,
                        fps = uiState.telemetry.actualFps
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(modifier = Modifier.weight(1.1f)) {
                            StudioLinkScreen(
                                uiState = uiState,
                                onStartHost = startHostWithConsent,
                                onStartDirector = viewModel::startDirector,
                                onStopSession = viewModel::stopSession,
                                onRefreshProbe = viewModel::refreshPermissionsAndProbe,
                                onOpenUsbTetherSettings = { openUsbTetherSettings(context) },
                                onRequestUsbPermission = viewModel::requestUsbPermissionIfNeeded,
                                onRequestMicPermission = {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                },
                                onOpenAccessibilityDisclosure = { viewModel.setShowAccessibilityDialog(true) },
                                onToggleRemoteLock = viewModel::setRemoteControlEnabled,
                                onClearTelestrator = viewModel::clearTelestrator,
                                onAcknowledgeCue = viewModel::acknowledgeCue,
                                onTransportPrefChange = viewModel::updateTransportPreference,
                                onManualIpChange = viewModel::updateManualPeerIp
                            )
                        }
                        Box(modifier = Modifier.weight(0.9f)) {
                            if (selectedTab == StudioTab.CUES) {
                                CuesLogScreen(
                                    uiState = uiState,
                                    onSendCue = viewModel::sendCue,
                                    onAcknowledgeCue = viewModel::acknowledgeCue,
                                    onAddQuickCue = viewModel::addQuickCueShortcut,
                                    onRemoveQuickCue = viewModel::removeQuickCueShortcut,
                                    onClearLogs = viewModel::clearLogs
                                )
                            } else {
                                AudioQualityScreen(
                                    uiState = uiState,
                                    onUpdateAudioMixer = viewModel::updateAudioMixer,
                                    onUpdateQualityProfile = viewModel::updateQualityProfile
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Compact Mobile Layout with Scaffold + Bottom NavigationBar
            Scaffold(
                containerColor = StudioObsidian,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        containerColor = RackSlate,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        StudioTab.entries.forEach { tab ->
                            val active = selectedTab == tab
                            NavigationBarItem(
                                selected = active,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (active) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TallyCrimson,
                                    selectedTextColor = StudioTextPrimary,
                                    indicatorColor = TallyCrimsonContainer,
                                    unselectedIconColor = StudioTextMuted,
                                    unselectedTextColor = StudioTextMuted
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 620.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TallyHeaderBar(
                            role = uiState.role,
                            linkStatus = uiState.linkStatus,
                            rttMs = uiState.telemetry.rttMs,
                            fps = uiState.telemetry.actualFps
                        )

                        when (selectedTab) {
                            StudioTab.LINK -> StudioLinkScreen(
                                uiState = uiState,
                                onStartHost = startHostWithConsent,
                                onStartDirector = viewModel::startDirector,
                                onStopSession = viewModel::stopSession,
                                onRefreshProbe = viewModel::refreshPermissionsAndProbe,
                                onOpenUsbTetherSettings = { openUsbTetherSettings(context) },
                                onRequestUsbPermission = viewModel::requestUsbPermissionIfNeeded,
                                onRequestMicPermission = {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                },
                                onOpenAccessibilityDisclosure = { viewModel.setShowAccessibilityDialog(true) },
                                onToggleRemoteLock = viewModel::setRemoteControlEnabled,
                                onClearTelestrator = viewModel::clearTelestrator,
                                onAcknowledgeCue = viewModel::acknowledgeCue,
                                onTransportPrefChange = viewModel::updateTransportPreference,
                                onManualIpChange = viewModel::updateManualPeerIp,
                                modifier = Modifier.weight(1f)
                            )
                            StudioTab.MIXER -> AudioQualityScreen(
                                uiState = uiState,
                                onUpdateAudioMixer = viewModel::updateAudioMixer,
                                onUpdateQualityProfile = viewModel::updateQualityProfile,
                                modifier = Modifier.weight(1f)
                            )
                            StudioTab.CUES -> CuesLogScreen(
                                uiState = uiState,
                                onSendCue = viewModel::sendCue,
                                onAcknowledgeCue = viewModel::acknowledgeCue,
                                onAddQuickCue = viewModel::addQuickCueShortcut,
                                onRemoveQuickCue = viewModel::removeQuickCueShortcut,
                                onClearLogs = viewModel::clearLogs,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openUsbTetherSettings(context: Context) {
    val tetherIntent = Intent().apply {
        setClassName("com.android.settings", "com.android.settings.TetherSettings")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(tetherIntent)
    } catch (_: Exception) {
        val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(fallback)
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
