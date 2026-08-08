package com.usbtcpbridge.sensorfusion

import android.util.Log
import kotlin.math.*

/**
 * Calculates differences between Phone Ground-Truth and Flight Controller Telemetry.
 * Emits a string FAULT message if thresholds are exceeded.
 */
class FaultDetector(
    private val onFaultDetected: (message: String) -> Unit
) {
    companion object {
        private const val TAG = "FaultDetector"
        
        // Thresholds
        private const val MAX_POS_DIFF_METERS = 5.0
        private const val MAX_ATTITUDE_DIFF_DEG = 10.0
        
        // Rate limiting for fault messages (e.g. max 1 per second)
        private const val FAULT_COOLDOWN_MS = 1000L
    }

    private var phoneLat = 0.0
    private var phoneLon = 0.0
    private var phonePitch = 0.0
    private var phoneRoll = 0.0
    
    private var fcLat = 0.0
    private var fcLon = 0.0
    private var fcPitch = 0.0
    private var fcRoll = 0.0
    
    private var lastFaultTime = 0L

    fun updatePhoneState(lat: Double, lon: Double, alt: Double, pitch: Double, roll: Double, yaw: Double) {
        phoneLat = lat
        phoneLon = lon
        phonePitch = pitch
        phoneRoll = roll
        checkFaults()
    }

    fun updateFcState(lat: Double, lon: Double, alt: Double, pitch: Double, roll: Double, yaw: Double) {
        fcLat = lat
        fcLon = lon
        fcPitch = pitch
        fcRoll = roll
        checkFaults()
    }

    private fun checkFaults() {
        // Ensure we have valid data from both sides before checking
        if (phoneLat == 0.0 || fcLat == 0.0) return
        
        val now = System.currentTimeMillis()
        if (now - lastFaultTime < FAULT_COOLDOWN_MS) return

        // 1. Position Check (Haversine)
        val posDiff = calculateDistanceMeters(phoneLat, phoneLon, fcLat, fcLon)
        if (posDiff > MAX_POS_DIFF_METERS) {
            emitFault("GPS_MISMATCH", "dist=${"%.1f".format(posDiff)}m")
            return
        }

        // 2. Attitude Check (Pitch / Roll)
        // Note: Magnetometer (yaw) is excluded here intentionally due to EMI from motors making it an unreliable primary signal.
        val pitchDiff = abs(phonePitch - fcPitch)
        val rollDiff = abs(phoneRoll - fcRoll)
        
        // Handle wrap-around for degrees if necessary (pitch/roll usually don't wrap in standard representations, but good practice)
        val normPitchDiff = if (pitchDiff > 180) 360 - pitchDiff else pitchDiff
        val normRollDiff = if (rollDiff > 180) 360 - rollDiff else rollDiff

        if (normPitchDiff > MAX_ATTITUDE_DIFF_DEG || normRollDiff > MAX_ATTITUDE_DIFF_DEG) {
            val maxAtt = max(normPitchDiff, normRollDiff)
            emitFault("ATTITUDE_MISMATCH", "delta=${"%.1f".format(maxAtt)}deg")
            return
        }
    }

    private fun emitFault(type: String, detail: String) {
        lastFaultTime = System.currentTimeMillis()
        val msg = "FAULT:$type|$detail|time=$lastFaultTime\n"
        Log.w(TAG, msg.trim())
        onFaultDetected(msg)
    }

    // Haversine formula
    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // Earth radius in meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }
}
