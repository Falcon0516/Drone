package com.usbtcpbridge.telemetry

import android.util.Log
import com.usbtcpbridge.tcp.TCPManager
import java.util.Locale

/**
 * UltrasonicManager handles Ultrasonic sensor data stream.
 * Receives parsed US: data from the USB serial bridge and
 * streams it to a connected TCP client.
 * Maintains its own TCP Server.
 */
class UltrasonicManager : TCPManager.TCPListener {

    companion object {
        private const val TAG = "UltrasonicManager"
    }

    // TCP Server
    private val tcpManager = TCPManager(this)
    private var isStreaming = false

    // Latest ultrasonic values (cm)
    @Volatile var distL: Int = 0
    @Volatile var distM: Int = 0
    @Volatile var distR: Int = 0

    // Pre-allocated buffer
    private val stringBuilder = StringBuilder(64)

    fun start(port: Int) {
        Log.i(TAG, "Starting Ultrasonic stream on port $port")
        tcpManager.start(port)
        isStreaming = true
    }

    fun stop() {
        Log.i(TAG, "Stopping Ultrasonic stream")
        isStreaming = false
        tcpManager.stop()
    }

    /**
     * Called by BridgeService when a US: line is parsed from USB serial.
     * Format: "US:L,M,R"
     */
    fun onUltrasonicData(line: String) {
        if (!isStreaming) return

        try {
            // Parse "US:L,M,R"
            val values = line.substring(3).split(",")
            if (values.size >= 3) {
                distL = values[0].trim().toInt()
                distM = values[1].trim().toInt()
                distR = values[2].trim().toInt()

                // Format and send to TCP client
                stringBuilder.setLength(0)
                stringBuilder.append("US: ")
                stringBuilder.append(distL)
                stringBuilder.append(", ")
                stringBuilder.append(distM)
                stringBuilder.append(", ")
                stringBuilder.append(distR)
                stringBuilder.append("\n")

                if (tcpManager.isConnected) {
                    tcpManager.write(stringBuilder.toString().toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ultrasonic data: $line", e)
        }
    }

    // TCPManager.TCPListener implementation

    override fun onClientConnected(clientAddress: String) {
        Log.i(TAG, "Ultrasonic Client connected: $clientAddress")
    }

    override fun onClientDisconnected() {
        Log.i(TAG, "Ultrasonic Client disconnected")
    }

    override fun onTcpDataReceived(data: ByteArray) {
        // Ignore incoming data
    }

    override fun onTcpError(error: String) {
        Log.e(TAG, "Ultrasonic TCP Error: $error")
    }

    override fun onServerStarted(port: Int) {
        Log.i(TAG, "Ultrasonic Server started on port $port")
    }

    override fun onServerStopped() {
        Log.i(TAG, "Ultrasonic Server stopped")
    }

    fun isRunning(): Boolean = isStreaming
    fun isConnected(): Boolean = tcpManager.isConnected
}
