"""
Automatic Multi-Interface Network Discovery for Cursorly.

Enables zero-configuration discovery across Wi-Fi, Mobile Hotspot, Ethernet,
and USB Tethering (RNDIS) without requiring any ADB commands or manual IP entry.
"""

import ctypes
from ctypes import wintypes
import ipaddress
import json
import socket
import sys
import threading
import time
from dataclasses import dataclass, asdict
from typing import Callable, Dict, List, Optional

DISCOVERY_PORT = 41234
DISCOVER_MAGIC = b"CURSORLY_DISCOVER_V1"
CLIENT_HELLO_PREFIX = b"CURSORLY_CLIENT_HELLO_V1:"
CURSORLY_BT_UUID = "7a9d3c10-8f4b-4e2a-9c1d-5b6e7f8a9012"


@dataclass
class InterfaceAddress:
    """Represents an active IPv4 network interface and its transport kind."""

    ip: str
    netmask: str
    broadcast: str
    kind: str  # "usb", "hotspot", "wifi", "ethernet", "other"
    name: str


def classify_interface(name: str, description: str, if_type: int = 0) -> str:
    """Classify a Windows network adapter by transport kind."""
    text = f"{name} {description}".lower()
    if any(k in text for k in ("rndis", "remote ndis", "usb", "tether", "android", "mobile broadband")):
        return "usb"
    if any(k in text for k in ("wi-fi direct", "hosted", "hotspot", "local area connection*")):
        return "hotspot"
    if if_type == 71 or any(k in text for k in ("wi-fi", "wifi", "wireless", "wlan", "802.11")):
        return "wifi"
    if if_type == 6 or "ethernet" in text:
        return "ethernet"
    return "other"


def _compute_netmask_and_broadcast(ip_str: str, prefix_len: int) -> tuple[str, str]:
    prefix_len = max(1, min(32, int(prefix_len)))
    net = ipaddress.IPv4Network(f"{ip_str}/{prefix_len}", strict=False)
    return str(net.netmask), str(net.broadcast_address)


def _get_ipv4_interfaces_windows() -> List[InterfaceAddress]:
    """Enumerate active IPv4 adapters via Win32 iphlpapi.GetAdaptersAddresses."""
    AF_INET = 2
    GAA_FLAG_SKIP_ANYCAST = 0x0002
    GAA_FLAG_SKIP_MULTICAST = 0x0004
    GAA_FLAG_SKIP_DNS_SERVER = 0x0008
    ERROR_BUFFER_OVERFLOW = 111
    NO_ERROR = 0
    IF_OPER_STATUS_UP = 1
    IF_TYPE_SOFTWARE_LOOPBACK = 24

    class SOCKADDR_IN(ctypes.Structure):
        _fields_ = [
            ("sin_family", ctypes.c_ushort),
            ("sin_port", ctypes.c_ushort),
            ("sin_addr", ctypes.c_ubyte * 4),
            ("sin_zero", ctypes.c_char * 8),
        ]

    class SOCKET_ADDRESS(ctypes.Structure):
        _fields_ = [
            ("lpSockaddr", ctypes.POINTER(SOCKADDR_IN)),
            ("iSockaddrLength", ctypes.c_int),
        ]

    class IP_ADAPTER_UNICAST_ADDRESS_LH(ctypes.Structure):
        pass

    IP_ADAPTER_UNICAST_ADDRESS_LH._fields_ = [
        ("Length", wintypes.ULONG),
        ("Flags", wintypes.DWORD),
        ("Next", ctypes.POINTER(IP_ADAPTER_UNICAST_ADDRESS_LH)),
        ("Address", SOCKET_ADDRESS),
        ("PrefixOrigin", ctypes.c_int),
        ("SuffixOrigin", ctypes.c_int),
        ("DadState", ctypes.c_int),
        ("ValidLifetime", wintypes.ULONG),
        ("PreferredLifetime", wintypes.ULONG),
        ("LeaseLifetime", wintypes.ULONG),
        ("OnLinkPrefixLength", ctypes.c_ubyte),
    ]

    class IP_ADAPTER_ADDRESSES_LH(ctypes.Structure):
        pass

    IP_ADAPTER_ADDRESSES_LH._fields_ = [
        ("Length", wintypes.ULONG),
        ("IfIndex", wintypes.DWORD),
        ("Next", ctypes.POINTER(IP_ADAPTER_ADDRESSES_LH)),
        ("AdapterName", ctypes.c_char_p),
        ("FirstUnicastAddress", ctypes.POINTER(IP_ADAPTER_UNICAST_ADDRESS_LH)),
        ("FirstAnycastAddress", ctypes.c_void_p),
        ("FirstMulticastAddress", ctypes.c_void_p),
        ("FirstDnsServerAddress", ctypes.c_void_p),
        ("DnsSuffix", ctypes.c_wchar_p),
        ("Description", ctypes.c_wchar_p),
        ("FriendlyName", ctypes.c_wchar_p),
        ("PhysicalAddress", ctypes.c_ubyte * 8),
        ("PhysicalAddressLength", wintypes.DWORD),
        ("Flags", wintypes.DWORD),
        ("Mtu", wintypes.DWORD),
        ("IfType", wintypes.DWORD),
        ("OperStatus", ctypes.c_int),
    ]

    iphlpapi = ctypes.windll.iphlpapi
    flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER
    size = wintypes.ULONG(16384)
    buf = ctypes.create_string_buffer(size.value)

    ret = iphlpapi.GetAdaptersAddresses(AF_INET, flags, None, ctypes.byref(buf), ctypes.byref(size))
    if ret == ERROR_BUFFER_OVERFLOW:
        buf = ctypes.create_string_buffer(size.value)
        ret = iphlpapi.GetAdaptersAddresses(AF_INET, flags, None, ctypes.byref(buf), ctypes.byref(size))

    if ret != NO_ERROR:
        return []

    results: List[InterfaceAddress] = []
    adapter_ptr = ctypes.cast(buf, ctypes.POINTER(IP_ADAPTER_ADDRESSES_LH))
    while adapter_ptr:
        adapter = adapter_ptr.contents
        if adapter.OperStatus == IF_OPER_STATUS_UP and adapter.IfType != IF_TYPE_SOFTWARE_LOOPBACK:
            friendly = adapter.FriendlyName or ""
            desc = adapter.Description or ""
            kind = classify_interface(friendly, desc, int(adapter.IfType))
            unicast_ptr = adapter.FirstUnicastAddress
            while unicast_ptr:
                unicast = unicast_ptr.contents
                sockaddr_ptr = unicast.Address.lpSockaddr
                if sockaddr_ptr and sockaddr_ptr.contents.sin_family == AF_INET:
                    raw_ip = bytes(sockaddr_ptr.contents.sin_addr)
                    ip_str = socket.inet_ntoa(raw_ip)
                    if not ip_str.startswith("127.") and not ip_str.startswith("169.254."):
                        prefix_len = int(unicast.OnLinkPrefixLength or 24)
                        netmask, broadcast = _compute_netmask_and_broadcast(ip_str, prefix_len)
                        results.append(
                            InterfaceAddress(
                                ip=ip_str,
                                netmask=netmask,
                                broadcast=broadcast,
                                kind=kind,
                                name=friendly or desc or kind,
                            )
                        )
                unicast_ptr = unicast.Next
        adapter_ptr = adapter.Next
    return results


