package com.cursorly.trackpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KeyboardBar(
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF111827))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val keys = listOf(
                "Esc" to "escape",
                "Tab" to "tab",
                "Win" to "win",
                "Alt+Tab" to "alt_tab",
                "Ctrl+C" to "ctrl_c",
                "Ctrl+V" to "ctrl_v",
                "←" to "left",
                "↑" to "up",
                "↓" to "down",
                "→" to "right",
                "Del" to "delete",
            )
            for ((label, keyCode) in keys) {
                FilledTonalButton(
                    onClick = { onSendKey(keyCode) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type text to send to PC...", fontSize = 13.sp, color = Color(0xFF64748B)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF1F5F9),
                    unfocusedTextColor = Color(0xFFF1F5F9),
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF334155),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotEmpty()) {
                            onSendText(text)
                            text = ""
                        } else {
                            onSendKey("enter")
                        }
                    }
                ),
            )
            IconButton(onClick = { onSendKey("backspace") }) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", tint = Color(0xFF94A3B8))
            }
            IconButton(
                onClick = {
                    if (text.isNotEmpty()) {
                        onSendText(text)
                        text = ""
                    } else {
                        onSendKey("enter")
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color(0xFF38BDF8))
            }
        }
    }
}
