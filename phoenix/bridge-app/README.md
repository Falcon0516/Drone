# Android USB TCP Bridge Application

An Android application that acts as a robust, background-capable bridge between a USB Serial device (e.g., Arduino, Rover, Robot) and multiple TCP clients. It facilitates bi-directional communication, telemetry data routing, and ensures uninterrupted operation with CPU and WiFi wake-locks.

## Features

- **USB to TCP Data Bridging**: Seamlessly forwards data between a connected USB serial device and a TCP client.
- **Background Operation**: Runs as a Foreground Service, ensuring it doesn't get killed by the Android OS while in the background.
- **Wake-Locks**: Acquires CPU (`PARTIAL_WAKE_LOCK`) and High-Performance WiFi locks to maintain consistent data transfer even when the screen is off.
- **Auto-Started Servers**:
  - **TCP Bridge Server**: Configurable port (default `1234`).
  - **Telemetry Server**: Configurable port (default `1235`, auto-starts on `8001`).
  - **GPS Server**: Configurable port (default `1236`, auto-starts on `8002`).
  - **Ultrasonic Server**: Auto-starts on `8004`. Extracts lines prefixed with `US:` from the USB stream and routes them separately.
- **Safety Features**: Automatically sends an emergency stop command (`M:0,0\n`) to the USB device when the TCP client disconnects or the service is destroyed.
- **Real-time Logging**: In-app UI for monitoring connections, data flow, and error logs in real-time.

## Requirements

- Android 8.0 (API Level 26) or higher.
- A compatible USB OTG cable.
- A USB Serial device (e.g., Arduino, ESP32, FTDI, CH340, CP210x, Prolific).
- Permissions:
  - Location (Fine/Coarse) for GPS tracking.
  - Post Notifications (Android 13+) for foreground service status.

## Architecture

The core of the application revolves around the `BridgeService`, a foreground service that orchestrates various managers:
- **`TCPManager`**: Handles the primary TCP Server socket.
- **`USBManager`**: Manages USB serial connections, baud rates, and data reading/writing.
- **`DataBridge`**: Manages the bi-directional queues between USB and TCP.
- **`TelemetryManager` / `GPSManager` / `UltrasonicManager`**: Dedicated handlers for routing specific telemetry data streams over independent TCP sockets.

## Protocol Highlights

- **Emergency Stop**: The app sends `M:0,0\n` via USB if the primary TCP client disconnects. This is specifically useful for robotic applications (e.g., stopping motors).
- **Ultrasonic Data**: If the USB device sends data prefixed with `US:`, the app automatically filters this out of the main TCP stream and routes it to the Ultrasonic TCP server.

## Building and Running

1. Clone the repository:
   ```bash
   git clone https://github.com/Falcon0516/ANDROID_USB_TCP_BRIDGE_APPLICATION.git
   ```
2. Open the project in **Android Studio**.
3. Sync Gradle and build the project.
4. Run the app on a physical Android device (emulators will not support USB OTG serial connections easily).

## Usage

1. Connect your USB serial device to your Android phone via a USB OTG adapter.
2. Grant the required USB permissions when prompted.
3. In the app, configure your desired **TCP Port** and **Baud Rate**.
4. Enable the **Bridge**, **Telemetry**, or **GPS** switches as required.
5. Connect your TCP client(s) to the Android device's IP address on the specified ports.

## Dependencies

- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android): Used for robust USB serial communication.

## License

This project is licensed under the MIT License. See the LICENSE file for details.