def get_ipv4_interfaces() -> List[InterfaceAddress]:
    """Return active non-loopback IPv4 interfaces on this host."""
    if sys.platform == "win32":
        try:
            ifaces = _get_ipv4_interfaces_windows()
            if ifaces:
                return ifaces
        except Exception:
            pass

    # Fallback using standard socket resolution
    seen: set[str] = set()
    results: List[InterfaceAddress] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip_str = info[4][0]
            if ip_str not in seen and not ip_str.startswith("127.") and not ip_str.startswith("169.254."):
                seen.add(ip_str)
                netmask, broadcast = _compute_netmask_and_broadcast(ip_str, 24)
                results.append(InterfaceAddress(ip=ip_str, netmask=netmask, broadcast=broadcast, kind="wifi", name="Primary"))
    except Exception:
        pass
    return results


def get_local_bluetooth_mac() -> Optional[str]:
    """Query the primary Windows Bluetooth radio MAC address via bthprops.cpl."""
    if sys.platform != "win32":
        return None
    try:
        bthprops = ctypes.WinDLL("bthprops.cpl")
        kernel32 = ctypes.windll.kernel32

        class BLUETOOTH_FIND_RADIO_PARAMS(ctypes.Structure):
            _fields_ = [("dwSize", wintypes.DWORD)]

        class BLUETOOTH_RADIO_INFO(ctypes.Structure):
            _fields_ = [
                ("dwSize", wintypes.DWORD),
                ("_pad", wintypes.DWORD),  # Align 64-bit BTH_ADDR (ULONGLONG) to 8-byte boundary (sizeof == 520)
                ("address", ctypes.c_ubyte * 8),  # BLUETOOTH_ADDRESS (ULONGLONG little-endian 6 bytes)
                ("szName", ctypes.c_wchar * 248),
                ("ulClassofDevice", wintypes.ULONG),
                ("lmpSubversion", wintypes.USHORT),
                ("manufacturer", wintypes.USHORT),
            ]

        bthprops.BluetoothFindFirstRadio.argtypes = [
            ctypes.POINTER(BLUETOOTH_FIND_RADIO_PARAMS),
            ctypes.POINTER(wintypes.HANDLE),
        ]
        bthprops.BluetoothFindFirstRadio.restype = wintypes.HANDLE

        bthprops.BluetoothGetRadioInfo.argtypes = [
            wintypes.HANDLE,
            ctypes.POINTER(BLUETOOTH_RADIO_INFO),
        ]
        bthprops.BluetoothGetRadioInfo.restype = wintypes.DWORD

        bthprops.BluetoothFindRadioClose.argtypes = [wintypes.HANDLE]
        bthprops.BluetoothFindRadioClose.restype = wintypes.BOOL

        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel32.CloseHandle.restype = wintypes.BOOL

        params = BLUETOOTH_FIND_RADIO_PARAMS(dwSize=ctypes.sizeof(BLUETOOTH_FIND_RADIO_PARAMS))
        radio_handle = wintypes.HANDLE()
        find_handle = bthprops.BluetoothFindFirstRadio(ctypes.byref(params), ctypes.byref(radio_handle))
        if not find_handle:
            return None

        try:
            info = BLUETOOTH_RADIO_INFO(dwSize=ctypes.sizeof(BLUETOOTH_RADIO_INFO))
            res = bthprops.BluetoothGetRadioInfo(radio_handle, ctypes.byref(info))
            if res == 0:
                mac_bytes = list(info.address[:6])
                mac_bytes.reverse()
                return ":".join(f"{b:02X}" for b in mac_bytes)
        finally:
            if radio_handle:
                kernel32.CloseHandle(radio_handle)
            bthprops.BluetoothFindRadioClose(find_handle)
    except Exception:
        return None
    return None


