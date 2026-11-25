package com.cursorly.trackpad

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.cursorly.trackpad.discovery.HostDiscoveryManager
import com.cursorly.trackpad.ui.TrackpadScreen
import com.cursorly.trackpad.ui.TrackpadViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TrackpadViewModel by viewModels()
    private var hasBluetoothPermission by mutableStateOf(false)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasBluetoothPermission = HostDiscoveryManager.hasBluetoothPermissions(this)
        viewModel.refreshDiscovery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasBluetoothPermission = HostDiscoveryManager.hasBluetoothPermissions(this)

        setContent {
            val settings by viewModel.settings.collectAsState()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF111827),
                )
            ) {
                TrackpadScreen(
                    viewModel = viewModel,
                    hasBluetoothPermission = hasBluetoothPermission,
                    onRequestBluetoothPermission = { requestBluetoothPermissions() },
                    onOpenTetheringSettings = {
                        try {
                            startActivity(viewModel.tetheringSettingsIntent())
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasBluetoothPermission = HostDiscoveryManager.hasBluetoothPermissions(this)
        viewModel.refreshDiscovery()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            )
        }
    }
}
