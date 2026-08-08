package com.usbtcpbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.usbtcpbridge.databinding.ActivityMainBinding
import com.usbtcpbridge.service.BridgeService
import com.usbtcpbridge.ui.LogAdapter
import com.usbtcpbridge.ui.LogItem

class MainActivity : AppCompatActivity(), BridgeService.StatusListener {

    private lateinit var binding: ActivityMainBinding
    private var bridgeService: BridgeService? = null
    private var serviceBound = false

    private val logAdapter = LogAdapter()
    private val logItems = mutableListOf<LogItem>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BridgeService.LocalBinder
            bridgeService = binder.getService()
            serviceBound = true
            
            bridgeService?.addStatusListener(this@MainActivity)
            
            // Load existing logs
            bridgeService?.getLogHistory()?.forEach { (message, type) ->
                addLogItem(message, convertLogType(type))
            }
            // Update AI status
            val isAiRunning = bridgeService?.let {
                // Check if AI is running (assuming we track this, for now just sync switch)
                binding.aiSwitch.isChecked
            } ?: false
            
            binding.aiSwitch.setOnCheckedChangeListener(null)
            binding.aiSwitch.isChecked = isAiRunning
            binding.aiSwitch.setOnCheckedChangeListener(this@MainActivity::onAiSwitchChanged)
            
            // Update UI state - Telemetry is now AUTO-STARTED
            updateToggleState(bridgeService?.isRunning() == true)
            updateStatus(
                bridgeService?.isUsbDeviceConnected() == true,
                bridgeService?.isTcpClientConnected() == true,
                bridgeService?.isRunning() == true
            )

            // Auto-check the switches since service starts them automatically
            binding.telemetrySwitch.setOnCheckedChangeListener(null)
            binding.telemetrySwitch.isChecked = bridgeService?.isTelemetryRunning() == true
            binding.telemetrySwitch.setOnCheckedChangeListener(this@MainActivity::onTelemetrySwitchChanged)
            
            updateTelemetryStatus(
                bridgeService?.isTelemetryRunning() == true,
                bridgeService?.isTelemetryConnected() == true
            )

            binding.gpsSwitch.setOnCheckedChangeListener(null)
            binding.gpsSwitch.isChecked = bridgeService?.isGpsRunning() == true
            binding.gpsSwitch.setOnCheckedChangeListener(this@MainActivity::onGpsSwitchChanged)
            
            updateGpsStatus(
                bridgeService?.isGpsRunning() == true,
                bridgeService?.isGpsConnected() == true
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService?.removeStatusListener(this@MainActivity)
            bridgeService = null
            serviceBound = false
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BridgeService.BROADCAST_ACTION) {
                val usbConnected = intent.getBooleanExtra(BridgeService.EXTRA_USB_CONNECTED, false)
                val tcpConnected = intent.getBooleanExtra(BridgeService.EXTRA_TCP_CONNECTED, false)
                val tcpListening = intent.getBooleanExtra(BridgeService.EXTRA_TCP_LISTENING, false)
                val serviceRunning = intent.getBooleanExtra(BridgeService.EXTRA_SERVICE_RUNNING, false)

                val telemetryRunning = intent.getBooleanExtra(BridgeService.EXTRA_TELEMETRY_RUNNING, false)
                val telemetryConnected = intent.getBooleanExtra(BridgeService.EXTRA_TELEMETRY_CONNECTED, false)
                val gpsRunning = intent.getBooleanExtra(BridgeService.EXTRA_GPS_RUNNING, false)
                val gpsConnected = intent.getBooleanExtra(BridgeService.EXTRA_GPS_CONNECTED, false)

                runOnUiThread {
                    updateStatus(usbConnected, tcpConnected, tcpListening)
                    updateToggleState(serviceRunning)
                    
                    updateTelemetrySwitchState(telemetryRunning)
                    updateTelemetryStatus(telemetryRunning, telemetryConnected)
                    
                    updateGpsSwitchState(gpsRunning)
                    updateGpsStatus(gpsRunning, gpsConnected)
                }

                // Handle log messages
                intent.getStringExtra(BridgeService.EXTRA_LOG_MESSAGE)?.let { message ->
                    val typeOrdinal = intent.getIntExtra(BridgeService.EXTRA_LOG_TYPE, 0)
                    val type = BridgeService.LogType.entries.getOrElse(typeOrdinal) { BridgeService.LogType.INFO }
                    runOnUiThread {
                        addLogItem(message, convertLogType(type))
                    }
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startBridgeService()
        } else {
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_LONG).show()
            binding.bridgeSwitch.isChecked = false
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineLocationGranted || coarseLocationGranted) {
            startGPS()
        } else {
            Toast.makeText(this, "Location permission required for GPS", Toast.LENGTH_LONG).show()
            binding.gpsSwitch.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadPreferences()
        setupRecyclerView()
    }

