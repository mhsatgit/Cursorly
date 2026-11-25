package com.cursorly.trackpad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorly.trackpad.model.ConnectionState
import com.cursorly.trackpad.model.ConnectionStatus
import com.cursorly.trackpad.model.DiscoveredHost
import com.cursorly.trackpad.model.TransportKind

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionSheet(
    status: ConnectionStatus,
    hosts: List<DiscoveredHost>,
    hasBluetoothPermission: Boolean,
    usbConnectedWithoutTethering: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onOpenTetheringSettings: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (DiscoveredHost, TransportKind, String?) -> Unit,
    onConnectManualIp: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var manualIp by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        contentColor = Color(0xFFF1F5F9),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Available PCs", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                    Text(
                        text = "Auto-discovering over Wi-Fi, Hotspot, USB & Bluetooth",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8))
                }
            }

            if (status.state == ConnectionState.CONNECTED || status.state == ConnectionState.CONNECTING) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(status.detail, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC), fontSize = 14.sp)
                            Text("Active session", color = Color(0xFF38BDF8), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect", color = Color(0xFFF43F5E))
                        }
                    }
                }
            }

            if (usbConnectedWithoutTethering) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("USB Cable Detected", fontWeight = FontWeight.SemiBold, color = Color(0xFFFBBF24), fontSize = 13.sp)
                            Text(
                                "Enable USB Tethering for zero-ADB 1ms wired connection.",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1),
                            )
                        }
                        FilledTonalButton(onClick = onOpenTetheringSettings) {
                            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("Enable")
                        }
                    }
                }
            }

            if (!hasBluetoothPermission) {
                OutlinedButton(
                    onClick = onRequestBluetoothPermission,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0xFF6366F1)),
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFFA5B4FC), modifier = Modifier.padding(end = 6.dp))
                    Text("Allow Bluetooth to discover paired PCs", color = Color(0xFFA5B4FC))
                }
            }

            if (hosts.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Searching for PCs...", fontWeight = FontWeight.SemiBold, color = Color(0xFFF1F5F9))
                        Text(
                            "Start Cursorly on your Windows PC on the same Wi-Fi, Hotspot, or USB Tethering—or pair via Bluetooth.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostCard(
                            host = host,
                            onConnect = { kind, ip ->
                                onConnect(host, kind, ip)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            // Optional manual IP fallback
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Manual PC IP (e.g. 192.168.1.42)", fontSize = 12.sp, color = Color(0xFF64748B)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF1F5F9),
                        unfocusedTextColor = Color(0xFFF1F5F9),
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155),
                    ),
                )
                Button(
                    onClick = {
                        if (manualIp.isNotBlank()) {
                            onConnectManualIp(manualIp, 8080)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF0B0F19)),
                ) {
                    Text("Connect", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostCard(
    host: DiscoveredHost,
    onConnect: (TransportKind, String?) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = Color(0xFF38BDF8))
                Column {
                    Text(host.hostName, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC), fontSize = 15.sp)
                    val subtitle = buildString {
                        if (host.endpoints.isNotEmpty()) {
                            append(host.endpoints.joinToString(" · ") { "${it.kind.label}: ${it.ip}" })
                        } else if (host.btMac != null) {
                            append("Paired Bluetooth (${host.btMac})")
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (ep in host.endpoints) {
                    FilledTonalButton(
                        onClick = { onConnect(ep.kind, ep.ip) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        val icon = if (ep.kind == TransportKind.USB_TETHER) Icons.Default.Usb else Icons.Default.Wifi
                        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Connect ${ep.kind.label}", fontSize = 12.sp)
                    }
                }
                if (host.btMac != null) {
                    if (!host.pairedBtOnly) {
                        FilledTonalButton(
                            onClick = { onConnect(TransportKind.BLUETOOTH_HOST, null) },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("BT Host", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { onConnect(TransportKind.BLUETOOTH_HID, null) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("BT Mouse (No Host)", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
