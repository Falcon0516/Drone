package com.usbtcpbridge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * USBManager handles USB serial device detection, connection, and data I/O
 * using the usb-serial-for-android library.
 */
class USBManager(
    private val context: Context,
    private val listener: USBListener
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "USBManager"
        private const val ACTION_USB_PERMISSION = "com.usbtcpbridge.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_BUFFER_SIZE = 4096
    }

    interface USBListener {
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onUsbDataReceived(data: ByteArray)
        fun onUsbError(error: String)
        fun onPermissionRequired(device: UsbDevice)
    }

    private enum class PermissionState { Unknown, Requested, Granted, Denied }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val isConnected = AtomicBoolean(false)
    private val permissionState = AtomicReference(PermissionState.Unknown)

    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var currentDriver: UsbSerialDriver? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this@USBManager) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    
                    if (granted && device != null) {
                        permissionState.set(PermissionState.Granted)
                        Log.i(TAG, "USB permission granted for ${device.deviceName}")
                        connectToDevice(device)
                    } else {
                        permissionState.set(PermissionState.Denied)
                        Log.w(TAG, "USB permission denied")
                        listener.onUsbError("USB permission denied")
                    }
                }
            }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (device != null && currentDriver?.device?.deviceId == device.deviceId) {
                    Log.i(TAG, "USB device detached: ${device.deviceName}")
                    disconnect()
                }
            }
        }
    }

    private var receiversRegistered = false

    /**
     * Start USB manager and register receivers
     */
    fun start() {
        if (!receiversRegistered) {
            val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
            val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            
            ContextCompat.registerReceiver(
                context, permissionReceiver, permissionFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                context, disconnectReceiver, detachFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiversRegistered = true
        }
    }

    /**
     * Stop USB manager and unregister receivers
     */
    fun stop() {
        disconnect()
        
        if (receiversRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
                context.unregisterReceiver(disconnectReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered", e)
            }
            receiversRegistered = false
        }
    }

    /**
     * Find and connect to an available USB serial device
     */
    fun findAndConnect(baudRate: Int): Boolean {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            listener.onUsbError("No USB serial devices found")
            return false
        }

        val driver = availableDrivers[0]
        currentDriver = driver
        
        Log.i(TAG, "Found USB device: ${driver.device.deviceName}")

        // Check permission
        if (!usbManager.hasPermission(driver.device)) {
            Log.i(TAG, "Requesting USB permission...")
            permissionState.set(PermissionState.Requested)
            listener.onPermissionRequired(driver.device)
            requestPermission(driver.device)
            return false // Will connect after permission granted
        }

        return connectToDevice(driver.device, baudRate)
    }

    /**
     * Connect to a specific USB device
     */
    fun connectToDevice(device: UsbDevice, baudRate: Int = 9600): Boolean {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        
        if (driver == null) {
            Log.e(TAG, "No driver found for device ${device.deviceName}")
            listener.onUsbError("No driver found for device")
            return false
        }

        currentDriver = driver

        // Check permission
        if (!usbManager.hasPermission(device)) {
            if (permissionState.get() == PermissionState.Unknown) {
                Log.i(TAG, "Requesting USB permission...")
                permissionState.set(PermissionState.Requested)
                listener.onPermissionRequired(device)
                requestPermission(device)
            }
            return false
        }

        // Open connection
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB connection")
            listener.onUsbError("Failed to open USB connection")
            return false
        }

        usbConnection = connection

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            usbSerialPort = port

            // Start I/O manager for non-blocking reads
            val manager = SerialInputOutputManager(port, this)
            manager.readBufferSize = READ_BUFFER_SIZE
            manager.start()
            ioManager = manager

            isConnected.set(true)
            
            val deviceInfo = "${device.manufacturerName ?: "Unknown"} ${device.productName ?: device.deviceName}"
            Log.i(TAG, "Connected to USB device: $deviceInfo @ $baudRate baud")
            listener.onDeviceConnected(deviceInfo)
            
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open USB port", e)
            listener.onUsbError("Failed to open USB port: ${e.message}")
            disconnect()
            return false
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "Unsupported operation", e)
            listener.onUsbError("Unsupported operation: ${e.message}")
            disconnect()
            return false
        }
    }

    /**
     * Disconnect from the USB device
     */
    @Synchronized
    fun disconnect() {
        if (!isConnected.getAndSet(false) && usbSerialPort == null) {
            return
        }

        Log.i(TAG, "Disconnecting USB device...")

        ioManager?.let { manager ->
            manager.setListener(null)
            manager.stop()
        }
        ioManager = null

        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing USB port", e)
        }
        usbSerialPort = null

        usbConnection?.close()
        usbConnection = null

        currentDriver = null
        permissionState.set(PermissionState.Unknown)

        listener.onDeviceDisconnected()
        Log.i(TAG, "USB device disconnected")
    }

    /**
     * Write data to the USB serial port
     */
    fun write(data: ByteArray): Boolean {
        val port = usbSerialPort ?: return false
        
        return try {
            port.write(data, WRITE_TIMEOUT_MS)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to USB", e)
            listener.onUsbError("USB write error: ${e.message}")
            false
        }
    }

    /**
     * Check if connected to a USB device
     */
    fun isDeviceConnected(): Boolean = isConnected.get()

    /**
     * Request USB permission for a device
     */
    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    // SerialInputOutputManager.Listener callbacks
    
    override fun onNewData(data: ByteArray) {
        listener.onUsbDataReceived(data)
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "USB I/O error", e)
        if (isConnected.get()) {
            listener.onUsbError("USB I/O error: ${e.message}")
            disconnect()
        }
    }
}
