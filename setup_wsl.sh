#!/usr/bin/env bash
# ============================================================
#  PHOENIX - WSL/Linux Simulation Setup Script
#  Run this inside WSL Ubuntu or any Debian-based Linux.
#  It clones, compiles, and configures ArduPilot SITL + Gazebo.
# ============================================================
set -e

echo ""
echo "============================================================"
echo " PHOENIX - Simulation Environment Setup (Linux/WSL)"
echo "============================================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIM_DIR="$SCRIPT_DIR/simulation"

# ============================================================
#  1. System Dependencies
# ============================================================
echo "[1/6] Installing system dependencies..."
sudo apt-get update
sudo apt-get install -y \
    git cmake build-essential g++ \
    python3 python3-pip python3-dev python3-venv \
    python3-future python3-lxml python3-matplotlib \
    python3-numpy python3-opencv python3-pexpect \
    python3-scipy python3-serial python3-wxgtk4.0 \
    libxml2-dev libxslt1-dev \
    curl wget unzip \
    lsb-release gnupg

echo "[OK] System dependencies installed."
echo ""

# ============================================================
#  2. Gazebo Harmonic
# ============================================================
echo "[2/6] Installing Gazebo Harmonic..."
if command -v gz &> /dev/null; then
    echo "[OK] Gazebo already installed: $(gz sim --version 2>/dev/null || echo 'found')"
else
    # Add OSRF repository
    sudo curl https://packages.osrfoundation.org/gazebo.gpg \
        --output /usr/share/keyrings/pkgs-osrf-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/pkgs-osrf-archive-keyring.gpg] http://packages.osrfoundation.org/gazebo/ubuntu-stable $(lsb_release -cs) main" \
        | sudo tee /etc/apt/sources.list.d/gazebo-stable.list > /dev/null
    sudo apt-get update
    sudo apt-get install -y gz-harmonic
    echo "[OK] Gazebo Harmonic installed."
fi
echo ""

# ============================================================
#  3. Clone ArduPilot
# ============================================================
echo "[3/6] Setting up ArduPilot..."
mkdir -p "$SIM_DIR"

if [ -d "$SIM_DIR/ardupilot" ]; then
    echo "[OK] ArduPilot directory already exists. Skipping clone."
else
    cd "$SIM_DIR"
    git clone --recurse-submodules https://github.com/ArduPilot/ardupilot.git
    echo "[OK] ArduPilot cloned."
fi

# Install ArduPilot Python dependencies
cd "$SIM_DIR/ardupilot"
pip3 install --user empy==3.3.4 pexpect future pymavlink mavproxy dronekit
echo "[OK] ArduPilot Python dependencies installed."
echo ""

# ============================================================
#  4. Build ArduPilot SITL
# ============================================================
echo "[4/6] Building ArduPilot SITL (this may take 5-10 minutes)..."
cd "$SIM_DIR/ardupilot"

# Configure for SITL
./waf configure --board sitl
./waf copter

echo "[OK] ArduPilot SITL built successfully."
echo ""

# ============================================================
#  5. Clone & Build ardupilot_gazebo plugin
# ============================================================
echo "[5/6] Setting up ArduPilot Gazebo plugin..."

if [ -d "$SIM_DIR/ardupilot_gazebo" ]; then
    echo "[OK] ardupilot_gazebo directory already exists. Skipping clone."
else
    cd "$SIM_DIR"
    git clone https://github.com/ArduPilot/ardupilot_gazebo.git
    echo "[OK] ardupilot_gazebo cloned."
fi

cd "$SIM_DIR/ardupilot_gazebo"
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=RelWithDebInfo
make -j$(nproc)

echo "[OK] ArduPilot Gazebo plugin built."
echo ""

# ============================================================
#  6. Install MAVProxy
# ============================================================
echo "[6/6] Installing MAVProxy..."
pip3 install --user MAVProxy
echo "[OK] MAVProxy installed."
echo ""

# ============================================================
#  Add to PATH
# ============================================================
MAVPROXY_PATH="$HOME/.local/bin"
if ! echo "$PATH" | grep -q "$MAVPROXY_PATH"; then
    echo "export PATH=\$PATH:$MAVPROXY_PATH" >> ~/.bashrc
    echo "[OK] Added $MAVPROXY_PATH to PATH in ~/.bashrc"
fi

# ============================================================
#  Done
# ============================================================
echo ""
echo "============================================================"
echo " PHOENIX Simulation Setup Complete!"
echo "============================================================"
echo ""
echo " To start the simulation, run:"
echo "   cd $SCRIPT_DIR"
echo "   bash launch_3d_sim.sh"
echo ""
echo " Then open QGroundControl on Windows to connect and fly!"
echo "============================================================"