    override fun onStart() {
        super.onStart()
        
        // Register broadcast receiver
        val filter = IntentFilter(BridgeService.BROADCAST_ACTION)
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Bind to service if running
        Intent(this, BridgeService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        
        unregisterReceiver(statusReceiver)
        
        if (serviceBound) {
            bridgeService?.removeStatusListener(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        // Setup UI listeners
        binding.clearLogButton.setOnClickListener {
            logAdapter.submitList(emptyList())
        }
        
        binding.bridgeSwitch.setOnCheckedChangeListener(this::onSwitchChanged)
        binding.sitlSwitch.setOnCheckedChangeListener(this::onSitlSwitchChanged)
        binding.telemetrySwitch.setOnCheckedChangeListener(this::onTelemetrySwitchChanged)
        binding.gpsSwitch.setOnCheckedChangeListener(this::onGpsSwitchChanged)
        binding.aiSwitch.setOnCheckedChangeListener(this::onAiSwitchChanged)
        
        binding.injectFaultButton.setOnClickListener {
            bridgeService?.injectDebugFault()
        }
    }
        
    private fun onTelemetrySwitchChanged(buttonView: android.widget.CompoundButton, isChecked: Boolean) {
        if (isChecked) startTelemetry() else stopTelemetry()
        updateInputsEnabled()
    }

    private fun onGpsSwitchChanged(buttonView: android.widget.CompoundButton, isChecked: Boolean) {
        if (isChecked) checkLocationPermissionAndStart() else stopGPS()
        updateInputsEnabled()
    }

    private fun onAiSwitchChanged(buttonView: android.widget.CompoundButton, isChecked: Boolean) {
        if (isChecked) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
                buttonView.isChecked = false
                return
            }
            bridgeService?.startAIDetection()
            binding.aiStatusText.text = "Running"
        } else {
            bridgeService?.stopAIDetection()
            binding.aiStatusText.text = "Stopped"
        }
    }

    private fun onSitlSwitchChanged(buttonView: android.widget.CompoundButton, isChecked: Boolean) {
        binding.sitlConfigContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        binding.baudRateLayout.visibility = if (isChecked) android.view.View.GONE else android.view.View.VISIBLE
    }


    private fun onSwitchChanged(buttonView: android.widget.CompoundButton, isChecked: Boolean) {
        if (isChecked) {
            if (validateInputs()) {
                savePreferences()
                checkPermissionsAndStart()
            } else {
                binding.bridgeSwitch.setOnCheckedChangeListener(null)
                binding.bridgeSwitch.isChecked = false
                binding.bridgeSwitch.setOnCheckedChangeListener(this::onSwitchChanged)
            }
        } else {
            stopBridgeService()
        }

        // Clear log button
        binding.clearLogButton.setOnClickListener {
            logItems.clear()
            logAdapter.submitList(emptyList())
        }

        // Disable inputs while service is running
        updateInputsEnabled(true)
    }

    private fun setupRecyclerView() {
        binding.logRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = logAdapter
        }
    }

