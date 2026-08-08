package com.usbtcpbridge.sensorfusion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the phone's independent GPS and IMU sensors for cross-checking against the drone.
 */
class PhoneSensorManager(
    private val context: Context,
    private val onStateUpdated: (lat: Double, lon: Double, alt: Double, pitch: Double, roll: Double, yaw: Double) -> Unit
) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "PhoneSensorManager"
    }

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    // State
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentAlt = 0.0
    
    // Fake offset for debug injection
    private var fakeLatOffset = 0.0
    private var fakeLonOffset = 0.0

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasAccel = false
    private var hasMag = false

    // Expose current location for AI detection tags
    fun getCurrentLat() = currentLat
    fun getCurrentLon() = currentLon

    fun start() {
        Log.i(TAG, "Starting Phone Sensor Manager")
        
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Register IMU
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager?.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }

        // Register GPS if we have permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 sec
                0f,    // 0 meters
                this,
                Looper.getMainLooper()
            )
        } else {
            Log.w(TAG, "Missing GPS permission, phone location will be 0.0")
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping Phone Sensor Manager")
        sensorManager?.unregisterListener(this)
        locationManager?.removeUpdates(this)
    }

    fun injectDebugFault() {
        // Offset by roughly 50 meters
        fakeLatOffset += 0.00045 
        fakeLonOffset += 0.00045
        Log.w(TAG, "DEBUG: Injected fake GPS fault. Offset: $fakeLatOffset")
        emitState()
    }

    // --- LocationListener ---
    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude + fakeLatOffset
        currentLon = location.longitude + fakeLonOffset
        currentAlt = location.altitude
        emitState()
    }
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- SensorEventListener ---
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            hasAccel = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            hasMag = true
        }
        
        if (hasAccel && hasMag) {
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // orientationAngles: [0] = yaw/azimuth, [1] = pitch, [2] = roll (in radians)
            emitState()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun emitState() {
        val yaw = Math.toDegrees(orientationAngles[0].toDouble())
        val pitch = Math.toDegrees(orientationAngles[1].toDouble())
        val roll = Math.toDegrees(orientationAngles[2].toDouble())
        
        if (currentLat != 0.0 && currentLon != 0.0) {
            onStateUpdated(currentLat, currentLon, currentAlt, pitch, roll, yaw)
        }
    }
}
