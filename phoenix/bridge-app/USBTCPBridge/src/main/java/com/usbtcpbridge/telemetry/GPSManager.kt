package com.usbtcpbridge.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.ActivityCompat
import com.usbtcpbridge.tcp.TCPManager
import java.util.Locale

/**
 * GPSManager handles Location data stream.
 * Uses a dedicated HandlerThread and LocationListener.
 * Maintains its own TCP Server.
 */
class GPSManager(
    private val context: Context
) : TCPManager.TCPListener, LocationListener {

    companion object {
        private const val TAG = "GPSManager"
        private const val HANDLER_THREAD_NAME = "GPSThread"
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    // Dedicated thread
    private var handlerThread: HandlerThread? = null
    
    // TCP Server
    private val tcpManager = TCPManager(this)
    private var isStreaming = false

    // Pre-allocated buffer
    private val stringBuilder = StringBuilder(64)

    fun start(port: Int) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission missing")
            return
        }

        Log.i(TAG, "Starting GPS on port $port")

        // Start dedicated thread
        handlerThread = HandlerThread(HANDLER_THREAD_NAME).apply {
            start()
        }

        // Request updates on dedicated thread looper
        // Request both GPS and Network providers for robustness
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1 second
                    0f,
                    this,
                    handlerThread!!.looper
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L, // 1 second
                    0f,
                    this,
                    handlerThread!!.looper
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception demanding location updates", e)
            return
        }

        // Start TCP Server
        tcpManager.start(port)
        isStreaming = true
    }

    fun stop() {
        Log.i(TAG, "Stopping GPS")
        isStreaming = false
        
        // Unregister listener
        locationManager.removeUpdates(this)

        // Stop TCP
        tcpManager.stop()

        // Stop thread
        handlerThread?.quitSafely()
        handlerThread = null
    }

    // LocationListener implementation

    override fun onLocationChanged(location: Location) {
        if (!isStreaming) return

        // Zero-allocation formatting
        stringBuilder.setLength(0)
        stringBuilder.append("GPS: ")
        stringBuilder.append(String.format(Locale.US, "%.6f", location.latitude))
        stringBuilder.append(", ")
        stringBuilder.append(String.format(Locale.US, "%.6f", location.longitude))
        stringBuilder.append(", ")
        stringBuilder.append(String.format(Locale.US, "%.1f", location.altitude)) // Altitude
        stringBuilder.append(", ")
        stringBuilder.append(String.format(Locale.US, "%.1f", location.speed))    // Speed m/s
        stringBuilder.append("\n")

        // Send to TCP client
        if (tcpManager.isConnected) {
            tcpManager.write(stringBuilder.toString().toByteArray())
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // TCPManager.TCPListener implementation

    override fun onClientConnected(clientAddress: String) {
        Log.i(TAG, "GPS Client connected: $clientAddress")
    }

    override fun onClientDisconnected() {
        Log.i(TAG, "GPS Client disconnected")
    }

    override fun onTcpDataReceived(data: ByteArray) {
        // Ignore incoming
    }

    override fun onTcpError(error: String) {
        Log.e(TAG, "GPS TCP Error: $error")
    }

    override fun onServerStarted(port: Int) {
        Log.i(TAG, "GPS Server started on port $port")
    }

    override fun onServerStopped() {
        Log.i(TAG, "GPS Server stopped")
    }

    fun isRunning(): Boolean = isStreaming
    fun isConnected(): Boolean = tcpManager.isConnected
}
