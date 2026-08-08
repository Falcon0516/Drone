#!/usr/bin/env python3
"""
PHOENIX Ground Station Server
Receives telemetry from the Android bridge app over TCP,
parses MAVLink + FAULT: + AI: messages, and broadcasts
parsed JSON over WebSocket to the dashboard.
"""

import asyncio
import json
import threading
import time
import struct
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

# pip install websockets pymavlink
import websockets
from pymavlink import mavutil

# Configuration
TCP_LISTEN_PORT = 8000       # Bridge app connects here
WS_PORT = 8765               # Dashboard connects here  
HTTP_PORT = 3000              # Serves the dashboard HTML
DASHBOARD_DIR = Path(__file__).parent

# Global state
connected_ws_clients = set()
latest_telemetry = {
    "lat": 0.0, "lon": 0.0, "alt": 0.0,
    "heading": 0.0, "pitch": 0.0, "roll": 0.0,
    "battery": -1, "groundspeed": 0.0,
    "connected": False
}
fault_history = []
detection_history = []


async def broadcast(message: dict):
    """Send a JSON message to all connected WebSocket clients."""
    if connected_ws_clients:
        data = json.dumps(message)
        await asyncio.gather(
            *[client.send(data) for client in connected_ws_clients],
            return_exceptions=True
        )


async def ws_handler(websocket):
    """Handle a new WebSocket connection from the dashboard."""
    connected_ws_clients.add(websocket)
    print(f"[WS] Dashboard connected ({len(connected_ws_clients)} total)")
    try:
        # Send current state immediately
        await websocket.send(json.dumps({
            "type": "init",
            "telemetry": latest_telemetry,
            "faults": fault_history[-50:],
            "detections": detection_history[-50:]
        }))
        async for _ in websocket:
            pass  # We don't expect messages from the dashboard
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_ws_clients.discard(websocket)
        print(f"[WS] Dashboard disconnected ({len(connected_ws_clients)} total)")


class MavlinkStreamParser:
    """Parses a mixed stream of MAVLink binary + text prefix messages."""

    def __init__(self, loop: asyncio.AbstractEventLoop):
        self.loop = loop
        self.mav = mavutil.mavlink.MAVLink(None)
        self.mav.robust_parsing = True
        self.text_buffer = b""

    def feed(self, data: bytes):
        """Feed raw bytes from TCP and dispatch parsed messages."""
        # Split text-prefixed lines (FAULT:, AI:) from binary MAVLink
        self.text_buffer += data

        # Extract complete text lines first
        while b"\n" in self.text_buffer:
            line, self.text_buffer = self.text_buffer.split(b"\n", 1)
            text = line.decode("utf-8", errors="replace").strip()
            if text.startswith("FAULT:"):
                self._handle_fault(text)
            elif text.startswith("AI:"):
                self._handle_ai(text)
            # else: might be partial MAVLink, ignore text lines

        # Try to parse MAVLink from accumulated binary
        try:
            msgs = self.mav.parse_buffer(data)
            if msgs:
                for msg in msgs:
                    self._handle_mavlink(msg)
        except Exception:
            pass  # Malformed packets are expected in a noisy stream

    def _handle_mavlink(self, msg):
        msg_type = msg.get_type()

        if msg_type == "GLOBAL_POSITION_INT":
            latest_telemetry["lat"] = msg.lat / 1e7
            latest_telemetry["lon"] = msg.lon / 1e7
            latest_telemetry["alt"] = msg.relative_alt / 1000.0
            latest_telemetry["heading"] = msg.hdg / 100.0
            latest_telemetry["connected"] = True
            asyncio.run_coroutine_threadsafe(
                broadcast({"type": "telemetry", "data": dict(latest_telemetry)}),
                self.loop
            )

        elif msg_type == "ATTITUDE":
            latest_telemetry["pitch"] = round(msg.pitch * 57.2958, 1)
            latest_telemetry["roll"] = round(msg.roll * 57.2958, 1)

        elif msg_type == "VFR_HUD":
            latest_telemetry["groundspeed"] = round(msg.groundspeed, 1)

        elif msg_type == "SYS_STATUS":
            latest_telemetry["battery"] = msg.battery_remaining
            asyncio.run_coroutine_threadsafe(
                broadcast({"type": "telemetry", "data": dict(latest_telemetry)}),
                self.loop
            )

    def _handle_fault(self, text: str):
        """Parse: FAULT:GPS_MISMATCH|dist=12.5m|time=123456789"""
        parts = text[len("FAULT:"):].split("|")
        fault = {
            "type": parts[0] if parts else "UNKNOWN",
            "details": {p.split("=")[0]: p.split("=")[1] for p in parts[1:] if "=" in p},
            "timestamp": time.time()
        }
        fault_history.append(fault)
        if len(fault_history) > 200:
            fault_history.pop(0)
        print(f"[FAULT] {fault}")
        asyncio.run_coroutine_threadsafe(
            broadcast({"type": "fault", "data": fault}),
            self.loop
        )

    def _handle_ai(self, text: str):
        """Parse: AI:car|conf=0.85|bbox=[120,40,300,200]|lat=12.9|lon=77.5"""
        parts = text[len("AI:"):].split("|")
        detection = {
            "label": parts[0] if parts else "unknown",
            "details": {p.split("=")[0]: p.split("=")[1] for p in parts[1:] if "=" in p},
            "timestamp": time.time()
        }
        detection_history.append(detection)
        if len(detection_history) > 200:
            detection_history.pop(0)
        print(f"[AI] {detection}")
        asyncio.run_coroutine_threadsafe(
            broadcast({"type": "detection", "data": detection}),
            self.loop
        )


