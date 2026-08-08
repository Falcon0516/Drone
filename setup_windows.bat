@echo off
REM ============================================================
REM  PHOENIX Drone System - Windows Setup Script
REM  This script installs all dependencies needed to run
REM  the PHOENIX Ground Station and Simulation on Windows.
REM ============================================================
title PHOENIX Setup
color 0A

echo.
echo  ============================================================
echo   PHOENIX - Autonomous Drone Survey System
echo   Windows Setup Script
echo  ============================================================
echo.

REM --- Check for Admin rights ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] This script works best with Administrator privileges.
    echo           Right-click and "Run as administrator" for WSL setup.
    echo.
)

REM ============================================================
REM  PHASE 1: Python Setup
REM ============================================================
echo [1/5] Checking Python installation...
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed or not in PATH.
    echo.
    echo  Please install Python 3.10+ from https://www.python.org/downloads/
    echo  IMPORTANT: Check "Add Python to PATH" during installation!
    echo.
    pause
    exit /b 1
)
python --version
echo [OK] Python found.
echo.

REM --- Install Python dependencies ---
echo [2/5] Installing Python dependencies for Ground Station...
pip install websockets pymavlink
if %errorlevel% neq 0 (
    echo [WARNING] pip install had issues. Trying with --user flag...
    pip install --user websockets pymavlink
)
echo [OK] Python dependencies installed.
echo.

REM ============================================================
REM  PHASE 2: Check for ADB (Android Debug Bridge)
REM ============================================================
echo [3/5] Checking for ADB (Android Debug Bridge)...
adb version >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] ADB not found. Installing via winget...
    winget install Google.PlatformTools >nul 2>&1
    if %errorlevel% neq 0 (
        echo [WARNING] Could not auto-install ADB.
        echo  Please download manually from:
        echo  https://developer.android.com/tools/releases/platform-tools
        echo  Extract to C:\platform-tools and add to PATH.
    ) else (
        echo [OK] ADB installed via winget.
    )
) else (
    adb version
    echo [OK] ADB found.
)
echo.

REM ============================================================
REM  PHASE 3: QGroundControl
REM ============================================================
echo [4/5] QGroundControl (Ground Control Station GUI)...
if exist "%USERPROFILE%\Desktop\QGroundControl.exe" (
    echo [OK] QGroundControl found on Desktop.
) else (
    echo [INFO] QGroundControl is recommended for visual drone control.
    echo  Download from: https://docs.qgroundcontrol.com/master/en/qgc-user-guide/getting_started/download_and_install.html
    echo  Or run: winget install QGroundControl.QGroundControl
    echo.
    choice /C YN /M "Attempt to install QGroundControl via winget? "
    if errorlevel 2 goto skipqgc
    winget install QGroundControl.QGroundControl >nul 2>&1
    echo [OK] QGroundControl install attempted.
)
:skipqgc
echo.

REM ============================================================
REM  PHASE 4: WSL2 Setup (for Simulation)
REM ============================================================
echo [5/5] WSL2 Setup (Required for ArduPilot Simulation)...
echo.
echo  The 3D drone simulation (ArduPilot SITL + Gazebo) requires Linux.
echo  On Windows, we use WSL2 (Windows Subsystem for Linux) to run it.
echo.

wsl --status >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] WSL2 is not installed.
    echo.
    choice /C YN /M "Install WSL2 now? (Requires admin + reboot) "
    if errorlevel 2 goto skipwsl
    echo.
    echo Installing WSL2 with Ubuntu...
    wsl --install -d Ubuntu
    echo.
    echo  ============================================================
    echo   WSL2 installation started!
    echo   Your computer will need to RESTART to complete setup.
    echo   After restart, Ubuntu will open and ask you to create
    echo   a username and password.
    echo.
    echo   Once inside Ubuntu, run:
    echo     cd /mnt/c/Users/%USERNAME%/path/to/PHOENIX
    echo     bash setup_wsl.sh
    echo  ============================================================
    pause
    exit /b 0
) else (
    echo [OK] WSL2 is already installed.
    echo.
    echo  To set up the simulation inside WSL, open Ubuntu and run:
    echo    cd /mnt/c/Users/%USERNAME%/path/to/PHOENIX
    echo    bash setup_wsl.sh
)
:skipwsl
echo.

REM ============================================================
REM  DONE
REM ============================================================
echo.
echo  ============================================================
echo   PHOENIX Setup Complete!
echo  ============================================================
echo.
echo  What you can do now:
echo.
echo  1. START GROUND STATION:
echo     Run: start_windows.bat
echo.
echo  2. START SIMULATION (in WSL Ubuntu terminal):
echo     cd /mnt/c/path/to/PHOENIX
echo     bash setup_wsl.sh   (first time only)
echo     bash launch_3d_sim.sh
echo.
echo  3. CONNECT ANDROID APP:
echo     Install PHOENIX APK on your phone
echo     Connect phone via USB
echo     Run: adb reverse tcp:5760 tcp:5760
echo     Run: adb forward tcp:8000 tcp:8000
echo.
echo  4. OPEN DASHBOARD:
echo     http://localhost:3000
echo.
echo  ============================================================
pause
