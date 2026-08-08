@echo off
REM ============================================================
REM  PHOENIX - Windows Quick Start Launcher
REM  Starts the Ground Station dashboard and sets up ADB.
REM ============================================================
title PHOENIX Ground Station
color 0B

echo.
echo  ============================================================
echo   PHOENIX - Ground Station Launcher
echo  ============================================================
echo.

REM --- Check Python ---
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python not found. Run setup_windows.bat first.
    pause
    exit /b 1
)

REM --- ADB Port Forwarding (if phone connected) ---
echo [1/2] Setting up ADB port forwarding...
adb devices >nul 2>&1
if %errorlevel% equ 0 (
    adb reverse tcp:5760 tcp:5760 >nul 2>&1
    adb forward tcp:8000 tcp:8000 >nul 2>&1
    adb forward tcp:1235 tcp:1235 >nul 2>&1
    adb forward tcp:1236 tcp:1236 >nul 2>&1
    echo [OK] ADB port forwarding configured.
) else (
    echo [INFO] No Android device detected. Skipping ADB setup.
    echo        Connect your phone and run this script again if needed.
)
echo.

REM --- Start Ground Station ---
echo [2/2] Starting PHOENIX Ground Station...
echo.
echo  Dashboard will be available at: http://localhost:3000
echo  Press Ctrl+C to stop the server.
echo.
echo  ============================================================
echo   To start the 3D SIMULATION, open WSL Ubuntu and run:
echo     cd /mnt/c/path/to/PHOENIX
echo     bash launch_3d_sim.sh
echo  ============================================================
echo.

cd phoenix\dashboard
python ground_station.py
pause
