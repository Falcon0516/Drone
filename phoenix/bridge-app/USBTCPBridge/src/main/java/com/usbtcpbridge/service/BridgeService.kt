package com.usbtcpbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.usbtcpbridge.MainActivity
import com.usbtcpbridge.R
import com.usbtcpbridge.bridge.DataBridge
import com.usbtcpbridge.tcp.TCPManager
import com.usbtcpbridge.tcp.TailscaleClientManager
import com.usbtcpbridge.usb.USBManager
import com.usbtcpbridge.telemetry.TelemetryManager
import com.usbtcpbridge.telemetry.GPSManager
import com.usbtcpbridge.telemetry.UltrasonicManager
import com.usbtcpbridge.sensorfusion.MavlinkParser
import com.usbtcpbridge.sensorfusion.PhoneSensorManager
import com.usbtcpbridge.sensorfusion.FaultDetector
import com.usbtcpbridge.ai.AIDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

import androidx.lifecycle.LifecycleService

/**
 * BridgeService is a foreground service that maintains the USB-TCP bridge.
 * It uses a WakeLock to prevent CPU sleep during data transfers.
 */
class BridgeService : LifecycleService(), 
    TCPManager.TCPListener, 
    USBManager.USBListener,
    com.usbtcpbridge.tcp.SITLManager.SITLListener,
    DataBridge.BridgeListener {

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "usb_tcp_bridge_channel"
        private const val WAKELOCK_TAG = "USBTCPBridge:BridgeWakeLock"
        private const val WIFILOCK_TAG = "USBTCPBridge:BridgeWifiLock"

        const val ACTION_START = "com.usbtcpbridge.action.START"
        const val ACTION_STOP = "com.usbtcpbridge.action.STOP"
        
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_TARGET_HOST = "target_host"
        const val EXTRA_BAUD_RATE = "baud_rate"
        const val EXTRA_SITL_MODE = "sitl_mode"
        const val EXTRA_SITL_IP = "sitl_ip"
        const val EXTRA_SITL_PORT = "sitl_port"
        
        const val BROADCAST_ACTION = "com.usbtcpbridge.BRIDGE_STATUS"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_LOG_TYPE = "log_type"
        const val EXTRA_USB_CONNECTED = "usb_connected"
        const val EXTRA_TCP_CONNECTED = "tcp_connected"
        const val EXTRA_TCP_LISTENING = "tcp_listening"
        const val EXTRA_SERVICE_RUNNING = "service_running"
        
        const val EXTRA_TELEMETRY_RUNNING = "telemetry_running"
        const val EXTRA_TELEMETRY_CONNECTED = "telemetry_connected"
        const val EXTRA_GPS_RUNNING = "gps_running"
        const val EXTRA_GPS_CONNECTED = "gps_connected"
        const val EXTRA_ULTRASONIC_RUNNING = "ultrasonic_running"
        const val EXTRA_ULTRASONIC_CONNECTED = "ultrasonic_connected"

        const val DEFAULT_TCP_PORT = 1234
        const val DEFAULT_BAUD_RATE = 9600
    }

    // Log types for UI coloring
    enum class LogType {
        INFO, SUCCESS, WARNING, ERROR, DATA_IN, DATA_OUT
    }

    // Binder for activity communication
    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()

    private lateinit var powerManager: PowerManager
    private lateinit var wifiManager: WifiManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null


    private var tcpManager: TailscaleClientManager? = null
    private var usbManager: USBManager? = null
    private var sitlManager: com.usbtcpbridge.tcp.SITLManager? = null
    private var dataBridge: DataBridge? = null
    
    private var telemetryManager: TelemetryManager? = null
    private var gpsManager: GPSManager? = null
    private var ultrasonicManager: UltrasonicManager? = null
    
    private var mavlinkParser: MavlinkParser? = null
    private var phoneSensorManager: PhoneSensorManager? = null
    private var faultDetector: FaultDetector? = null
    
    private var aiDetector: AIDetector? = null

    private var tcpPort = DEFAULT_TCP_PORT
    private var targetHost = "127.0.0.1"
    private var baudRate = DEFAULT_BAUD_RATE
    private var telemetryPort = 1235
    private var gpsPort = 1236
    private var ultrasonicPort = 1237

    private var isServiceRunning = false
    private var isUsbConnected = false
    private var isTcpClientConnected = false
    private var isTcpListening = false
    
    private var isSitlMode = false
    private var sitlIp = "127.0.0.1"
    private var sitlPort = 5760

    // Log history for UI
    private val logHistory = CopyOnWriteArrayList<Pair<String, LogType>>()
    private val maxLogHistory = 500
    
    // Rate limit for overflow logs
    private var lastOverflowLogTime: Long = 0

    // Status listeners
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    interface StatusListener {
        fun onStatusChanged(usbConnected: Boolean, tcpConnected: Boolean, tcpListening: Boolean)
        fun onLogMessage(message: String, type: LogType)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        
        telemetryManager = TelemetryManager(this)
        gpsManager = GPSManager(this)
        ultrasonicManager = UltrasonicManager()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)
                targetHost = intent.getStringExtra(EXTRA_TARGET_HOST) ?: "127.0.0.1"
                baudRate = intent.getIntExtra(EXTRA_BAUD_RATE, DEFAULT_BAUD_RATE)
                isSitlMode = intent.getBooleanExtra(EXTRA_SITL_MODE, false)
                sitlIp = intent.getStringExtra(EXTRA_SITL_IP) ?: "127.0.0.1"
                sitlPort = intent.getIntExtra(EXTRA_SITL_PORT, 5760)
                startBridge()
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
        }
        
        // Use REDELIVER_INTENT ensures service restarts with previous Intent if killed
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopBridge()
        super.onDestroy()
    }

    /**
     * Start the bridge (TCP server, USB connection, data bridge)
     */
    private fun startBridge() {
        if (isServiceRunning) {
            Log.w(TAG, "Bridge already running")
            return
        }

        Log.i(TAG, "Starting bridge on TCP port $tcpPort with baud rate $baudRate")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Acquire WakeLocks (CPU + WiFi)
        acquireWakeLocks()

        // Initialize managers
        tcpManager = TailscaleClientManager(this)
        if (isSitlMode) {
            sitlManager = com.usbtcpbridge.tcp.SITLManager(this)
        } else {
            usbManager = USBManager(this, this)
        }
        dataBridge = DataBridge(this)
        
        faultDetector = FaultDetector { faultMsg ->
            tcpManager?.write(faultMsg.toByteArray())
        }
        
        phoneSensorManager = PhoneSensorManager(this) { lat, lon, alt, pitch, roll, yaw ->
            faultDetector?.updatePhoneState(lat, lon, alt, pitch, roll, yaw)
        }
        
        mavlinkParser = MavlinkParser { lat, lon, alt, pitch, roll, yaw ->
            faultDetector?.updateFcState(lat, lon, alt, pitch, roll, yaw)
        }
        
        mavlinkParser?.start()
        phoneSensorManager?.start()

        // Start USB/SITL manager
        if (isSitlMode) {
            sitlManager?.start(sitlIp, sitlPort)
        } else {
            usbManager?.start()
        }

        // Start TCP server
        if (tcpManager?.start(targetHost, tcpPort) == true) {
            addLog(getString(R.string.log_tcp_server_started, tcpPort), LogType.SUCCESS)
        }

        // Start data bridge
        dataBridge?.start()

        // Try to connect to USB device
        if (!isSitlMode) {
            usbManager?.findAndConnect(baudRate)
        }

        isServiceRunning = true
        addLog(getString(R.string.log_service_started), LogType.INFO)
        broadcastStatus()
        updateNotification("Listening on port $tcpPort")
    }

    /**
     * Stop the bridge and cleanup resources
     */
    private fun stopBridge() {
        if (!isServiceRunning) {
            return
        }

        Log.i(TAG, "Stopping bridge...")

        isServiceRunning = false

        // Stop data bridge first
        dataBridge?.stop()
        dataBridge = null

        // Stop USB / SITL
        usbManager?.stop()
        usbManager = null
        sitlManager?.stop()
        sitlManager = null
        isUsbConnected = false

        // Stop TCP
        tcpManager?.stop()
        tcpManager = null

        mavlinkParser?.stop()
        mavlinkParser = null
        
        phoneSensorManager?.stop()
        phoneSensorManager = null
        
        faultDetector = null
        
        aiDetector?.stop()
        aiDetector = null
        
        isTcpClientConnected = false
        isTcpListening = false
        
        // Stop Telemetry
        telemetryManager?.stop()
        
        // Stop GPS
        gpsManager?.stop()
        
        // Stop Ultrasonic
        ultrasonicManager?.stop()

        // Release WakeLocks
        releaseWakeLocks()

        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        addLog(getString(R.string.log_service_stopped), LogType.INFO)
        broadcastStatus()
    }

    /**
     * Acquire WakeLocks to prevent sleep during transfers
     */
    private fun acquireWakeLocks() {
        // CPU Lock
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            )
        }
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire(10 * 60 * 60 * 1000L) // 10 hours max
                Log.i(TAG, "CPU WakeLock acquired")
            }
        }
        
        // WiFi Lock (High Perf)
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, 
                WIFILOCK_TAG
            )
        }
        wifiLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire()
                Log.i(TAG, "WiFi Lock acquired")
            }
        }
    }

    /**
     * Release the WakeLocks
     */
    private fun releaseWakeLocks() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "CPU WakeLock released")
            }
        }
        wakeLock = null

        wifiLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "WiFi Lock released")
            }
        }
        wifiLock = null
    }

    /**
     * Create the notification channel (Android O+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Create the foreground notification
     */
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BridgeService::class.java).apply {
            action = ACTION_STOP
        }
        
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Update the notification with new status
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(status))
    }

    /**
     * Add a log message
     */
    private fun addLog(message: String, type: LogType) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        
        Log.d(TAG, "Log: $logMessage")
        
        // Add to history
        logHistory.add(Pair(logMessage, type))
        while (logHistory.size > maxLogHistory) {
            logHistory.removeAt(0)
        }

        // Notify listeners
        statusListeners.forEach { it.onLogMessage(logMessage, type) }

        // Broadcast
        Intent(BROADCAST_ACTION).also { intent ->
            intent.setPackage(packageName)
            intent.putExtra(EXTRA_LOG_MESSAGE, logMessage)
            intent.putExtra(EXTRA_LOG_TYPE, type.ordinal)
            sendBroadcast(intent)
        }
    }

    /**
     * Broadcast current status to UI
     */
    private fun broadcastStatus() {
        statusListeners.forEach { 
            it.onStatusChanged(isUsbConnected, isTcpClientConnected, isTcpListening) 
        }

        Intent(BROADCAST_ACTION).also { intent ->
            intent.setPackage(packageName)
            intent.putExtra(EXTRA_USB_CONNECTED, isUsbConnected)
            intent.putExtra(EXTRA_TCP_CONNECTED, isTcpClientConnected)
            intent.putExtra(EXTRA_TCP_LISTENING, isTcpListening)
            intent.putExtra(EXTRA_TCP_LISTENING, isTcpListening)
            intent.putExtra(EXTRA_SERVICE_RUNNING, isServiceRunning)
            
            intent.putExtra(EXTRA_TELEMETRY_RUNNING, telemetryManager?.isRunning() == true)
            intent.putExtra(EXTRA_TELEMETRY_CONNECTED, telemetryManager?.isConnected() == true)
            intent.putExtra(EXTRA_GPS_RUNNING, gpsManager?.isRunning() == true)
            intent.putExtra(EXTRA_GPS_CONNECTED, gpsManager?.isConnected() == true)
            intent.putExtra(EXTRA_ULTRASONIC_RUNNING, ultrasonicManager?.isRunning() == true)
            intent.putExtra(EXTRA_ULTRASONIC_CONNECTED, ultrasonicManager?.isConnected() == true)
            
            sendBroadcast(intent)
        }
    }

    // Public methods for bound activity
    
    fun addStatusListener(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    fun getLogHistory(): List<Pair<String, LogType>> = logHistory.toList()

    fun isRunning(): Boolean = isServiceRunning

    fun isUsbDeviceConnected(): Boolean = isUsbConnected

    fun isTcpClientConnected(): Boolean = isTcpClientConnected

    fun getCurrentTcpPort(): Int = tcpPort
    
    fun getCurrentBaudRate(): Int = baudRate
    
    // Telemetry Controls
    
    fun startTelemetry(port: Int) {
        telemetryManager?.start(port)
        telemetryPort = port
        addLog("Telemetry started on port $port", LogType.INFO)
        broadcastStatus()
    }
    
    fun stopTelemetry() {
        telemetryManager?.stop()
        addLog("Telemetry stopped", LogType.INFO)
        broadcastStatus()
    }
    
    fun isTelemetryRunning(): Boolean = telemetryManager?.isRunning() == true
    
    fun isTelemetryConnected(): Boolean = telemetryManager?.isConnected() == true
    
    fun startGPS(port: Int) {
        gpsManager?.start(port)
        gpsPort = port
        addLog("GPS started on port $port", LogType.INFO)
        broadcastStatus()
    }
    
    fun stopGPS() {
        gpsManager?.stop()
        addLog("GPS stopped", LogType.INFO)
        broadcastStatus()
    }
    
    fun isGpsRunning(): Boolean = gpsManager?.isRunning() == true
    
    fun isGpsConnected(): Boolean = gpsManager?.isConnected() == true
    
    // Ultrasonic Controls
    
    fun startUltrasonic(port: Int) {
        ultrasonicManager?.start(port)
        ultrasonicPort = port
        addLog("Ultrasonic started on port $port", LogType.INFO)
        broadcastStatus()
    }
    
    fun stopUltrasonic() {
        ultrasonicManager?.stop()
        addLog("Ultrasonic stopped", LogType.INFO)
        broadcastStatus()
    }
    
    fun isUltrasonicRunning(): Boolean = ultrasonicManager?.isRunning() == true
    
    fun isUltrasonicConnected(): Boolean = ultrasonicManager?.isConnected() == true

    // TCPManager.TCPListener implementation

    override fun onClientConnected(clientAddress: String) {
        isTcpClientConnected = true
        addLog(getString(R.string.log_tcp_client_connected, clientAddress), LogType.SUCCESS)
        broadcastStatus()
        updateNotification("Client: $clientAddress")
    }

    override fun onClientDisconnected() {
        isTcpClientConnected = false
        addLog(getString(R.string.log_tcp_client_disconnected), LogType.WARNING)
        broadcastStatus()
        updateNotification("Listening on port $tcpPort")
    }

    override fun onTcpDataReceived(data: ByteArray) {
        // Data from TCP client -> queue for USB
        dataBridge?.queueTcpData(data)
    }

    override fun onTcpError(error: String) {
        addLog(getString(R.string.log_error, error), LogType.ERROR)
    }

    override fun onServerStarted(port: Int) {
        isTcpListening = true
        broadcastStatus()
    }

    override fun onServerStopped() {
        isTcpListening = false
        isTcpClientConnected = false
        addLog(getString(R.string.log_tcp_server_stopped), LogType.INFO)
        broadcastStatus()
    }

    // USBManager.USBListener implementation

    override fun onDeviceConnected(deviceName: String) {
        isUsbConnected = true
        addLog(getString(R.string.log_usb_connected, deviceName), LogType.SUCCESS)
        broadcastStatus()
    }

    override fun onDeviceDisconnected() {
        isUsbConnected = false
        addLog(getString(R.string.log_usb_disconnected), LogType.WARNING)
        broadcastStatus()
    }

    override fun onUsbDataReceived(data: ByteArray) {
        dataBridge?.queueUsbData(data)
        mavlinkParser?.feedData(data)
    }

    override fun onUsbError(error: String) {
        addLog(getString(R.string.log_error, error), LogType.ERROR)
    }

    override fun onPermissionRequired(device: UsbDevice) {
        addLog("USB permission required for ${device.deviceName}", LogType.WARNING)
    }

    // DataBridge.BridgeListener implementation

    override fun onUsbToTcpData(data: ByteArray): Boolean {
        return tcpManager?.write(data) ?: false
    }

    override fun onTcpToUsbData(data: ByteArray): Boolean {
        return if (isSitlMode) {
            sitlManager?.write(data) ?: false
        } else {
            usbManager?.write(data) ?: false
        }
    }

    override fun onUsbToTcpBytes(count: Long) {
        // Could update stats in notification if needed
    }

    override fun onTcpToUsbBytes(count: Long) {
        // Could update stats in notification if needed
    }

    override fun onError(direction: String, error: String) {
        addLog(getString(R.string.log_error, "$direction: $error"), LogType.ERROR)
    }

    override fun onBufferOverflow(direction: String) {
        val now = System.currentTimeMillis()
        if (now - lastOverflowLogTime > 1000) {
            addLog("Buffer overflow in $direction - data dropped", LogType.WARNING)
            lastOverflowLogTime = now
        }
    }

    // SITLManager.SITLListener implementation

    override fun onSitlConnected(address: String) {
        isUsbConnected = true // Mock USB connection so UI shows green
        addLog(getString(R.string.log_usb_connected, "SITL ($address)"), LogType.SUCCESS)
        broadcastStatus()
    }

    override fun onSitlDisconnected() {
        isUsbConnected = false
        addLog(getString(R.string.log_usb_disconnected) + " (SITL)", LogType.WARNING)
        broadcastStatus()
    }

    override fun onSitlDataReceived(data: ByteArray) {
        dataBridge?.queueUsbData(data) // Queue as if it came from USB
        mavlinkParser?.feedData(data)
    }

    override fun onSitlError(error: String) {
        addLog(getString(R.string.log_error, "SITL: $error"), LogType.ERROR)
    }

    fun injectDebugFault() {
        phoneSensorManager?.injectDebugFault()
    }
    
    fun startAIDetection() {
        if (aiDetector == null) {
            aiDetector = AIDetector(this, this) { label, score, x, y, w, h ->
                val lat = phoneSensorManager?.getCurrentLat() ?: 0.0
                val lon = phoneSensorManager?.getCurrentLon() ?: 0.0
                val msg = "AI:$label|conf=${"%.2f".format(score)}|bbox=[$x,$y,$w,$h]|lat=$lat|lon=$lon\n"
                Log.d(TAG, msg.trim())
                tcpManager?.write(msg.toByteArray())
            }
        }
        aiDetector?.start()
        addLog("AI Detection started", LogType.INFO)
    }
    
    fun stopAIDetection() {
        aiDetector?.stop()
        addLog("AI Detection stopped", LogType.INFO)
    }
}
