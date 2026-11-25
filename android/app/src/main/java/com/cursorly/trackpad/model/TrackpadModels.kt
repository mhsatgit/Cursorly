package com.cursorly.trackpad.model

enum class TransportKind(val label: String) {
    WIFI("Wi-Fi"),
    HOTSPOT("Hotspot"),
    USB_TETHER("USB"),
    BLUETOOTH_HOST("Bluetooth Host"),
    BLUETOOTH_HID("Bluetooth HID"),
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class TrackpadOrientation(val wireValue: String, val label: String) {
    PORTRAIT("portrait", "Portrait"),
    LANDSCAPE("landscape", "Landscape"),
}

data class HostEndpoint(
    val ip: String,
    val kind: TransportKind,
    val adapterName: String,
)

data class DiscoveredHost(
    val id: String,
    val hostName: String,
    val endpoints: List<HostEndpoint> = emptyList(),
    val wsPort: Int = 8080,
    val btMac: String? = null,
    val btChannel: Int? = null,
    val btUuid: String = "7a9d3c10-8f4b-4e2a-9c1d-5b6e7f8a9012",
    val lastSeenMs: Long = System.currentTimeMillis(),
    val pairedBtOnly: Boolean = false,
) {
    val primaryEndpoint: HostEndpoint?
        get() = endpoints.firstOrNull { it.kind == TransportKind.USB_TETHER }
            ?: endpoints.firstOrNull { it.kind == TransportKind.HOTSPOT }
            ?: endpoints.firstOrNull { it.kind == TransportKind.WIFI }
            ?: endpoints.firstOrNull()
}

data class ConnectionTarget(
    val hostName: String,
    val transportKind: TransportKind,
    val ip: String? = null,
    val port: Int = 8080,
    val btAddress: String? = null,
    val btChannel: Int? = null,
)

data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val target: ConnectionTarget? = null,
    val detail: String = "Not connected",
)

data class HostStateSync(
    val sensitivity: Float,
    val precisionMode: Boolean,
    val orientation: TrackpadOrientation,
    val naturalScroll: Boolean,
)

data class TrackpadSettings(
    val sensitivity: Float = 0.50f,
    val precisionMode: Boolean = false,
    val orientation: TrackpadOrientation = TrackpadOrientation.PORTRAIT,
    val naturalScroll: Boolean = false,
    val tapToClick: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoConnect: Boolean = true,
)
