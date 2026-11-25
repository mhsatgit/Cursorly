package com.cursorly.trackpad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorly.trackpad.model.TrackpadSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: TrackpadSettings,
    onAutoConnectChange: (Boolean) -> Unit,
    onNaturalScrollChange: (Boolean) -> Unit,
    onTapToClickChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        contentColor = Color(0xFFF1F5F9),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Trackpad Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF8FAFC),
            )

            SettingSwitchRow(
                title = "Auto-connect to PC",
                subtitle = "Automatically connect when a single Cursorly PC is detected",
                checked = settings.autoConnect,
                onCheckedChange = onAutoConnectChange,
            )
            SettingSwitchRow(
                title = "Natural Scrolling",
                subtitle = "Invert 2-finger scroll direction (content follows fingers)",
                checked = settings.naturalScroll,
                onCheckedChange = onNaturalScrollChange,
            )
            SettingSwitchRow(
                title = "Tap-to-Click",
                subtitle = "1-finger tap = Left Click, 2-finger = Right, 3-finger = Middle",
                checked = settings.tapToClick,
                onCheckedChange = onTapToClickChange,
            )
            SettingSwitchRow(
                title = "Haptic Feedback",
                subtitle = "Tactile vibration on mouse button clicks and mode switches",
                checked = settings.hapticsEnabled,
                onCheckedChange = onHapticsChange,
            )
            SettingSwitchRow(
                title = "Keep Screen Awake",
                subtitle = "Prevent phone display from sleeping while trackpad is open",
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF1F5F9))
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF94A3B8))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
