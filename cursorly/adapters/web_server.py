"""
High-Frequency Web Trackpad Server (Zero-Install Browser Mode).

Uses FastAPI & uvicorn to host a high-performance HTTP + WebSocket trackpad.
Includes real-time sensitivity slider, preset chips, sniper mode, and dual Portrait/Landscape orientation toggle.
"""

import asyncio
import socket
import struct
import json
import threading
import time
from typing import Optional, Callable, Set

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
import uvicorn

# Binary packet layout:
# struct.pack("!BBffd", event_type, pointer_id, x, y, timestamp)
PACKET_FORMAT = "!BBffd"
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)

HTML_CLIENT = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Cursorly Trackpad</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; -webkit-user-select: none; }
        html, body {
            width: 100%; height: 100%; overflow: hidden; background: #0b0f19;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            color: #e2e8f0; touch-action: none;
        }
        #controls-bar {
            position: absolute; top: 0; left: 0; width: 100%; height: 56px;
            background: #111827; border-bottom: 1px solid #1e293b;
            display: flex; align-items: center; justify-content: space-between;
            padding: 0 10px; z-index: 20; gap: 6px;
        }
        .sens-group {
            display: flex; align-items: center; gap: 6px; flex: 1; min-width: 140px;
        }
        .sens-label {
            font-size: 11px; font-weight: 600; color: #38bdf8; min-width: 65px;
        }
        input[type=range] {
            flex: 1; accent-color: #38bdf8; height: 6px; border-radius: 3px;
        }
        .presets {
            display: flex; gap: 4px; align-items: center; flex-wrap: nowrap;
        }
        .chip {
            padding: 4px 7px; font-size: 11px; font-weight: 600; border-radius: 4px;
            background: #1e293b; color: #94a3b8; border: 1px solid #334155;
            cursor: pointer; transition: all 0.1s; white-space: nowrap;
        }
        .chip:active, .chip.active {
            background: #38bdf8; color: #0b0f19; border-color: #38bdf8;
        }
        .chip-orient {
            background: #1e1b4b; color: #a5b4fc; border-color: #6366f1;
        }
        .chip-orient.landscape {
            background: #6366f1; color: #ffffff;
        }
        .chip-sniper {
            background: #27272a; color: #f43f5e; border-color: #f43f5e;
        }
        .chip-sniper.active {
            background: #f43f5e; color: #fff;
        }
        #surface {
            position: absolute; top: 56px; left: 0; width: 100%; height: calc(84% - 56px);
            background: radial-gradient(circle at 50% 50%, #1e293b 0%, #0f172a 100%);
            display: flex; flex-direction: column; align-items: center; justify-content: center;
        }
        .guide { color: #64748b; font-size: 13px; pointer-events: none; text-align: center; line-height: 1.5; }
        .guide span { color: #38bdf8; font-weight: 600; }
        #status-bar {
            position: absolute; top: 66px; left: 16px; right: 16px;
            display: flex; justify-content: space-between; font-size: 12px; color: #94a3b8;
            pointer-events: none; z-index: 10;
        }
        .badge {
            display: inline-block; width: 8px; height: 8px; border-radius: 50%;
            background: #ef4444; margin-right: 6px; vertical-align: middle;
        }
        #buttons {
            position: absolute; bottom: 0; left: 0; width: 100%; height: 16%;
            display: flex; border-top: 2px solid #334155; background: #111827;
        }
        .btn {
            flex: 1; display: flex; align-items: center; justify-content: center;
            font-size: 16px; font-weight: 600; color: #94a3b8;
            background: #1e293b; transition: background 0.08s;
        }
        .btn:active { background: #38bdf8; color: #0f172a; }
        .btn + .btn { border-left: 2px solid #334155; }
        .pointer-dot {
            position: absolute; width: 44px; height: 44px; border-radius: 50%;
            background: rgba(56, 189, 248, 0.35); border: 2px solid #38bdf8;
            transform: translate(-50%, -50%); pointer-events: none; display: none;
        }

        /* Landscape orientation styling */
        @media (orientation: landscape) {
            #controls-bar { height: 46px; }
            #surface { top: 46px; height: calc(86% - 46px); }
            #buttons { height: 14%; }
            #status-bar { top: 52px; }
            .guide p { font-size: 12px; }
        }
    </style>
</head>
<body>
    <div id="controls-bar">
        <div class="sens-group">
            <span class="sens-label" id="sens-label">Sens: 0.50x</span>
            <input type="range" id="sens-slider" min="0.05" max="1.50" step="0.05" value="0.50">
        </div>
        <div class="presets">
            <button class="chip chip-orient" id="btn-orient">📱 Portrait</button>
            <button class="chip" data-val="0.20">0.2x</button>
            <button class="chip active" data-val="0.50">0.5x</button>
            <button class="chip chip-sniper" id="btn-sniper">🎯 Sniper</button>
        </div>
    </div>

    <div id="status-bar">
        <div id="conn-status"><span class="badge" id="badge"></span>Connecting...</div>
        <div id="hz-meter" style="color:#38bdf8; font-weight:600;">0 Hz</div>
    </div>

    <div id="surface">
        <div class="guide">
            <p style="font-size:16px; margin-bottom:4px; color:#f1f5f9; font-weight:600;">Cursorly</p>
            <p><span>1 Finger:</span> Move cursor &bull; Tap for Left Click</p>
            <p><span>2 Fingers:</span> Smooth 2D Scroll &bull; Tap for Right Click</p>
            <p><span>Double Tap &amp; Drag:</span> Select &amp; Move</p>
        </div>
        <div id="dot" class="pointer-dot"></div>
    </div>

    <div id="buttons">
        <div id="btn-left" class="btn">Left Click</div>
        <div id="btn-right" class="btn">Right Click</div>
    </div>

    <script>
        const surface = document.getElementById('surface');
        const badge = document.getElementById('badge');
        const connStatus = document.getElementById('conn-status');
        const hzMeter = document.getElementById('hz-meter');
        const dot = document.getElementById('dot');
        const btnLeft = document.getElementById('btn-left');
        const btnRight = document.getElementById('btn-right');
        const sensSlider = document.getElementById('sens-slider');
        const sensLabel = document.getElementById('sens-label');
        const btnSniper = document.getElementById('btn-sniper');
        const btnOrient = document.getElementById('btn-orient');
        const presetChips = document.querySelectorAll('.presets .chip[data-val]');

        let ws = null;
        let eventCount = 0;
        let lastHzTime = performance.now();
        let sniperActive = false;
        let isLandscape = false;
        const activePointers = new Set();

        function applyStateSync(msg) {
            if (typeof msg.sensitivity === 'number') {
                const val = msg.sensitivity.toFixed(2);
                sensSlider.value = val;
                sensLabel.textContent = `Sens: ${val}x`;
                presetChips.forEach(c => c.classList.toggle('active', c.dataset.val === val));
            }
            if (typeof msg.precision_mode === 'boolean') {
                sniperActive = msg.precision_mode;
                btnSniper.classList.toggle('active', sniperActive);
            }
            if (typeof msg.orientation === 'string') {
                isLandscape = msg.orientation.startsWith('landscape');
                btnOrient.classList.toggle('landscape', isLandscape);
                btnOrient.textContent = isLandscape ? '🔄 Landscape' : '📱 Portrait';
            }
        }

        function connect() {
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            ws = new WebSocket(`${proto}//${location.host}/ws`);
            ws.binaryType = 'arraybuffer';

            ws.onopen = () => {
                badge.style.background = '#22c55e';
                connStatus.innerHTML = '<span class="badge" style="background:#22c55e"></span>Connected (Unbuffered)';
            };

            ws.onmessage = (event) => {
                if (typeof event.data !== 'string') return;
                try {
                    const msg = JSON.parse(event.data);
                    if (msg.event === 'state_sync') {
                        applyStateSync(msg);
                    }
                } catch (_) {}
            };

            ws.onclose = () => {
                activePointers.clear();
                dot.style.display = 'none';
                badge.style.background = '#ef4444';
                connStatus.innerHTML = '<span class="badge" style="background:#ef4444"></span>Disconnected (Reconnecting...)';
                setTimeout(connect, 1000);
            };
        }
        connect();

        setInterval(() => {
            const now = performance.now();
            const hz = Math.round((eventCount * 1000) / (now - lastHzTime));
            hzMeter.textContent = `${hz} Hz`;
            eventCount = 0;
            lastHzTime = now;
        }, 1000);

        function updateSensitivity(val) {
            sensSlider.value = val;
            sensLabel.textContent = `Sens: ${parseFloat(val).toFixed(2)}x`;
            presetChips.forEach(c => c.classList.toggle('active', c.dataset.val === parseFloat(val).toFixed(2)));
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ action: 'set_sensitivity', value: parseFloat(val) }));
            }
        }

        sensSlider.addEventListener('input', (e) => updateSensitivity(e.target.value));

        presetChips.forEach(chip => {
            chip.addEventListener('click', () => {
                if (navigator.vibrate) navigator.vibrate(8);
                updateSensitivity(chip.dataset.val);
            });
        });

        btnSniper.addEventListener('click', () => {
            sniperActive = !sniperActive;
            btnSniper.classList.toggle('active', sniperActive);
            if (navigator.vibrate) navigator.vibrate(sniperActive ? [10, 30, 10] : 10);
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ action: 'toggle_precision' }));
            }
        });

        function setOrientation(landscape) {
            isLandscape = landscape;
            btnOrient.classList.toggle('landscape', isLandscape);
            btnOrient.textContent = isLandscape ? '🔄 Landscape' : '📱 Portrait';
            if (navigator.vibrate) navigator.vibrate(15);
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ action: 'set_orientation', orientation: isLandscape ? 'landscape' : 'portrait' }));
            }
        }

        btnOrient.addEventListener('click', () => {
            setOrientation(!isLandscape);
        });

        // Listen for screen orientation change if supported
        if (screen.orientation) {
            screen.orientation.addEventListener('change', () => {
                const autoLandscape = screen.orientation.type.startsWith('landscape');
                if (autoLandscape !== isLandscape) {
                    setOrientation(autoLandscape);
                }
            });
        }

        function sendPoint(type, id, x, y, t) {
            if (!ws || ws.readyState !== WebSocket.OPEN) return;
            const buf = new ArrayBuffer(18);
            const view = new DataView(buf);
            view.setUint8(0, type);
            view.setUint8(1, id & 0xFF);
            view.setFloat32(2, x, false);
            view.setFloat32(6, y, false);
            view.setFloat64(10, t / 1000.0, false);
            ws.send(buf);
            eventCount++;
        }

        function normalizeTimestampMs(ts) {
            return (typeof ts === 'number' && ts > 0 && ts < 1e11) ? ts : performance.now();
        }

        surface.addEventListener('pointerdown', (e) => {
            surface.setPointerCapture(e.pointerId);
            activePointers.add(e.pointerId);
            dot.style.display = 'block';
            dot.style.left = e.clientX + 'px';
            dot.style.top = e.clientY + 'px';
            sendPoint(0, e.pointerId, e.clientX, e.clientY, performance.now());
        });

        surface.addEventListener('pointermove', (e) => {
            if (!activePointers.has(e.pointerId)) return;
            dot.style.left = e.clientX + 'px';
            dot.style.top = e.clientY + 'px';

            if (e.getCoalescedEvents) {
                const events = e.getCoalescedEvents();
                for (const c of events) {
                    sendPoint(1, c.pointerId, c.clientX, c.clientY, normalizeTimestampMs(c.timeStamp));
                }
            } else {
                sendPoint(1, e.pointerId, e.clientX, e.clientY, normalizeTimestampMs(e.timeStamp));
            }
        });

        const onUp = (e) => {
            if (!activePointers.has(e.pointerId)) return;
            activePointers.delete(e.pointerId);
            if (activePointers.size === 0) {
                dot.style.display = 'none';
            }
            sendPoint(2, e.pointerId, e.clientX, e.clientY, performance.now());
        };
        surface.addEventListener('pointerup', onUp);
        surface.addEventListener('pointercancel', onUp);

        btnLeft.addEventListener('pointerdown', () => {
            if (navigator.vibrate) navigator.vibrate(12);
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ action: 'left_down' }));
        });
        const onLeftUp = () => {
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ action: 'left_up' }));
        };
        btnLeft.addEventListener('pointerup', onLeftUp);
        btnLeft.addEventListener('pointercancel', onLeftUp);
        btnRight.addEventListener('pointerdown', () => {
            if (navigator.vibrate) navigator.vibrate(20);
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ action: 'right_down' }));
        });
        const onRightUp = () => {
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ action: 'right_up' }));
        };
        btnRight.addEventListener('pointerup', onRightUp);
        btnRight.addEventListener('pointercancel', onRightUp);
    </script>
