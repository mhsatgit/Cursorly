package com.cursorly.trackpad.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cursorly.trackpad.discovery.HostDiscoveryManager
import com.cursorly.trackpad.model.ConnectionState
import com.cursorly.trackpad.model.ConnectionStatus
import com.cursorly.trackpad.model.ConnectionTarget
import com.cursorly.trackpad.model.DiscoveredHost
import com.cursorly.trackpad.model.TrackpadOrientation
import com.cursorly.trackpad.model.TrackpadSettings
import com.cursorly.trackpad.model.TransportKind
import com.cursorly.trackpad.protocol.PacketCodec
import com.cursorly.trackpad.transport.BluetoothHidTransport
import com.cursorly.trackpad.transport.BluetoothRfcommTransport
import com.cursorly.trackpad.transport.TrackpadTransport
import com.cursorly.trackpad.transport.WebSocketTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class TrackpadViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = HostDiscoveryManager(application)
    private val wsTransport = WebSocketTransport()
    private val btRfcommTransport = BluetoothRfcommTransport(application)
    private val btHidTransport = BluetoothHidTransport(application)

    private val _activeTransport = MutableStateFlow<TrackpadTransport>(wsTransport)
    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _rttMs = MutableStateFlow<Int?>(null)
    val rttMs: StateFlow<Int?> = _rttMs.asStateFlow()

    private val _hz = MutableStateFlow(0)
    val hz: StateFlow<Int> = _hz.asStateFlow()

    private val _settings = MutableStateFlow(TrackpadSettings())
    val settings: StateFlow<TrackpadSettings> = _settings.asStateFlow()

    val discoveredHosts: StateFlow<List<DiscoveredHost>> = discoveryManager.hosts
    val usbConnectedWithoutTethering: StateFlow<Boolean> = discoveryManager.usbConnectedWithoutTethering
    val usbTetheringActive: StateFlow<Boolean> = discoveryManager.usbTetheringActive

    private val sampleCounter = AtomicInteger(0)
    private var userManuallyDisconnected = false

    init {
        discoveryManager.start()

        // Forward active transport status/RTT/remote state sync
        viewModelScope.launch {
            _activeTransport.collectLatest { transport ->
                launch {
                    transport.status.collect { st ->
                        _status.value = st
                        if (st.state == ConnectionState.CONNECTED) {
                            pushCurrentSettingsToTransport(transport)
                        }
                    }
                }
                launch {
                    transport.rttMs.collect { _rttMs.value = it }
                }
                launch {
                    transport.remoteState.collect { sync ->
                        if (sync != null) {
                            _settings.value = _settings.value.copy(
                                sensitivity = sync.sensitivity.coerceIn(0.05f, 1.50f),
                                precisionMode = sync.precisionMode,
                                orientation = sync.orientation,
                                naturalScroll = sync.naturalScroll,
                            )
                        }
                    }
                }
            }
        }

        // Measure outgoing touch sample rate (Hz)
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _hz.value = sampleCounter.getAndSet(0)
            }
        }

        // Seamless auto-connect when a network/USB PC host appears and we're disconnected
        viewModelScope.launch {
            discoveryManager.hosts.collect { hosts ->
                if (
                    _settings.value.autoConnect &&
                    !userManuallyDisconnected &&
                    _status.value.state == ConnectionState.DISCONNECTED
                ) {
                    val networkCandidates = hosts.filter { !it.pairedBtOnly && it.primaryEndpoint != null }
                    if (networkCandidates.size == 1) {
                        val host = networkCandidates.first()
                        val ep = host.primaryEndpoint!!
                        connectToHost(host, ep.kind, ep.ip)
                    }
                }
            }
        }
    }

    fun refreshDiscovery() {
        discoveryManager.triggerImmediateProbe()
    }

    fun connectToHost(host: DiscoveredHost, kind: TransportKind, ipOverride: String? = null) {
        userManuallyDisconnected = false
        val target = when (kind) {
            TransportKind.WIFI, TransportKind.HOTSPOT, TransportKind.USB_TETHER -> {
                val ep = host.endpoints.firstOrNull { it.ip == ipOverride }
                    ?: host.endpoints.firstOrNull { it.kind == kind }
                    ?: host.primaryEndpoint
                    ?: return
                ConnectionTarget(
                    hostName = host.hostName,
                    transportKind = ep.kind,
                    ip = ep.ip,
                    port = host.wsPort,
                )
            }
            TransportKind.BLUETOOTH_HOST, TransportKind.BLUETOOTH_HID -> {
                val mac = host.btMac ?: return
                ConnectionTarget(
                    hostName = host.hostName,
                    transportKind = kind,
                    btAddress = mac,
                    btChannel = host.btChannel,
                )
            }
        }
        connectTarget(target)
    }

    fun connectManualIp(ip: String, port: Int = 8080) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return
        userManuallyDisconnected = false
        connectTarget(
            ConnectionTarget(
                hostName = cleanIp,
                transportKind = TransportKind.WIFI,
                ip = cleanIp,
                port = port,
            )
        )
    }

    private fun connectTarget(target: ConnectionTarget) {
        _activeTransport.value.disconnect()
        val chosen: TrackpadTransport = when (target.transportKind) {
            TransportKind.WIFI, TransportKind.HOTSPOT, TransportKind.USB_TETHER -> wsTransport
            TransportKind.BLUETOOTH_HOST -> btRfcommTransport
            TransportKind.BLUETOOTH_HID -> btHidTransport
        }
        _activeTransport.value = chosen
        chosen.connect(target)
    }

    fun disconnect() {
        userManuallyDisconnected = true
        _activeTransport.value.disconnect()
    }

    fun onTouchSample(eventType: Int, pointerId: Int, xDp: Float, yDp: Float, timestampSec: Double) {
        if (_status.value.state != ConnectionState.CONNECTED) return
        sampleCounter.incrementAndGet()
        _activeTransport.value.sendTouch(eventType, pointerId, xDp, yDp, timestampSec)
    }

    fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(0.05f, 1.50f)
        _settings.value = _settings.value.copy(sensitivity = clamped)
        _activeTransport.value.sendControl(PacketCodec.jsonSetSensitivity(clamped))
    }

    fun togglePrecisionMode() {
        val next = !_settings.value.precisionMode
        _settings.value = _settings.value.copy(precisionMode = next)
        _activeTransport.value.sendControl(PacketCodec.jsonAction("toggle_precision"))
    }

    fun toggleOrientation() {
        val next = if (_settings.value.orientation == TrackpadOrientation.PORTRAIT) {
            TrackpadOrientation.LANDSCAPE
        } else {
            TrackpadOrientation.PORTRAIT
        }
        _settings.value = _settings.value.copy(orientation = next)
        _activeTransport.value.sendControl(PacketCodec.jsonSetOrientation(next.wireValue))
    }

    fun setNaturalScroll(enabled: Boolean) {
        _settings.value = _settings.value.copy(naturalScroll = enabled)
        _activeTransport.value.sendControl(PacketCodec.jsonSetNaturalScroll(enabled))
    }

    fun setTapToClick(enabled: Boolean) {
        _settings.value = _settings.value.copy(tapToClick = enabled)
        _activeTransport.value.sendControl(PacketCodec.jsonSetTapToClick(enabled))
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(hapticsEnabled = enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _settings.value = _settings.value.copy(keepScreenOn = enabled)
    }

    fun setAutoConnect(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoConnect = enabled)
    }

    fun sendLeftDown() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x01, true)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("left_down"))
        }
    }

    fun sendLeftUp() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x01, false)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("left_up"))
        }
    }

    fun sendRightDown() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x02, true)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("right_down"))
        }
    }

    fun sendRightUp() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x02, false)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("right_up"))
        }
    }

    fun sendMiddleDown() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x04, true)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("middle_down"))
        }
    }

    fun sendMiddleUp() {
        if (_activeTransport.value === btHidTransport) {
            btHidTransport.sendExplicitButton(0x04, false)
        } else {
            _activeTransport.value.sendControl(PacketCodec.jsonAction("middle_up"))
        }
    }

    fun sendRightClick() {
        _activeTransport.value.sendControl(PacketCodec.jsonAction("right_click"))
    }

    fun sendMiddleClick() {
        _activeTransport.value.sendControl(PacketCodec.jsonAction("middle_click"))
    }

    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) return
        _activeTransport.value.sendControl(PacketCodec.jsonKeyText(text))
    }

    fun sendNamedKey(key: String) {
        _activeTransport.value.sendControl(PacketCodec.jsonKeyPress(key))
    }

    fun tetheringSettingsIntent(): Intent = discoveryManager.openTetheringSettingsIntent()

    private fun pushCurrentSettingsToTransport(transport: TrackpadTransport) {
        val s = _settings.value
        transport.sendControl(PacketCodec.jsonSetTapToClick(s.tapToClick))
        if (transport === btHidTransport) {
            transport.sendControl(PacketCodec.jsonSetSensitivity(s.sensitivity))
            transport.sendControl(PacketCodec.jsonSetOrientation(s.orientation.wireValue))
            transport.sendControl(PacketCodec.jsonSetNaturalScroll(s.naturalScroll))
            if (s.precisionMode) {
                transport.sendControl(PacketCodec.jsonAction("toggle_precision"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stop()
        wsTransport.disconnect()
        btRfcommTransport.disconnect()
        btHidTransport.disconnect()
    }
}
