package com.cursorly.trackpad.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.cursorly.trackpad.discovery.HostDiscoveryManager
import com.cursorly.trackpad.input.HidReportAction
import com.cursorly.trackpad.input.LocalHidGestureEngine
import com.cursorly.trackpad.model.ConnectionState
import com.cursorly.trackpad.model.ConnectionStatus
import com.cursorly.trackpad.model.ConnectionTarget
import com.cursorly.trackpad.model.HostStateSync
import com.cursorly.trackpad.model.TrackpadOrientation
import com.cursorly.trackpad.protocol.PacketCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Standalone Bluetooth HID Mouse/Trackpad transport using Android's BluetoothHidDevice API.
 * Allows controlling any Windows/macOS/Linux PC natively over Bluetooth with no PC host app running.
 */
class BluetoothHidTransport(private val context: Context) : TrackpadTransport {

    companion object {
        private const val REPORT_ID_MOUSE = 1

        // Standard Mouse HID Descriptor (Report ID 1):
        // Byte 0: 3 buttons + 5 padding bits
        // Byte 1: X (-127..127)
        // Byte 2: Y (-127..127)
        // Byte 3: Vertical Wheel (-127..127)
        // Byte 4: Horizontal AC Pan (-127..127)
        private val MOUSE_HID_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), REPORT_ID_MOUSE.toByte(), // Report ID (1)
            0x09, 0x01,       // Usage (Pointer)
            0xA1.toByte(), 0x00, // Collection (Physical)
            0x05, 0x09,       // Usage Page (Button)
            0x19, 0x01,       // Usage Minimum (Button 1)
            0x29, 0x03,       // Usage Maximum (Button 3)
            0x15, 0x00,       // Logical Minimum (0)
            0x25, 0x01,       // Logical Maximum (1)
            0x95.toByte(), 0x03, // Report Count (3)
            0x75, 0x01,       // Report Size (1)
            0x81.toByte(), 0x02, // Input (Data, Var, Abs)
            0x95.toByte(), 0x01, // Report Count (1)
            0x75, 0x05,       // Report Size (5)
            0x81.toByte(), 0x03, // Input (Const, Var, Abs)
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x30,       // Usage (X)
            0x09, 0x31,       // Usage (Y)
            0x09, 0x38,       // Usage (Wheel)
            0x15, 0x81.toByte(), // Logical Minimum (-127)
            0x25, 0x7F,       // Logical Maximum (127)
            0x75, 0x08,       // Report Size (8)
            0x95.toByte(), 0x03, // Report Count (3)
            0x81.toByte(), 0x06, // Input (Data, Var, Rel)
            0x05, 0x0C,       // Usage Page (Consumer)
            0x0A, 0x38, 0x02, // Usage (AC Pan - Horizontal Wheel)
            0x15, 0x81.toByte(), // Logical Minimum (-127)
            0x25, 0x7F,       // Logical Maximum (127)
            0x75, 0x08,       // Report Size (8)
            0x95.toByte(), 0x01, // Report Count (1)
            0x81.toByte(), 0x06, // Input (Data, Var, Rel)
            0xC0.toByte(),    // End Collection (Physical)
            0xC0.toByte(),    // End Collection (Application)
        )
    }

    private val gestureEngine = LocalHidGestureEngine()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pendingFuture: ScheduledFuture<*>? = null

    private val _status = MutableStateFlow(ConnectionStatus())
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _rttMs = MutableStateFlow<Int?>(null)
    override val rttMs: StateFlow<Int?> = _rttMs.asStateFlow()

    private val _remoteState = MutableStateFlow<HostStateSync?>(null)
    override val remoteState: StateFlow<HostStateSync?> = _remoteState.asStateFlow()

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private var pendingTarget: ConnectionTarget? = null

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            val target = pendingTarget ?: return
            if (registered) {
                val mac = target.btAddress
                val device = if (mac != null) adapter?.getRemoteDevice(mac) else pluggedDevice
                if (device != null) {
                    hidDevice?.connect(device)
                }
            } else {
                _status.value = ConnectionStatus(ConnectionState.ERROR, target, "HID registration failed")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val target = pendingTarget
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    val deviceName = if (HostDiscoveryManager.hasBluetoothPermissions(context)) {
                        try {
                            device?.name
                        } catch (_: SecurityException) {
                            null
                        }
                    } else {
                        null
                    }
                    _status.value = ConnectionStatus(
                        state = ConnectionState.CONNECTED,
                        target = target,
                        detail = "${target?.hostName ?: deviceName ?: "PC"} · BT HID Mouse",
                    )
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _status.value = ConnectionStatus(
                        state = ConnectionState.CONNECTING,
                        target = target,
                        detail = "Pairing HID with ${target?.hostName ?: "PC"}...",
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    hostDevice = null
                    executor.execute {
                        pendingFuture?.cancel(false)
                        pendingFuture = null
                        gestureEngine.reset()
                    }
                    _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = proxy as BluetoothHidDevice
            hidDevice = hid
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Cursorly Trackpad",
                "Precision Wireless Trackpad",
                "Cursorly",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                MOUSE_HID_DESCRIPTOR,
            )
            hid.registerApp(sdp, null, null, executor, hidCallback)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                hostDevice = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(target: ConnectionTarget) {
        if (!HostDiscoveryManager.hasBluetoothPermissions(context)) {
            _status.value = ConnectionStatus(ConnectionState.ERROR, target, "Bluetooth permission required")
            return
        }
        val btMgr = context.getSystemService(BluetoothManager::class.java)
        val btAdapter = btMgr?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _status.value = ConnectionStatus(ConnectionState.ERROR, target, "Enable Bluetooth first")
            return
        }
        disconnect()
        adapter = btAdapter
        pendingTarget = target
        executor.execute { gestureEngine.reset() }
        _status.value = ConnectionStatus(
            state = ConnectionState.CONNECTING,
            target = target,
            detail = "Starting Bluetooth HID Mouse...",
        )
        btAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val device = hostDevice
        val hid = hidDevice
        if (device != null && hid != null) {
            try {
                hid.disconnect(device)
            } catch (_: Exception) {
            }
        }
        try {
            hid?.unregisterApp()
        } catch (_: Exception) {
        }
        if (hid != null) {
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        hidDevice = null
        hostDevice = null
        pendingTarget = null
        executor.execute {
            pendingFuture?.cancel(false)
            pendingFuture = null
            gestureEngine.reset()
        }
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
    }

    private fun schedulePendingCheck() {
        pendingFuture?.cancel(false)
        pendingFuture = null
        val delayMs = gestureEngine.nextPendingDelayMs() ?: return
        pendingFuture = executor.schedule(
            {
                val actions = gestureEngine.pollPendingActions()
                for (act in actions) {
                    sendHidReport(act)
                }
                schedulePendingCheck()
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun sendTouch(eventType: Int, pointerId: Int, x: Float, y: Float, timestampSec: Double) {
        executor.execute {
            val actions = when (eventType) {
                PacketCodec.EVENT_DOWN -> gestureEngine.onTouchDown(pointerId, x, y, timestampSec)
                PacketCodec.EVENT_MOVE -> gestureEngine.onTouchMove(pointerId, x, y, timestampSec)
                PacketCodec.EVENT_UP -> gestureEngine.onTouchUp(pointerId, x, y, timestampSec)
                else -> emptyList()
            }
            for (act in actions) {
                sendHidReport(act)
            }
            schedulePendingCheck()
        }
    }

    override fun sendExplicitButton(maskBit: Int, pressed: Boolean) {
        executor.execute {
            sendHidReport(gestureEngine.setExplicitButton(maskBit, pressed))
        }
    }

    override fun sendControl(jsonPayload: String) {
        executor.execute {
            try {
                val obj = JSONObject(jsonPayload)
                when (obj.optString("action")) {
                    "set_sensitivity" -> gestureEngine.sensitivity = obj.optDouble("value", 0.5).toFloat()
                    "toggle_precision" -> gestureEngine.precisionMode = !gestureEngine.precisionMode
                    "set_orientation" -> {
                        gestureEngine.orientation = if (obj.optString("orientation").startsWith("landscape")) {
                            TrackpadOrientation.LANDSCAPE
                        } else {
                            TrackpadOrientation.PORTRAIT
                        }
                    }
                    "toggle_orientation" -> {
                        gestureEngine.orientation = if (gestureEngine.orientation == TrackpadOrientation.PORTRAIT) {
                            TrackpadOrientation.LANDSCAPE
                        } else {
                            TrackpadOrientation.PORTRAIT
                        }
                    }
                    "set_natural_scroll" -> gestureEngine.naturalScroll = obj.optBoolean("enabled", false)
                    "set_tap_to_click" -> gestureEngine.tapToClick = obj.optBoolean("enabled", true)
                    "left_click" -> {
                        sendHidReport(HidReportAction(buttons = 0x01))
                        sendHidReport(HidReportAction(buttons = 0x00))
                    }
                    "right_click" -> {
                        sendHidReport(HidReportAction(buttons = 0x02))
                        sendHidReport(HidReportAction(buttons = 0x00))
                    }
                    "middle_click" -> {
                        sendHidReport(HidReportAction(buttons = 0x04))
                        sendHidReport(HidReportAction(buttons = 0x00))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHidReport(action: HidReportAction) {
        val hid = hidDevice ?: return
        val device = hostDevice ?: return
        val report = byteArrayOf(
            (action.buttons and 0x07).toByte(),
            action.dx.coerceIn(-127, 127).toByte(),
            action.dy.coerceIn(-127, 127).toByte(),
            action.wheelV.coerceIn(-127, 127).toByte(),
            action.wheelH.coerceIn(-127, 127).toByte(),
        )
        try {
            hid.sendReport(device, REPORT_ID_MOUSE, report)
        } catch (_: Exception) {
        }
    }
}