</body>
</html>
"""


class WebTrackpadServer:
    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8080,
        callback_engine=None,
        on_action: Optional[Callable[[str, tuple], None]] = None,
        on_client_change: Optional[Callable[[Optional[str]], None]] = None,
    ):
        self.host = host
        self.port = port
        self.callback_engine = callback_engine
        self.on_action = on_action
        self.on_client_change = on_client_change
        self._server: Optional[uvicorn.Server] = None
        self._thread: Optional[threading.Thread] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._clients: Set[WebSocket] = set()
        self._clients_lock = threading.Lock()

    def _current_state_payload(self) -> dict:
        if not self.callback_engine:
            return {"event": "state_sync"}
        b = self.callback_engine.ballistics
        return {
            "event": "state_sync",
            "sensitivity": round(b.sensitivity, 2),
            "precision_mode": bool(b.precision_mode),
            "orientation": self.callback_engine.orientation.value,
            "natural_scroll": bool(getattr(self.callback_engine, "natural_scroll", False)),
            "tap_to_click": bool(getattr(self.callback_engine, "tap_to_click", True)),
        }

    def broadcast_state(self) -> None:
        """Broadcast current sensitivity/precision/orientation to all connected WS clients."""
        loop = self._loop
        if not loop or loop.is_closed():
            return
        with self._clients_lock:
            clients = list(self._clients)
        if not clients:
            return
        msg = json.dumps(self._current_state_payload())

        async def _send_all():
            for ws in clients:
                try:
                    await ws.send_text(msg)
                except Exception:
                    pass

        try:
            asyncio.run_coroutine_threadsafe(_send_all(), loop)
        except Exception:
            pass

    @staticmethod
    def get_local_ip() -> str:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        except Exception:
            return "127.0.0.1"
        finally:
            s.close()

    def create_app(self) -> FastAPI:
        app = FastAPI(title="Cursorly Web Server")

        @app.get("/", response_class=HTMLResponse)
        async def index():
            return HTMLResponse(content=HTML_CLIENT)

        @app.get("/api/info")
        async def api_info():
            with self._clients_lock:
                client_count = len(self._clients)
            state = self._current_state_payload()
            state.pop("event", None)
            return JSONResponse(
                {
                    "service": "cursorly",
                    "version": 1,
                    "host": socket.gethostname(),
                    "ws_port": self.port,
                    "connected_clients": client_count,
                    **state,
                }
            )

        @app.websocket("/ws")
        async def websocket_endpoint(websocket: WebSocket):
            await websocket.accept()
            self._loop = asyncio.get_running_loop()
            with self._clients_lock:
                self._clients.add(websocket)
            client_label = f"Web/Wi-Fi ({websocket.client.host if websocket.client else 'unknown'})"
            if self.on_client_change:
                self.on_client_change(client_label)
            try:
                await websocket.send_text(json.dumps(self._current_state_payload()))
            except Exception:
                pass
            try:
                while True:
                    message = await websocket.receive()
                    if message.get("type") == "websocket.disconnect":
                        break
                    if "bytes" in message and message["bytes"]:
                        data = message["bytes"]
                        if len(data) >= PACKET_SIZE:
                            etype, pid, x, y, ts = struct.unpack(PACKET_FORMAT, data[:PACKET_SIZE])
                            if self.callback_engine:
                                if etype == 0:
                                    actions = self.callback_engine.on_touch_down(pid, x, y, ts)
                                elif etype == 1:
                                    actions = self.callback_engine.on_touch_move(pid, x, y, ts)
                                elif etype == 2:
                                    actions = self.callback_engine.on_touch_up(pid, x, y, ts)
                                else:
                                    actions = []

                                if self.on_action:
                                    for act, args in actions:
                                        self.on_action(act, args)

                    elif "text" in message and message["text"]:
                        try:
                            payload = json.loads(message["text"])
                            act = payload.get("action")
                            if act == "ping":
                                await websocket.send_text(json.dumps({"event": "pong", "t": payload.get("t")}))
                            elif act == "client_hello":
                                dev_name = payload.get("device_name", "Android")
                                transport = payload.get("transport", "wifi")
                                if self.on_client_change:
                                    self.on_client_change(f"{dev_name} ({transport})")
                            elif act == "set_sensitivity" and self.callback_engine:
                                val = float(payload.get("value", 0.5))
                                self.callback_engine.ballistics.set_sensitivity(val)
                                if self.on_action:
                                    self.on_action("sensitivity_changed", (val,))
                            elif act == "toggle_precision" and self.callback_engine:
                                is_prec = self.callback_engine.ballistics.toggle_precision_mode()
                                if self.on_action:
                                    self.on_action("precision_toggled", (is_prec,))
                            elif act == "set_orientation" and self.callback_engine:
                                o_val = payload.get("orientation", "portrait")
                                new_o = self.callback_engine.set_orientation(o_val)
                                if self.on_action:
                                    self.on_action("orientation_changed", (new_o,))
                            elif act == "toggle_orientation" and self.callback_engine:
                                new_o = self.callback_engine.toggle_orientation()
                                if self.on_action:
                                    self.on_action("orientation_toggled", (new_o,))
                            elif act == "set_natural_scroll" and self.callback_engine:
                                self.callback_engine.set_natural_scroll(bool(payload.get("enabled", False)))
                            elif act == "set_tap_to_click" and self.callback_engine:
                                self.callback_engine.set_tap_to_click(bool(payload.get("enabled", True)))
                            elif act == "key_text" and self.on_action:
                                self.on_action("key_text", (str(payload.get("text", "")),))
                            elif act == "key_press" and self.on_action:
                                self.on_action("key_press", (str(payload.get("key", "")),))
                            elif act == "zoom" and self.on_action:
                                self.on_action("zoom", (int(payload.get("delta", 0)),))
                            elif act and self.on_action:
                                self.on_action(act, ())
                        except Exception:
                            pass
            except (WebSocketDisconnect, RuntimeError):
                pass
            finally:
                with self._clients_lock:
                    self._clients.discard(websocket)
                    remaining = len(self._clients)
                if remaining == 0 and self.on_client_change:
                    self.on_client_change(None)

        return app

    def start(self):
        """Start the web server in a background daemon thread."""
        app = self.create_app()
        config = uvicorn.Config(app=app, host=self.host, port=self.port, log_level="warning")
        self._server = uvicorn.Server(config)

        self._thread = threading.Thread(target=self._server.run, daemon=True)
        self._thread.start()
        deadline = time.monotonic() + 1.5
        while time.monotonic() < deadline:
            if getattr(self._server, "started", False) or not self._thread.is_alive():
                break
            time.sleep(0.02)
        if not self._thread.is_alive() and not getattr(self._server, "started", False):
            print(f"[WebTrackpad] Error: failed to bind HTTP/WebSocket server on {self.host}:{self.port}")
            return
        local_ip = self.get_local_ip()
        print(f"[WebTrackpad] Web Trackpad ready at http://{local_ip}:{self.port}")

    def stop(self):
        if self._server:
            self._server.should_exit = True
        if self._thread:
            self._thread.join(timeout=1.0)
