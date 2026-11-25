package com.cursorly.trackpad.transport

import android.os.Build
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class WebSocketTransport : TrackpadTransport {

    private class TcpNoDelaySocketFactory : SocketFactory() {
        private val delegate = getDefault()

        private fun configure(socket: Socket): Socket {
            socket.tcpNoDelay = true
            return socket
        }

        override fun createSocket(): Socket = configure(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(address, port, localAddress, localPort))
    }

    private val client = OkHttpClient.Builder()
        .socketFactory(TcpNoDelaySocketFactory())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ConnectionStatus())
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _rttMs = MutableStateFlow<Int?>(null)
    override val rttMs: StateFlow<Int?> = _rttMs.asStateFlow()

    private val _remoteState = MutableStateFlow<HostStateSync?>(null)
    override val remoteState: StateFlow<HostStateSync?> = _remoteState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var currentTarget: ConnectionTarget? = null
    @Volatile
    private var manualDisconnect = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    override fun connect(target: ConnectionTarget) {
        val ip = target.ip ?: return
        manualDisconnect = false
        currentTarget = target
        reconnectJob?.cancel()
        openSocket(target, ip)
    }

    private fun openSocket(target: ConnectionTarget, ip: String) {
        webSocket?.close(1000, "Reconnect")
        _status.value = ConnectionStatus(
            state = ConnectionState.CONNECTING,
            target = target,
            detail = "Connecting via ${target.transportKind.label} ($ip)...",
        )

        val request = Request.Builder()
            .url("ws://$ip:${target.port}/ws")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@WebSocketTransport.webSocket) return
                _status.value = ConnectionStatus(
                    state = ConnectionState.CONNECTED,
                    target = target,
                    detail = "${target.hostName} · ${target.transportKind.label}",
                )
                webSocket.send(
                    PacketCodec.jsonClientHello(
                        deviceName = Build.MODEL ?: "Android",
                        transport = target.transportKind.label,
                    )
                )
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@WebSocketTransport.webSocket) return
                handleIncomingJson(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@WebSocketTransport.webSocket) return
                handleDisconnected("Disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@WebSocketTransport.webSocket) return
                handleDisconnected(t.message ?: "Connection failed")
            }
        })
        webSocket = socket
    }

    private fun handleIncomingJson(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("event")) {
                "pong" -> {
                    val sentAt = obj.optLong("t", 0L)
                    if (sentAt > 0L) {
                        val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(0)
                        _rttMs.value = rtt
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

    private fun handleDisconnected(reason: String) {
        pingJob?.cancel()
        _rttMs.value = null
        val target = currentTarget
        if (manualDisconnect || target == null) {
            _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
            return
        }
        _status.value = ConnectionStatus(ConnectionState.CONNECTING, target, "Reconnecting ($reason)...")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1500)
            if (!manualDisconnect && currentTarget === target) {
                target.ip?.let { openSocket(target, it) }
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !manualDisconnect) {
                webSocket?.send(PacketCodec.jsonPing(System.currentTimeMillis()))
                delay(2000)
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        currentTarget = null
        pingJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _rttMs.value = null
        _remoteState.value = null
        _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, null, "Not connected")
    }

    override fun sendTouch(eventType: Int, pointerId: Int, x: Float, y: Float, timestampSec: Double) {
        val bytes = PacketCodec.encodeTouchPacket(eventType, pointerId, x, y, timestampSec)
        webSocket?.send(bytes.toByteString())
    }

    override fun sendControl(jsonPayload: String) {
        webSocket?.send(jsonPayload)
    }
}
