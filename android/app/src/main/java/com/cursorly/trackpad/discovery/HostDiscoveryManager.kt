package com.cursorly.trackpad.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.cursorly.trackpad.model.DiscoveredHost
import com.cursorly.trackpad.model.HostEndpoint
import com.cursorly.trackpad.model.TransportKind
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

class HostDiscoveryManager(private val context: Context) {

    companion object {
        const val DISCOVERY_PORT = 41234
        private val DISCOVER_MAGIC = "CURSORLY_DISCOVER_V1".toByteArray(Charsets.UTF_8)
        private const val CLIENT_HELLO_PREFIX = "CURSORLY_CLIENT_HELLO_V1:"
        private const val NSD_TYPE = "_cursorly._tcp."

        fun hasBluetoothPermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostMap = LinkedHashMap<String, DiscoveredHost>()

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _usbConnectedWithoutTethering = MutableStateFlow(false)
    val usbConnectedWithoutTethering: StateFlow<Boolean> = _usbConnectedWithoutTethering.asStateFlow()

    private val _usbTetheringActive = MutableStateFlow(false)
    val usbTetheringActive: StateFlow<Boolean> = _usbTetheringActive.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var probeJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        refreshUsbAndBluetooth()
        startUdpDiscovery()
        startNsdDiscovery()
        registerNetworkCallbacks()
    }

    fun stop() {
        running = false
        probeJob?.cancel()
        listenJob?.cancel()
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        stopNsdDiscovery()
        unregisterNetworkCallbacks()
        releaseMulticastLock()
    }

