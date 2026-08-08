package com.usbtcpbridge.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.usbtcpbridge.tcp.TCPManager
import java.util.Locale

/**
 * TelemetryManager handles Accelerometer data stream.
 * Uses a dedicated HandlerThread for sensor callbacks to avoid UI lag.
 * Maintains its own TCP Server.
 */
class TelemetryManager(
    private val context: Context
) : TCPManager.TCPListener, SensorEventListener {

    companion object {
        private const val TAG = "TelemetryManager"
        private const val HANDLER_THREAD_NAME = "TelemetryThread"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Store latest gyro values
    @Volatile private var gyroX = 0f
    @Volatile private var gyroY = 0f
    @Volatile private var gyroZ = 0f
    
    // Dedicated thread for sensor processing
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // TCP Server
    private val tcpManager = TCPManager(this)
    private var isStreaming = false

    // Pre-allocated buffer for zero-allocation formatting
    private val stringBuilder = StringBuilder(64)

    fun start(port: Int) {
        if (accelerometer == null) {
            Log.e(TAG, "No accelerometer found")
        }
        if (gyroscope == null) {
            Log.e(TAG, "No gyroscope found")
        }

        if (accelerometer == null && gyroscope == null) return

        Log.i(TAG, "Starting Telemetry on port $port")

        // Start dedicated thread
        handlerThread = HandlerThread(HANDLER_THREAD_NAME).apply {
            start()
            sensorHandler = Handler(looper)
        }

        // Register sensor listener on dedicated thread
        // Register sensor listeners on dedicated thread
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST,
                sensorHandler
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST,
                sensorHandler
            )
        }

        // Start TCP Server
        tcpManager.start(port)
        isStreaming = true
    }

    fun stop() {
        Log.i(TAG, "Stopping Telemetry")
        isStreaming = false
        
        // Unregister sensor first
        sensorManager.unregisterListener(this)

        // Stop TCP
        tcpManager.stop()

        // Stop thread
        handlerThread?.quitSafely()
        handlerThread = null
        sensorHandler = null
    }

    // SensorEventListener implementation

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isStreaming || event == null) return

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyroX = event.values[0]
            gyroY = event.values[1]
            gyroZ = event.values[2]
            return // Don't send data on Gyro update, wait for Accel
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Zero-allocation formatting (reuse StringBuilder)
            stringBuilder.setLength(0)
            stringBuilder.append("ACCEL: ")
            stringBuilder.append(String.format(Locale.US, "%.3f", event.values[0]))
            stringBuilder.append(", ")
            stringBuilder.append(String.format(Locale.US, "%.3f", event.values[1]))
            stringBuilder.append(", ")
            stringBuilder.append(String.format(Locale.US, "%.3f", event.values[2]))
            
            // Append Gyro data
            stringBuilder.append(" Gyro: ")
            stringBuilder.append(String.format(Locale.US, "%.3f", gyroX))
            stringBuilder.append(", ")
            stringBuilder.append(String.format(Locale.US, "%.3f", gyroY))
            stringBuilder.append(", ")
            stringBuilder.append(String.format(Locale.US, "%.3f", gyroZ))
            stringBuilder.append("\n")

            // Send to TCP client (if connected)
            // Convert to bytes only here (small allocation unavoidable for write)
            if (tcpManager.isConnected) {
                tcpManager.write(stringBuilder.toString().toByteArray())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    // TCPManager.TCPListener implementation
    // We only send data, but we must implement the interface

    override fun onClientConnected(clientAddress: String) {
        Log.i(TAG, "Telemetry Client connected: $clientAddress")
    }

    override fun onClientDisconnected() {
        Log.i(TAG, "Telemetry Client disconnected")
    }

    override fun onTcpDataReceived(data: ByteArray) {
        // We ignore incoming data for telemetry
    }

    override fun onTcpError(error: String) {
        Log.e(TAG, "Telemetry TCP Error: $error")
    }

    override fun onServerStarted(port: Int) {
        Log.i(TAG, "Telemetry Server started on port $port")
    }

    override fun onServerStopped() {
        Log.i(TAG, "Telemetry Server stopped")
    }
    
    fun isRunning(): Boolean = isStreaming
    fun isConnected(): Boolean = tcpManager.isConnected
}
