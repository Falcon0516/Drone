# PHOENIX

> Project workspace for the PHOENIX system. This README tracks which components
> are reused from existing repositories and which are newly built.

---

## Reused As-Is

> Components taken directly from existing repos without modification.

- `bridge-app/` — ANDROID_USB_TCP_BRIDGE_APPLICATION (Foundation)
- Tailscale for mesh networking

---

## Reused & Extended

> Components adapted from existing repos with modifications for PHOENIX.

- `bridge-app/USBTCPBridge` — Extended with Tailscale TCP client tunneling, MAVLink parsing (`io.dronefleet.mavlink`), physics-based sensor fusion (GPS/IMU cross-check), and TFLite (SSD MobileNet V1) AI Object Detection via CameraX.

---

## Newly Built

> Components built from scratch for PHOENIX.

- `dashboard/` — Ground Station Python WebSocket server and HTML/CSS/JS frontend with live Leaflet map, telemetry gauges, fault alerts, and AI detection feeds.
- `reconstruction/` — Certified 3D reconstruction pipeline using COLMAP and OpenCV ArUco for strict metric scale calibration and defensible margin-of-error measurements.
- `trust-ledger/` — A secure, append-only local ledger that cryptographically seals reconstruction measurements in a tamper-evident SHA-256 hash chain.
- `mission-planner/` — Web-based autonomous survey planner with polygon drawing, lawnmower path generation, battery-split sorties, and MAVLink waypoint export.
- `test_mavlink.py` — MAVLink SITL tester script.