    fun triggerImmediateProbe() {
        scope.launch {
            refreshUsbAndBluetooth()
            sendProbeBurst()
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshUsbAndBluetooth() {
        refreshUsbState()
        refreshPairedBluetoothDevices()
        pruneStaleNetworkHosts()
        publishHosts()
    }

    private fun refreshUsbState() {
        var usbPlugged = false
        var rndisActive = false
        try {
            val intent = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
            if (intent != null) {
                usbPlugged = intent.getBooleanExtra("connected", false)
                rndisActive = intent.getBooleanExtra("rndis", false)
            }
        } catch (_: Exception) {
        }

        // Also inspect actual Linux network interfaces for rndis0 / usb0
        if (!rndisActive) {
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    val name = iface.name.lowercase()
                    if (iface.isUp && !iface.isLoopback && (name.contains("rndis") || name.contains("usb"))) {
                        rndisActive = true
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        _usbTetheringActive.value = rndisActive
        _usbConnectedWithoutTethering.value = usbPlugged && !rndisActive
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedBluetoothDevices() {
        if (!hasBluetoothPermissions(context)) return
        try {
            val btMgr = context.getSystemService(BluetoothManager::class.java)
            val adapter = btMgr?.adapter ?: return
            val bonded: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
            synchronized(hostMap) {
                for (dev in bonded) {
                    val mac = dev.address?.uppercase() ?: continue
                    val name = dev.name ?: "Bluetooth PC"
                    val existingNet = hostMap.values.firstOrNull { !it.pairedBtOnly && it.btMac.equals(mac, ignoreCase = true) }
                    if (existingNet == null) {
                        val key = "bt:$mac"
                        if (!hostMap.containsKey(key)) {
                            hostMap[key] = DiscoveredHost(
                                id = key,
                                hostName = name,
                                btMac = mac,
                                pairedBtOnly = true,
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startUdpDiscovery() {
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            udpSocket = sock
        } catch (_: Exception) {
            // Port 41234 already bound; fall back to ephemeral socket for active probing
            try {
                udpSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1000
                }
            } catch (_: Exception) {
                return
            }
        }

        listenJob = scope.launch {
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            while (isActive && running) {
                val sock = udpSocket ?: break
                try {
                    sock.receive(pkt)
                    val msg = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    if (msg.startsWith("{")) {
                        handleAnnouncementJson(msg, pkt.address.hostAddress)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout loop check
                } catch (_: Exception) {
                    if (!running) break
                    delay(250)
                }
            }
        }

        probeJob = scope.launch {
            while (isActive && running) {
                refreshUsbState()
                sendProbeBurst()
                pruneStaleNetworkHosts()
                publishHosts()
                delay(2500)
            }
        }
    }

    private fun sendProbeBurst() {
        val sock = udpSocket ?: return
        val targets = linkedSetOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }

        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    val inet = addr.address
                    if (inet !is Inet4Address) continue
                    addr.broadcast?.let { targets.add(it) }
                    // On USB RNDIS or phone hotspot, also probe .1 and common host offsets on /24
                    val ifaceName = iface.name.lowercase()
                    if (ifaceName.contains("rndis") || ifaceName.contains("usb") || ifaceName.contains("ap")) {
                        val octets = inet.address
                        if (octets.size == 4) {
                            octets[3] = 1
                            try {
                                targets.add(InetAddress.getByAddress(octets))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val helloPayload = (
            CLIENT_HELLO_PREFIX +
                JSONObject().apply {
                    put("device_id", "${Build.MANUFACTURER}-${Build.MODEL}")
                    put("device_name", Build.MODEL ?: "Android")
                    put("platform", "android")
                }.toString()
            ).toByteArray(Charsets.UTF_8)

        for (target in targets) {
            try {
                sock.send(DatagramPacket(DISCOVER_MAGIC, DISCOVER_MAGIC.size, target, DISCOVERY_PORT))
                sock.send(DatagramPacket(helloPayload, helloPayload.size, target, DISCOVERY_PORT))
            } catch (_: Exception) {
            }
        }
    }

    private fun handleAnnouncementJson(rawJson: String, sourceIp: String?) {
        try {
            val obj = JSONObject(rawJson)
            if (obj.optString("service") != "cursorly") return
            val hostName = obj.optString("host", sourceIp ?: "Windows PC")
            val wsPort = obj.optInt("ws_port", 8080)
            val btMac = obj.optString("bt_mac", "").ifBlank { null }
            val btChannel = if (obj.has("bt_channel") && !obj.isNull("bt_channel")) obj.optInt("bt_channel") else null
            val btUuid = obj.optString("bt_uuid", "7a9d3c10-8f4b-4e2a-9c1d-5b6e7f8a9012")

            val endpoints = mutableListOf<HostEndpoint>()
            val ipsArr = obj.optJSONArray("ips")
            if (ipsArr != null) {
                for (i in 0 until ipsArr.length()) {
                    val item = ipsArr.getJSONObject(i)
                    val ip = item.optString("ip", "")
                    if (ip.isBlank()) continue
                    val kind = when (item.optString("kind").lowercase()) {
                        "usb" -> TransportKind.USB_TETHER
                        "hotspot" -> TransportKind.HOTSPOT
                        else -> TransportKind.WIFI
                    }
                    val adapterName = item.optString("name", kind.label)
                    endpoints.add(HostEndpoint(ip = ip, kind = kind, adapterName = adapterName))
                }
            }

            if (sourceIp != null && endpoints.none { it.ip == sourceIp }) {
                endpoints.add(HostEndpoint(ip = sourceIp, kind = TransportKind.WIFI, adapterName = "LAN"))
            }

            // Sort endpoints so sourceIp / USB / Hotspot rank highest
            val sortedEndpoints = endpoints.sortedBy { ep ->
                when {
                    ep.kind == TransportKind.USB_TETHER -> 0
                    ep.ip == sourceIp -> 1
                    ep.kind == TransportKind.HOTSPOT -> 2
                    else -> 3
                }
            }

            val hostId = "net:${hostName.lowercase()}"
            synchronized(hostMap) {
                // Remove duplicate paired-BT-only entry if we now have full network+BT info
                if (btMac != null) {
                    hostMap.remove("bt:${btMac.uppercase()}")
                }
                hostMap[hostId] = DiscoveredHost(
                    id = hostId,
                    hostName = hostName,
                    endpoints = sortedEndpoints,
                    wsPort = wsPort,
                    btMac = btMac,
                    btChannel = btChannel,
                    btUuid = btUuid,
                    lastSeenMs = System.currentTimeMillis(),
                    pairedBtOnly = false,
                )
            }
            publishHosts()
        } catch (_: Exception) {
        }
    }

    private fun pruneStaleNetworkHosts() {
        val now = System.currentTimeMillis()
        var prunedAny = false
        synchronized(hostMap) {
            val iter = hostMap.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (!entry.value.pairedBtOnly && now - entry.value.lastSeenMs > 12_000L) {
                    iter.remove()
                    prunedAny = true
                }
            }
        }
        if (prunedAny) {
            refreshPairedBluetoothDevices()
        }
    }

    private fun publishHosts() {
        synchronized(hostMap) {
            _hosts.value = hostMap.values.sortedWith(
                compareBy<DiscoveredHost> { it.pairedBtOnly }.thenBy { it.hostName.lowercase() }
            )
        }
    }

    private fun startNsdDiscovery() {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = mgr
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("cursorly")) {
                        resolveNsdService(mgr, service)
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdListener = listener
            mgr.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun resolveNsdService(mgr: NsdManager, service: NsdServiceInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        val ip = serviceInfo.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                            ?: serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                            ?: return
                        val port = serviceInfo.port.takeIf { it > 0 } ?: 8080
                        val name = serviceInfo.serviceName ?: "Cursorly PC"
                        recordNsdHost(name, ip, port)
                        try {
                            mgr.unregisterServiceInfoCallback(this)
                        } catch (_: Exception) {
                        }
                    }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                mgr.registerServiceInfoCallback(service, executor, cb)
            } else {
                @Suppress("DEPRECATION")
                mgr.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val ip = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port.takeIf { it > 0 } ?: 8080
                        val name = serviceInfo.serviceName ?: "Cursorly PC"
                        recordNsdHost(name, ip, port)
                    }
                })
            }
        } catch (_: Exception) {
        }
    }

    private fun recordNsdHost(name: String, ip: String, port: Int) {
        val hostId = "net:${name.lowercase()}"
        synchronized(hostMap) {
            if (!hostMap.containsKey(hostId)) {
                hostMap[hostId] = DiscoveredHost(
                    id = hostId,
                    hostName = name,
                    endpoints = listOf(HostEndpoint(ip, TransportKind.WIFI, "mDNS")),
                    wsPort = port,
                )
            }
        }
        publishHosts()
    }

    private fun stopNsdDiscovery() {
        try {
            val listener = nsdListener
            if (listener != null) {
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (_: Exception) {
        }
        nsdListener = null
    }

    private fun registerNetworkCallbacks() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    triggerImmediateProbe()
                }
                override fun onLost(network: Network) {
                    triggerImmediateProbe()
                }
            }
            networkCallback = cb
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallbacks() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("cursorly_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    fun openTetheringSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