def encode_discovery_announcement(
    host_name: str,
    ws_port: int,
    interfaces: List[InterfaceAddress],
    bt_mac: Optional[str] = None,
    bt_channel: Optional[int] = None,
) -> bytes:
    """Serialize host discovery announcement JSON bytes."""
    payload = {
        "service": "cursorly",
        "version": 1,
        "host": host_name,
        "ws_port": ws_port,
        "bt_mac": bt_mac,
        "bt_channel": bt_channel,
        "bt_uuid": CURSORLY_BT_UUID,
        "ips": [asdict(iface) for iface in interfaces],
    }
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


class DiscoveryServer:
    """UDP discovery responder and periodic beacon for zero-config Android pairing."""

    def __init__(
        self,
        ws_port: int = 8080,
        discovery_port: int = DISCOVERY_PORT,
        bt_channel: Optional[int] = None,
        on_device_seen: Optional[Callable[[dict], None]] = None,
        beacon_interval_sec: float = 2.0,
    ):
        self.ws_port = ws_port
        self.discovery_port = discovery_port
        self.bt_channel = bt_channel
        self.on_device_seen = on_device_seen
        self.beacon_interval_sec = beacon_interval_sec

        self._running = False
        self._sock: Optional[socket.socket] = None
        self._recv_thread: Optional[threading.Thread] = None
        self._beacon_thread: Optional[threading.Thread] = None
        self.seen_devices: Dict[str, dict] = {}

    def build_announcement(self) -> bytes:
        interfaces = get_ipv4_interfaces()
        bt_mac = get_local_bluetooth_mac()
        return encode_discovery_announcement(
            host_name=socket.gethostname(),
            ws_port=self.ws_port,
            interfaces=interfaces,
            bt_mac=bt_mac,
            bt_channel=self.bt_channel,
        )

    def start(self) -> bool:
        if self._running:
            return True
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(0.5)
            sock.bind(("0.0.0.0", self.discovery_port))
        except OSError as exc:
            sock.close()
            print(f"[Discovery] Warning: could not bind UDP port {self.discovery_port} ({exc}); auto-discovery disabled.")
            return False
        self._sock = sock
        self._running = True

        self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True)
        self._beacon_thread = threading.Thread(target=self._beacon_loop, daemon=True)
        self._recv_thread.start()
        self._beacon_thread.start()
        return True

    def stop(self) -> None:
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None
        if self._recv_thread:
            self._recv_thread.join(timeout=1.0)
        if self._beacon_thread:
            self._beacon_thread.join(timeout=1.0)

    def _recv_loop(self) -> None:
        while self._running and self._sock:
            try:
                data, addr = self._sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break

            if data.startswith(DISCOVER_MAGIC):
                try:
                    self._sock.sendto(self.build_announcement(), addr)
                except OSError:
                    pass
            elif data.startswith(CLIENT_HELLO_PREFIX):
                try:
                    raw_json = data[len(CLIENT_HELLO_PREFIX) :].decode("utf-8", errors="ignore")
                    info = json.loads(raw_json)
                    info["ip"] = addr[0]
                    info["last_seen"] = time.monotonic()
                    key = info.get("device_id") or addr[0]
                    is_new = key not in self.seen_devices
                    self.seen_devices[key] = info
                    # Immediately reply with host descriptor so client sees us too
                    self._sock.sendto(self.build_announcement(), addr)
                    if is_new and self.on_device_seen:
                        self.on_device_seen(info)
                except Exception:
                    pass

    def _beacon_loop(self) -> None:
        while self._running and self._sock:
            try:
                payload = self.build_announcement()
                targets = {"255.255.255.255"}
                for iface in get_ipv4_interfaces():
                    if iface.broadcast:
                        targets.add(iface.broadcast)
                for target in targets:
                    try:
                        self._sock.sendto(payload, (target, self.discovery_port))
                    except OSError:
                        pass
            except Exception:
                pass

            slept = 0.0
            while self._running and slept < self.beacon_interval_sec:
                time.sleep(0.25)
                slept += 0.25
