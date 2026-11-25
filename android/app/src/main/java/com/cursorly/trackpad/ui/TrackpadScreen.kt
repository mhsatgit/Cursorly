package com.cursorly.trackpad.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorly.trackpad.input.TrackpadWithButtonsPanel
import com.cursorly.trackpad.model.ConnectionState
import com.cursorly.trackpad.model.TrackpadOrientation
import java.util.Locale

@Composable
fun TrackpadScreen(
    viewModel: TrackpadViewModel,
    hasBluetoothPermission: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onOpenTetheringSettings: () -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val rttMs by viewModel.rttMs.collectAsState()
    val hz by viewModel.hz.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val hosts by viewModel.discoveredHosts.collectAsState()
    val usbNoTether by viewModel.usbConnectedWithoutTethering.collectAsState()

    var showConnectionSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showKeyboardBar by remember { mutableStateOf(false) }

    val view = LocalView.current

    fun hapticTap() {
        if (settings.hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 1. Top Status & Quick Controls Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111827))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Connection Pill
                val badgeColor = when (status.state) {
                    ConnectionState.CONNECTED -> Color(0xFF22C55E)
                    ConnectionState.CONNECTING -> Color(0xFFF59E0B)
                    ConnectionState.ERROR -> Color(0xFFEF4444)
                    ConnectionState.DISCONNECTED -> Color(0xFF64748B)
                }
                val pillLabel = when (status.state) {
                    ConnectionState.CONNECTED -> {
                        val rttPart = rttMs?.let { " · ${it}ms" } ?: ""
                        "${status.detail}$rttPart"
                    }
                    ConnectionState.CONNECTING -> status.detail
                    ConnectionState.ERROR -> status.detail
                    ConnectionState.DISCONNECTED -> {
                        if (hosts.isNotEmpty()) "${hosts.size} PC available · Tap to connect"
                        else "Tap to connect PC"
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showConnectionSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = pillLabel,
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "$hz Hz",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )

                IconButton(
                    onClick = {
                        hapticTap()
                        showKeyboardBar = !showKeyboardBar
                    }
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = "Keyboard",
                        tint = if (showKeyboardBar) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                    )
                }

                IconButton(onClick = { showSettingsSheet = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF94A3B8),
                    )
                }
            }

            if (usbNoTether && status.state != ConnectionState.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTetheringSettings() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Usb, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                        Text(
                            text = "USB cable detected — tap here to enable USB Tethering (1ms wired mode)",
                            color = Color(0xFFFDE68A),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Sensitivity + Preset Chips + Orientation + Sniper
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = String.format(Locale.US, "%.2fx", settings.sensitivity),
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(42.dp),
                )
                Slider(
                    value = settings.sensitivity,
                    onValueChange = { viewModel.setSensitivity(it) },
                    valueRange = 0.05f..1.50f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8),
                        inactiveTrackColor = Color(0xFF334155),
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (preset in listOf(0.20f, 0.50f, 0.80f)) {
                    val selected = kotlin.math.abs(settings.sensitivity - preset) < 0.02f
                    CompactChip(
                        label = "${preset}x",
                        selected = selected,
                        selectedBg = Color(0xFF38BDF8),
                        selectedFg = Color(0xFF0B0F19),
                        unselectedBg = Color(0xFF1E293B),
                        unselectedFg = Color(0xFFCBD5E1),
                        borderColor = Color(0xFF334155),
                        onClick = {
                            hapticTap()
                            viewModel.setSensitivity(preset)
                        },
                    )
                }

                CompactChip(
                    label = settings.orientation.label,
                    selected = settings.orientation == TrackpadOrientation.LANDSCAPE,
                    selectedBg = Color(0xFF6366F1),
                    selectedFg = Color.White,
                    unselectedBg = Color(0xFF1E1B4B),
                    unselectedFg = Color(0xFFA5B4FC),
                    borderColor = Color(0xFF6366F1),
                    icon = Icons.Default.ScreenRotation,
                    onClick = {
                        hapticTap()
                        viewModel.toggleOrientation()
                    },
                )

                CompactChip(
                    label = "Sniper",
                    selected = settings.precisionMode,
                    selectedBg = Color(0xFFF43F5E),
                    selectedFg = Color.White,
                    unselectedBg = Color(0xFF27272A),
                    unselectedFg = Color(0xFFFDA4AF),
                    borderColor = Color(0xFFF43F5E),
                    icon = Icons.Default.GpsFixed,
                    onClick = {
                        hapticTap()
                        viewModel.togglePrecisionMode()
                    },
                )
            }
        }

        // 2. Optional Collapsible Remote Keyboard Bar
        if (showKeyboardBar) {
            KeyboardBar(
                onSendText = { viewModel.sendKeyboardText(it) },
                onSendKey = {
                    hapticTap()
                    viewModel.sendNamedKey(it)
                },
            )
        }

        // 3. Multi-Touch Trackpad Surface + Split-Touch Bottom Mouse Buttons
        TrackpadWithButtonsPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            hapticsEnabled = settings.hapticsEnabled,
            onTouchSample = { etype, pid, xDp, yDp, tSec ->
                viewModel.onTouchSample(etype, pid, xDp, yDp, tSec)
            },
            onLeftChange = { pressed ->
                if (pressed) viewModel.sendLeftDown() else viewModel.sendLeftUp()
            },
            onMiddleChange = { pressed ->
                if (pressed) viewModel.sendMiddleDown() else viewModel.sendMiddleUp()
            },
            onRightChange = { pressed ->
                if (pressed) viewModel.sendRightDown() else viewModel.sendRightUp()
            },
        )
    }

    if (showConnectionSheet) {
        ConnectionSheet(
            status = status,
            hosts = hosts,
            hasBluetoothPermission = hasBluetoothPermission,
            usbConnectedWithoutTethering = usbNoTether,
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onOpenTetheringSettings = onOpenTetheringSettings,
            onRefresh = { viewModel.refreshDiscovery() },
            onConnect = { host, kind, ip -> viewModel.connectToHost(host, kind, ip) },
            onConnectManualIp = { ip, port -> viewModel.connectManualIp(ip, port) },
            onDisconnect = { viewModel.disconnect() },
            onDismiss = { showConnectionSheet = false },
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            onAutoConnectChange = { viewModel.setAutoConnect(it) },
            onNaturalScrollChange = { viewModel.setNaturalScroll(it) },
            onTapToClickChange = { viewModel.setTapToClick(it) },
            onHapticsChange = { viewModel.setHapticsEnabled(it) },
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    selectedBg: Color,
    selectedFg: Color,
    unselectedBg: Color,
    unselectedFg: Color,
    borderColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) selectedBg else unselectedBg
    val fg = if (selected) selectedFg else unselectedFg
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, if (selected) selectedBg else borderColor),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            }
            Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
