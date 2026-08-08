#!/usr/bin/env bash
# PHOENIX Gazebo 3D Simulation Launcher
set -e

# --- Clean up any leftover processes ---
pkill -9 -f arducopter 2>/dev/null || true
pkill -9 -f mavproxy 2>/dev/null || true
pkill -9 -f "gz sim" 2>/dev/null || true
sleep 1

export GZ_VERSION=harmonic

# Add the compiled ArduPilot Gazebo plugin
export GZ_SIM_SYSTEM_PLUGIN_PATH=$PWD/simulation/ardupilot_gazebo/build:${GZ_SIM_SYSTEM_PLUGIN_PATH}

# Add models and worlds
export GZ_SIM_RESOURCE_PATH=$PWD/simulation/ardupilot_gazebo/models:$PWD/simulation/ardupilot_gazebo/worlds:${GZ_SIM_RESOURCE_PATH}

# Add mavproxy to PATH
export PATH=$PATH:$HOME/.local/bin:/opt/homebrew/bin:/Library/Frameworks/Python.framework/Versions/3.12/bin

# Cleanup handler
cleanup() {
    echo ""
    echo "Shutting down simulation..."
    pkill -f arducopter 2>/dev/null || true
    pkill -f mavproxy 2>/dev/null || true
    pkill -f "gz sim" 2>/dev/null || true
    exit 0
}
trap cleanup SIGINT SIGTERM

# ============================================
# STEP 1: Start Gazebo FIRST (the ArduPilotPlugin will wait for ArduCopter)
# ============================================
echo "Starting Gazebo Harmonic with Iris Drone..."

# On macOS, server and GUI must be launched separately
gz sim -v4 -s -r iris_runway.sdf &
GZ_SERVER_PID=$!
sleep 3
gz sim -v4 -g &
GZ_GUI_PID=$!

echo "Waiting for Gazebo to initialize..."
sleep 5

# ============================================
# STEP 2: Start ArduCopter with JSON model (matches ardupilot_gazebo plugin protocol)
# ============================================
echo "Starting ArduCopter SITL..."
cd simulation/ardupilot/ArduCopter

# KEY FIX: Use JSON model, NOT gazebo-iris!
# The ardupilot_gazebo plugin for Gazebo Harmonic uses the JSON wire protocol
# (magic 18458), NOT the old binary Gazebo Classic protocol.
../build/sitl/bin/arducopter \
    --model JSON \
    --speedup 1 \
    --slave 0 \
    --sim-address 127.0.0.1 \
    -I0 \
    --defaults ../Tools/autotest/default_params/copter.parm,../Tools/autotest/default_params/gazebo-iris.parm \
    &
ARDUPILOT_PID=$!

echo "ArduCopter PID: $ARDUPILOT_PID"
sleep 3

# ============================================
# STEP 3: Start MAVProxy (connects to ArduCopter serial port)
# ============================================
echo ""
echo "============================================"
echo "  PHOENIX 3D Drone Simulation Ready!"
echo "  Type 'mode guided' then 'arm throttle'"
echo "  then 'takeoff 10' to fly!"
echo "============================================"
echo ""

mavproxy.py \
    --retries 10 \
    --out 127.0.0.1:14550 \
    --out 127.0.0.1:14551 \
    --master tcp:127.0.0.1:5760 \
    --sitl 127.0.0.1:5501 \
    --console

# When MAVProxy exits, kill everything
cleanup
