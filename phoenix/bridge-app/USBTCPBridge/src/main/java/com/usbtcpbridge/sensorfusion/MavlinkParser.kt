package com.usbtcpbridge.sensorfusion

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.GlobalPositionInt
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MavlinkParser runs in a background thread, reading from a piped stream that receives
 * raw USB bytes. It decodes MAVLink packets and extracts telemetry needed for sensor fusion.
 */
class MavlinkParser(
    private val onTelemetryReceived: (lat: Double, lon: Double, alt: Double, pitch: Double, roll: Double, yaw: Double) -> Unit
) {
    companion object {
        private const val TAG = "MavlinkParser"
    }

    private val isRunning = AtomicBoolean(false)
    private var parseThread: Thread? = null
    
    private val pipedInputStream = PipedInputStream(1024 * 64)
    private val pipedOutputStream = PipedOutputStream(pipedInputStream)
    
    private var latestLat = 0.0
    private var latestLon = 0.0
    private var latestAlt = 0.0
    
    private var latestPitch = 0.0
    private var latestRoll = 0.0
    private var latestYaw = 0.0

    fun start() {
        if (isRunning.getAndSet(true)) return
        
        parseThread = Thread {
            Log.i(TAG, "MAVLink parser thread started")
            val dummyOut = java.io.ByteArrayOutputStream()
            val connection = MavlinkConnection.builder(pipedInputStream, dummyOut).build()
            
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val message = connection.next() ?: continue
                    
                    when (val payload = message.payload) {
                        is GlobalPositionInt -> {
                            latestLat = payload.lat() / 1e7
                            latestLon = payload.lon() / 1e7
                            latestAlt = payload.relativeAlt() / 1000.0 // meters
                            emitTelemetry()
                        }
                        is Attitude -> {
                            latestPitch = Math.toDegrees(payload.pitch().toDouble())
                            latestRoll = Math.toDegrees(payload.roll().toDouble())
                            latestYaw = Math.toDegrees(payload.yaw().toDouble())
                            emitTelemetry()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "MAVLink parsing error", e)
                }
            }
            Log.i(TAG, "MAVLink parser thread stopped")
        }
        parseThread?.start()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        
        parseThread?.interrupt()
        try {
            pipedOutputStream.close()
            pipedInputStream.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    /**
     * Feed raw bytes from the USB stream into the MAVLink parser.
     */
    fun feedData(data: ByteArray) {
        if (!isRunning.get()) return
        try {
            pipedOutputStream.write(data)
            pipedOutputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to MAVLink pipe", e)
        }
    }
    
    private fun emitTelemetry() {
        if (latestLat != 0.0 && latestLon != 0.0) {
            onTelemetryReceived(latestLat, latestLon, latestAlt, latestPitch, latestRoll, latestYaw)
        }
    }
}
