package com.cursorly.trackpad.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.cursorly.trackpad.discovery.HostDiscoveryManager
import com.cursorly.trackpad.model.ConnectionState
import com.cursorly.trackpad.model.ConnectionStatus
import com.cursorly.trackpad.model.ConnectionTarget
import com.cursorly.trackpad.model.HostStateSync
import com.cursorly.trackpad.model.TrackpadOrientation
import com.cursorly.trackpad.protocol.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothRfcommTransport(private val context: Context) : TrackpadTransport {

    companion object {
        val CURSORLY_SPP_UUID: UUID = UUID.fromString("7a9d3c10-8f4b-4e2a-9c1d-5b6e7f8a9012")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ConnectionStatus())
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _rttMs = MutableStateFlow<Int?>(null)
    override val rttMs: StateFlow<Int?> = _rttMs.asStateFlow()

    private val _remoteState = MutableStateFlow<HostStateSync?>(null)
    override val remoteState: StateFlow<HostStateSync?> = _remoteState.asStateFlow()

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var outStream: BufferedOutputStream? = null
    @Volatile
    private var manualDisconnect = false
    private val writeLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private var ioJob: Job? = null
    private var pingJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun connect(target: ConnectionTarget) {
        val address = target.btAddress ?: return
        if (!HostDiscoveryManager.hasBluetoothPermissions(context)) {
            _status.value = ConnectionStatus(ConnectionState.ERROR, target, "Bluetooth permission required")
            return
        }

        disconnect()
        manualDisconnect = false
        _status.value = ConnectionStatus(
            state = ConnectionState.CONNECTING,
            target = target,
            detail = "Connecting Bluetooth to ${target.hostName}...",
        )

        ioJob = scope.launch {
            try {
                val btMgr = context.getSystemService(BluetoothManager::class.java)
                val adapter = btMgr?.adapter ?: throw IOException("Bluetooth adapter unavailable")
                val device = adapter.getRemoteDevice(address)

                try {
                    adapter.cancelDiscovery()
                } catch (_: SecurityException) {
                }

                var btSocket: BluetoothSocket? = null
                try {
                    btSocket = device.createRfcommSocketToServiceRecord(CURSORLY_SPP_UUID)
                    btSocket.connect()
                } catch (sdpErr: IOException) {
                    try {
                        btSocket?.close()
                    } catch (_: Exception) {
                    }
                    val channel = target.btChannel
                    if (channel != null && channel > 0) {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        btSocket = method.invoke(device, channel) as BluetoothSocket
                        btSocket.connect()
                    } else {
                        throw sdpErr
                    }
                }

                socket = btSocket
                outStream = BufferedOutputStream(btSocket.outputStream, 1024)
                _status.value = ConnectionStatus(
                    state = ConnectionState.CONNECTED,
                    target = target,
                    detail = "${target.hostName} · Bluetooth Host",
                )
                sendControl(PacketCodec.jsonClientHello(Build.MODEL ?: "Android", "Bluetooth"))
                startPingLoop()

                val input = btSocket.inputStream
                while (isActive) {
                    val frame = PacketCodec.readRfcommFrame(input) ?: break
                    if (frame.first == PacketCodec.RFCOMM_FRAME_JSON_CONTROL) {
                        handleIncomingJson(String(frame.second, Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                if (!manualDisconnect) {
                    _status.value = ConnectionStatus(
                        state = ConnectionState.ERROR,
                        target = target,
                        detail = e.message ?: "Bluetooth connection failed",
                    )
                }
            } finally {
                cleanupSocket()
                if (!manualDisconnect && _status.value.state == ConnectionState.CONNECTED) {
                    _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
                }
            }
        }
    }

    private fun handleIncomingJson(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("event")) {
                "pong" -> {
                    val sentAt = obj.optLong("t", 0L)
                    if (sentAt > 0L) {
                        _rttMs.value = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(0)
                    }
                }
                "state_sync" -> {
                    val sens = obj.optDouble("sensitivity", 0.5).toFloat()
                    val prec = obj.optBoolean("precision_mode", false)
                    val orientStr = obj.optString("orientation", "portrait")
                    val orient = if (orientStr.startsWith("landscape")) TrackpadOrientation.LANDSCAPE else TrackpadOrientation.PORTRAIT
                    val natScroll = obj.optBoolean("natural_scroll", false)
                    _remoteState.value = HostStateSync(sens, prec, orient, natScroll)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && socket != null) {
                sendControl(PacketCodec.jsonPing(System.currentTimeMillis()))
                delay(2000)
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        ioJob?.cancel()
        pingJob?.cancel()
        cleanupSocket()
        _rttMs.value = null
        _remoteState.value = null
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
    }

    private fun cleanupSocket() {
        synchronized(writeLock) {
            try {
                outStream?.close()
            } catch (_: Exception) {
            }
            outStream = null
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    override fun sendTouch(eventType: Int, pointerId: Int, x: Float, y: Float, timestampSec: Double) {
        val pkt = PacketCodec.encodeTouchPacket(eventType, pointerId, x, y, timestampSec)
        sendFrame(PacketCodec.RFCOMM_FRAME_BINARY_TOUCH, pkt)
    }

    override fun sendControl(jsonPayload: String) {
        sendFrame(PacketCodec.RFCOMM_FRAME_JSON_CONTROL, jsonPayload.toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(frameType: Int, payload: ByteArray) {
        val frame = PacketCodec.encodeRfcommFrame(frameType, payload)
        writeExecutor.execute {
            synchronized(writeLock) {
                val os = outStream ?: return@execute
                try {
                    os.write(frame)
                    os.flush()
                } catch (_: IOException) {
                    cleanupSocket()
                }
            }
        }
    }
}
