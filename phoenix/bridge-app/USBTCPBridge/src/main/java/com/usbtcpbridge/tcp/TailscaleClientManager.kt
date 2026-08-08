package com.usbtcpbridge.tcp

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TailscaleClientManager acts as a TCP Client that connects OUT to a ground machine
 * over a Tailscale (or any IP) network, tunneling telemetry data.
 * It automatically attempts to reconnect if the connection drops.
 */
class TailscaleClientManager(
    private val listener: TCPManager.TCPListener
) {
    companion object {
        private const val TAG = "TailscaleClient"
        private const val READ_BUFFER_SIZE = 4096
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val isRunning = AtomicBoolean(false)
    private val clientSocket = AtomicReference<Socket?>(null)
    private val outputStream = AtomicReference<OutputStream?>(null)

    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    
    private var targetHost: String = ""
    private var targetPort: Int = 0

    val isConnected: Boolean
        get() = isRunning.get() && clientSocket.get()?.isConnected == true && !clientSocket.get()!!.isClosed

    /**
     * Start the client connection loop to the specified host and port
     */
    @Synchronized
    fun start(host: String, port: Int): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Client already running")
            return false
        }
        
        targetHost = host
        targetPort = port
        isRunning.set(true)

        Log.i(TAG, "Starting Tailscale Client towards $host:$port")
        listener.onServerStarted(port) // Reusing the callback to indicate the service is active

        connectThread = Thread({ connectLoop() }, "Tailscale-Connect-Thread").apply { start() }
        return true
    }

    /**
     * Stop the client and cleanup resources
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping Tailscale client...")

        disconnectSocket()

        connectThread?.interrupt()
        try {
            connectThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        connectThread = null

        listener.onServerStopped()
        Log.i(TAG, "Tailscale client stopped")
    }

    /**
     * Write data to the remote server
     */
    fun write(data: ByteArray): Boolean {
        val stream = outputStream.get() ?: return false
        return try {
            stream.write(data)
            stream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to remote server", e)
            disconnectSocket()
            false
        }
    }

    /**
     * Main connection loop that maintains the persistent tunnel
     */
    private fun connectLoop() {
        Log.d(TAG, "Connect loop started")

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            if (clientSocket.get() == null || clientSocket.get()?.isClosed == true) {
                try {
                    Log.i(TAG, "Attempting connection to $targetHost:$targetPort...")
                    val socket = Socket(targetHost, targetPort)
                    socket.soTimeout = 0 // Infinite read timeout
                    socket.tcpNoDelay = true
                    socket.keepAlive = true

                    handleNewConnection(socket)
                    
                    // Wait for the read thread to finish before trying to reconnect
                    readThread?.join()
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Connection failed: ${e.message}. Retrying in ${RECONNECT_DELAY_MS}ms...")
                        try {
                            Thread.sleep(RECONNECT_DELAY_MS)
                        } catch (ie: InterruptedException) {
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                }
            } else {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        Log.d(TAG, "Connect loop ended")
    }

    private fun handleNewConnection(socket: Socket) {
        clientSocket.set(socket)
        outputStream.set(socket.getOutputStream())
        
        val remoteAddress = "${socket.inetAddress.hostAddress}:${socket.port}"
        Log.i(TAG, "Connected to Ground Station: $remoteAddress")
        listener.onClientConnected(remoteAddress)

        readThread = Thread({ readLoop(socket.getInputStream()) }, "Tailscale-Read-Thread").apply { start() }
    }

    @Synchronized
    private fun disconnectSocket() {
        val socket = clientSocket.getAndSet(null) ?: return

        try {
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing client socket", e)
        }

        outputStream.set(null)
        
        if (Thread.currentThread() != readThread) {
            readThread?.interrupt()
            try {
                readThread?.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            readThread = null
        }

        listener.onClientDisconnected()
    }

    private fun readLoop(inputStream: InputStream) {
        Log.d(TAG, "Read loop started")
        val buffer = ByteArray(READ_BUFFER_SIZE)

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = inputStream.read(buffer)
                
                if (bytesRead == -1) {
                    Log.i(TAG, "Ground Station disconnected (EOF)")
                    break
                }

                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    listener.onTcpDataReceived(data)
                }
            }
        } catch (e: SocketException) {
            if (isRunning.get()) {
                Log.i(TAG, "Socket closed")
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading from server", e)
                listener.onTcpError("Read error: ${e.message}")
            }
        } finally {
            if (isRunning.get()) {
                disconnectSocket()
            }
        }

        Log.d(TAG, "Read loop ended")
    }
}