    private fun validateInputs(): Boolean {
        val portText = binding.tcpPortInput.text?.toString()
        val baudText = binding.baudRateInput.text?.toString()

        if (portText.isNullOrBlank()) {
            binding.tcpPortLayout.error = getString(R.string.error_invalid_port)
            return false
        }

        val port = portText.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            binding.tcpPortLayout.error = getString(R.string.error_invalid_port)
            return false
        }
        binding.tcpPortLayout.error = null

        if (baudText.isNullOrBlank()) {
            binding.baudRateLayout.error = getString(R.string.error_invalid_baud)
            return false
        }

        val baudRate = baudText.toIntOrNull()
        if (baudRate == null || baudRate < 300) {
            binding.baudRateLayout.error = getString(R.string.error_invalid_baud)
            return false
        }
        binding.baudRateLayout.error = null

        return true
    }
    
    // Telemetry & GPS Logic
    
    private fun startTelemetry() {
        if (!validateTelemetryInput()) {
            binding.telemetrySwitch.isChecked = false
            return
        }
        val port = binding.telemetryPortInput.text.toString().toInt()
        savePreferences() // Persist port
        bridgeService?.startTelemetry(port)
    }
    
    private fun stopTelemetry() {
        bridgeService?.stopTelemetry()
    }
    
    private fun validateTelemetryInput(): Boolean {
        val portText = binding.telemetryPortInput.text?.toString()
        val port = portText?.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            binding.telemetryPortLayout.error = getString(R.string.error_invalid_port)
            return false
        }
        binding.telemetryPortLayout.error = null
        return true
    }

    private fun checkLocationPermissionAndStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            startGPS()
        }
    }
    
    private fun startGPS() {
        if (!validateGPSInput()) {
            binding.gpsSwitch.isChecked = false
            return
        }
        val port = binding.gpsPortInput.text.toString().toInt()
        savePreferences()
        bridgeService?.startGPS(port)
    }
    
    private fun stopGPS() {
        bridgeService?.stopGPS()
    }

    private fun validateGPSInput(): Boolean {
        val portText = binding.gpsPortInput.text?.toString()
        val port = portText?.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            binding.gpsPortLayout.error = getString(R.string.error_invalid_port)
            return false
        }
        binding.gpsPortLayout.error = null
        return true
    }

    private fun checkPermissionsAndStart() {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startBridgeService()
    }

    private fun startBridgeService() {
        val port = binding.tcpPortInput.text?.toString()?.toIntOrNull() ?: BridgeService.DEFAULT_TCP_PORT
        val baudRate = binding.baudRateInput.text?.toString()?.toIntOrNull() ?: BridgeService.DEFAULT_BAUD_RATE
        val isSitlMode = binding.sitlSwitch.isChecked
        val sitlIp = binding.sitlIpInput.text?.toString() ?: "127.0.0.1"
        val sitlPort = binding.sitlPortInput.text?.toString()?.toIntOrNull() ?: 5760

        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_TCP_PORT, port)
            putExtra(BridgeService.EXTRA_BAUD_RATE, baudRate)
            putExtra(BridgeService.EXTRA_SITL_MODE, isSitlMode)
            putExtra(BridgeService.EXTRA_SITL_IP, sitlIp)
            putExtra(BridgeService.EXTRA_SITL_PORT, sitlPort)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Bind to get updates
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            updateInputsEnabled(false)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_service_start_failed), Toast.LENGTH_SHORT).show()
            binding.bridgeSwitch.isChecked = false
        }
    }

    private fun stopBridgeService() {
        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_STOP
        }
        startService(intent)
        
        updateInputsEnabled(true)
        updateStatus(false, false, false)
    }

    private fun updateToggleState(isRunning: Boolean) {
        // Avoid listener loop
        binding.bridgeSwitch.setOnCheckedChangeListener(null)
        binding.bridgeSwitch.isChecked = isRunning
        // Re-attach listener
        binding.bridgeSwitch.setOnCheckedChangeListener(this::onSwitchChanged)

        binding.toggleLabel.text = if (isRunning) {
            getString(R.string.toggle_stop)
        } else {
            getString(R.string.toggle_start)
        }
        updateInputsEnabled()
    }
    
    private fun updateTelemetrySwitchState(isRunning: Boolean) {
        binding.telemetrySwitch.setOnCheckedChangeListener(null)
        binding.telemetrySwitch.isChecked = isRunning
        binding.telemetrySwitch.setOnCheckedChangeListener(this::onTelemetrySwitchChanged)
        updateInputsEnabled()
    }

    private fun updateGpsSwitchState(isRunning: Boolean) {
        binding.gpsSwitch.setOnCheckedChangeListener(null)
        binding.gpsSwitch.isChecked = isRunning
        binding.gpsSwitch.setOnCheckedChangeListener(this::onGpsSwitchChanged)
        updateInputsEnabled()
    }

    private fun savePreferences() {
        val port = binding.tcpPortInput.text?.toString()?.toIntOrNull() ?: BridgeService.DEFAULT_TCP_PORT
        val baud = binding.baudRateInput.text?.toString()?.toIntOrNull() ?: BridgeService.DEFAULT_BAUD_RATE
        val telemetryPort = binding.telemetryPortInput.text?.toString()?.toIntOrNull() ?: 1235
        val gpsPort = binding.gpsPortInput.text?.toString()?.toIntOrNull() ?: 1236
        val sitlIp = binding.sitlIpInput.text?.toString() ?: "127.0.0.1"
        val sitlPort = binding.sitlPortInput.text?.toString()?.toIntOrNull() ?: 5760
        val isSitlMode = binding.sitlSwitch.isChecked
        
        getSharedPreferences("USBTCPBridge", Context.MODE_PRIVATE).edit().apply {
            putInt("pref_port", port)
            putInt("pref_baud", baud)
            putInt("pref_telemetry_port", telemetryPort)
            putInt("pref_gps_port", gpsPort)
            putString("pref_sitl_ip", sitlIp)
            putInt("pref_sitl_port", sitlPort)
            putBoolean("pref_sitl_mode", isSitlMode)
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("USBTCPBridge", Context.MODE_PRIVATE)
        val port = prefs.getInt("pref_port", BridgeService.DEFAULT_TCP_PORT)
        val baud = prefs.getInt("pref_baud", BridgeService.DEFAULT_BAUD_RATE)
        val telemetryPort = prefs.getInt("pref_telemetry_port", 1235)
        val gpsPort = prefs.getInt("pref_gps_port", 1236)
        val sitlIp = prefs.getString("pref_sitl_ip", "127.0.0.1")
        val sitlPort = prefs.getInt("pref_sitl_port", 5760)
        val isSitlMode = prefs.getBoolean("pref_sitl_mode", false)
        
        binding.tcpPortInput.setText(port.toString())
        binding.baudRateInput.setText(baud.toString())
        binding.telemetryPortInput.setText(telemetryPort.toString())
        binding.gpsPortInput.setText(gpsPort.toString())
        binding.sitlIpInput.setText(sitlIp)
        binding.sitlPortInput.setText(sitlPort.toString())
        binding.sitlSwitch.isChecked = isSitlMode
        onSitlSwitchChanged(binding.sitlSwitch, isSitlMode)
    }

    private fun updateInputsEnabled(unused: Boolean = false, unused2: Boolean = false, unused3: Boolean = false) {
        val bridgeRunning = binding.bridgeSwitch.isChecked
        val telemetryRunning = binding.telemetrySwitch.isChecked
        val gpsRunning = binding.gpsSwitch.isChecked

        binding.tcpPortInput.isEnabled = !bridgeRunning
        binding.baudRateInput.isEnabled = !bridgeRunning
        binding.sitlSwitch.isEnabled = !bridgeRunning
        binding.sitlIpInput.isEnabled = !bridgeRunning
        binding.sitlPortInput.isEnabled = !bridgeRunning
        binding.telemetryPortInput.isEnabled = !telemetryRunning
        binding.gpsPortInput.isEnabled = !gpsRunning
    }
    
    private fun updateTelemetryStatus(isRunning: Boolean, isConnected: Boolean) {
        val color = when {
            isConnected -> R.color.status_connected
            isRunning -> R.color.status_waiting
            else -> R.color.status_disconnected
        }
        (binding.telemetryStatusIndicator.background as GradientDrawable).setColor(
            ContextCompat.getColor(this, color)
        )
        binding.telemetryStatusText.text = when {
            isConnected -> getString(R.string.status_connected)
            isRunning -> getString(R.string.status_waiting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun updateGpsStatus(isRunning: Boolean, isConnected: Boolean) {
        val color = when {
            isConnected -> R.color.status_connected
            isRunning -> R.color.status_waiting
            else -> R.color.status_disconnected
        }
        (binding.gpsStatusIndicator.background as GradientDrawable).setColor(
            ContextCompat.getColor(this, color)
        )
        binding.gpsStatusText.text = when {
            isConnected -> getString(R.string.status_connected)
            isRunning -> getString(R.string.status_waiting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun updateStatus(usbConnected: Boolean, tcpConnected: Boolean, tcpListening: Boolean) {
        // USB Status
        updateStatusIndicator(
            binding.usbStatusIndicator.background as GradientDrawable,
            usbConnected
        )
        binding.usbStatusText.text = if (usbConnected) {
            getString(R.string.status_connected)
        } else {
            getString(R.string.status_disconnected)
        }

        // TCP Status
        val tcpColor = when {
            tcpConnected -> R.color.status_connected
            tcpListening -> R.color.status_waiting
            else -> R.color.status_disconnected
        }
        (binding.tcpStatusIndicator.background as GradientDrawable).setColor(
            ContextCompat.getColor(this, tcpColor)
        )
        binding.tcpStatusText.text = when {
            tcpConnected -> getString(R.string.status_connected)
            tcpListening -> getString(R.string.status_waiting)
            else -> getString(R.string.status_disconnected)
        }
    }

    private fun updateStatusIndicator(drawable: GradientDrawable, connected: Boolean) {
        val color = if (connected) R.color.status_connected else R.color.status_disconnected
        drawable.setColor(ContextCompat.getColor(this, color))
    }

    private fun addLogItem(message: String, type: LogItem.LogType) {
        val item = LogItem(message, type)
        logItems.add(item)
        
        // Keep only last 500 items
        while (logItems.size > 500) {
            logItems.removeAt(0)
        }
        
        logAdapter.submitList(logItems.toList())
        
        // Scroll to bottom
        binding.logRecyclerView.post {
            if (logItems.isNotEmpty()) {
                binding.logRecyclerView.smoothScrollToPosition(logItems.size - 1)
            }
        }
    }

    private fun convertLogType(serviceType: BridgeService.LogType): LogItem.LogType {
        return when (serviceType) {
            BridgeService.LogType.INFO -> LogItem.LogType.INFO
            BridgeService.LogType.SUCCESS -> LogItem.LogType.SUCCESS
            BridgeService.LogType.WARNING -> LogItem.LogType.WARNING
            BridgeService.LogType.ERROR -> LogItem.LogType.ERROR
            BridgeService.LogType.DATA_IN -> LogItem.LogType.DATA_IN
            BridgeService.LogType.DATA_OUT -> LogItem.LogType.DATA_OUT
        }
    }

    // BridgeService.StatusListener implementation
    
    override fun onStatusChanged(usbConnected: Boolean, tcpConnected: Boolean, tcpListening: Boolean) {
        runOnUiThread {
            updateStatus(usbConnected, tcpConnected, tcpListening)
        }
    }

    override fun onLogMessage(message: String, type: BridgeService.LogType) {
        runOnUiThread {
            addLogItem(message, convertLogType(type))
        }
    }
}