def tcp_server_thread(loop: asyncio.AbstractEventLoop):
    """TCP server that accepts a single connection from the bridge app."""
    import socket
    parser = MavlinkStreamParser(loop)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", TCP_LISTEN_PORT))
    srv.listen(1)
    print(f"[TCP] Listening on port {TCP_LISTEN_PORT} for bridge app...")

    while True:
        conn, addr = srv.accept()
        print(f"[TCP] Bridge connected from {addr}")
        latest_telemetry["connected"] = True
        asyncio.run_coroutine_threadsafe(
            broadcast({"type": "status", "connected": True}),
            loop
        )
        try:
            while True:
                data = conn.recv(4096)
                if not data:
                    break
                parser.feed(data)
        except Exception as e:
            print(f"[TCP] Connection error: {e}")
        finally:
            conn.close()
            latest_telemetry["connected"] = False
            asyncio.run_coroutine_threadsafe(
                broadcast({"type": "status", "connected": False}),
                loop
            )
            print(f"[TCP] Bridge disconnected")


def http_server_thread():
    """Serves the dashboard static files."""
    import os
    os.chdir(DASHBOARD_DIR)
    handler = SimpleHTTPRequestHandler
    httpd = HTTPServer(("0.0.0.0", HTTP_PORT), handler)
    print(f"[HTTP] Dashboard at http://localhost:{HTTP_PORT}/")
    httpd.serve_forever()


async def main():
    loop = asyncio.get_event_loop()

    # Start TCP listener in a background thread
    tcp_thread = threading.Thread(target=tcp_server_thread, args=(loop,), daemon=True)
    tcp_thread.start()

    # Start HTTP server in a background thread
    http_thread = threading.Thread(target=http_server_thread, daemon=True)
    http_thread.start()

    # Start WebSocket server
    print(f"[WS] WebSocket server on port {WS_PORT}")
    async with websockets.serve(ws_handler, "0.0.0.0", WS_PORT):
        await asyncio.Future()  # Run forever


if __name__ == "__main__":
    print("=" * 50)
    print("  PHOENIX Ground Station")
    print("=" * 50)
    print(f"  TCP listener (bridge) : port {TCP_LISTEN_PORT}")
    print(f"  WebSocket (dashboard) : port {WS_PORT}")
    print(f"  HTTP (dashboard UI)   : port {HTTP_PORT}")
    print("=" * 50)
    asyncio.run(main())
