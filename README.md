# PHOENIX 🦅
**Autonomous Drone Survey & Trust Ledger System**

PHOENIX is an open-source, end-to-end system designed to combat environmental encroachment (specifically lake buffer zones in Bengaluru) using low-cost hardware and cryptographic verification.

By replacing expensive $1,000+ flight controllers with a $50 Android smartphone, PHOENIX democratizes aerial surveying while simultaneously making the resulting data legally defensible and tamper-proof.

## 🚀 Features
- **Phone-as-a-Flight-Controller**: Uses an Android app to bridge telemetry, run AI (MobileNet V1), and act as a redundant sensor node.
- **Mission Planner**: A web-based tool to draw polygons and auto-generate MAVLink lawnmower survey paths with calculated battery sorties.
- **Sensor Fusion & Fault Detection**: Cross-checks the phone's internal GPS and Accelerometer against the drone's flight controller to detect spoofing or sensor failure.
- **Trust Ledger**: Cryptographically hashes aerial measurements to detect tampering. Change one centimeter of a measurement, and the hash breaks.
- **Ground Station Dashboard**: A real-time WebSocket dashboard for monitoring telemetry, AI detections, and fault alerts.

---

## 💻 Installation & Setup

PHOENIX includes an automated startup kit for Windows, Mac, and Linux. 

### 🪟 Windows Setup (Recommended)
Because the 3D drone simulation (ArduPilot + Gazebo) requires Linux, we've provided scripts that automate setting up Windows Subsystem for Linux (WSL2).

1. Clone the repository:
   ```cmd
   git clone https://github.com/Falcon0516/Drone.git PHOENIX
   cd PHOENIX
   ```
2. Run the automated Windows installer (Right-click -> Run as Administrator is recommended):
   ```cmd
   setup_windows.bat
   ```
   *This will install Python dependencies, ADB, and optionally set up WSL2.*
3. If WSL2 was just installed, open your new **Ubuntu** terminal and run:
   ```bash
   cd /mnt/c/Users/YOUR_NAME/path/to/PHOENIX
   bash setup_wsl.sh
   ```
   *This clones and compiles ArduPilot and Gazebo (takes ~10 mins).*

### 🍎 Mac / 🐧 Linux Setup
1. Install Python dependencies:
   ```bash
   pip3 install websockets pymavlink MAVProxy
   ```
2. Run the WSL/Linux setup script to compile the simulation natively:
   ```bash
   bash setup_wsl.sh
   ```

---

## 🎮 Running the System

### 1. Start the 3D Simulation
In a Linux/WSL or Mac terminal:
```bash
./launch_3d_sim.sh
```
Wait for `MAV> STABILIZE>` to appear.

### 2. Start the Ground Station
**Windows Users:** Simply double-click `start_windows.bat`.

**Mac/Linux Users:**
```bash
cd phoenix/dashboard
python3 ground_station.py
```
Open **http://localhost:3000** in your browser.

### 3. Connect the Android App (SITL Mode)
1. Install `PHOENIX-BridgeApp-SITL.apk` on your Android phone.
2. Connect your phone via USB.
3. Run ADB port forwarding:
   ```bash
   adb reverse tcp:5760 tcp:5760
   adb forward tcp:8000 tcp:8000
   adb forward tcp:1235 tcp:1235
   adb forward tcp:1236 tcp:1236
   ```
4. Open the App, toggle **SITL Mode ON** (IP: 127.0.0.1, Port: 5760).
5. Toggle Bridge, Telemetry, GPS, and AI to ON.
6. Watch the live data appear on your Ground Station dashboard!

---

## 📂 Project Structure

- `setup_windows.bat` / `start_windows.bat` - Windows automated startup kit
- `setup_wsl.sh` / `launch_3d_sim.sh` - Linux simulation setup and launcher
- `phoenix/bridge-app/` - The Android Kotlin codebase
- `phoenix/dashboard/` - Python WebSocket server and HTML/JS frontend
- `phoenix/mission-planner/` - Polygon-to-waypoint mission generator
- `phoenix/trust-ledger/` - Cryptographic measurement verifier

---
*Built for the JALSAKSHI ("Water Witness") initiative.*
