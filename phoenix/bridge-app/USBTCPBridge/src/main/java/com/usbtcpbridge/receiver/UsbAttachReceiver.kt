package com.usbtcpbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.usbtcpbridge.service.BridgeService

/**
 * UsbAttachReceiver receives USB_DEVICE_ATTACHED broadcasts and
 * auto-starts the bridge service when an Arduino is connected.
 */
class UsbAttachReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbAttachReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED != intent.action) {
            return
        }

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        if (device == null) {
            Log.w(TAG, "USB device attached but no device in intent")
            return
        }

        Log.i(TAG, "USB device attached: ${device.deviceName} " +
                "(VID=${device.vendorId}, PID=${device.productId})")

        // Read preferences for port and baud rate
        val prefs = context.getSharedPreferences("USBTCPBridge", Context.MODE_PRIVATE)
        val port = prefs.getInt("pref_port", BridgeService.DEFAULT_TCP_PORT)
        val baudRate = prefs.getInt("pref_baud", BridgeService.DEFAULT_BAUD_RATE)

        // Start the bridge service
        val serviceIntent = Intent(context, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_TCP_PORT, port)
            putExtra(BridgeService.EXTRA_BAUD_RATE, baudRate)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Bridge service started for USB device")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bridge service", e)
        }
    }
}
