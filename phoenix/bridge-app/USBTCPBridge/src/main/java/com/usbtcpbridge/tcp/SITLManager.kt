package com.usbtcpbridge.tcp

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SITLManager acts as a TCP Client to connect to a Software In The Loop (SITL)
 * drone simulator. It receives MAVLink data exactly like the USBManager does,
 * but over Wi-Fi or ADB reverse port forwarding.
 */
class SITLManager(
    private val listener: SITLListener
) {
    companion object {
        private const val TAG = "SITLManager"
        private const val READ_BUFFER_SIZE = 4096
    }

    interface SITLListener {
        fun onSitlConnected(address: String)
        fun onSitlDisconnected()
        fun onSitlDataReceived(data: ByteArray)
        fun onSitlError(error: String)
    }

    private val isRunning = AtomicBoolean(false)
    private val clientSocket = AtomicReference<Socket?>(null)
    private val outputStream = AtomicReference<OutputStream?>(null)

    private var connectThread: Thread? = null
    private var readThread: Thread? = null

    val isConnected: Boolean
        get() = isRunning.get() && clientSocket.get()?.isConnected == true && !clientSocket.get()!!.isClosed

    /**
     * Start the SITL TCP client connection
     */
    @Synchronized
    fun start(host: String, port: Int): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "SITL Client already running")
            return false
        }

        isRunning.set(true)
        Log.i(TAG, "Starting SITL Client targeting $host:$port")

        connectThread = Thread({ connectLoop(host, port) }, "SITL-Connect-Thread")
        connectThread?.start()

        return true
    }

    /**
     * Stop the SITL TCP client
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping SITL Client...")

        disconnectClient()

        connectThread?.interrupt()
        connectThread = null
    }

    @Synchronized
    private fun disconnectClient() {
        val socket = clientSocket.getAndSet(null) ?: return

        readThread?.interrupt()

        try {
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing SITL socket", e)
        }

        outputStream.set(null)

        try {
            readThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readThread = null

        listener.onSitlDisconnected()
    }

    fun write(data: ByteArray): Boolean {
        val stream = outputStream.get() ?: return false
        return try {
            stream.write(data)
            stream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to SITL client", e)
            disconnectClient()
            false
        }
    }

    private fun connectLoop(host: String, port: Int) {
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            if (clientSocket.get() == null || clientSocket.get()?.isConnected != true) {
                try {
                    Log.i(TAG, "Attempting to connect to SITL at $host:$port...")
                    val socket = Socket(host, port)
                    
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    // socket.soTimeout = 5000 // Optional: timeout for reading
                    
                    clientSocket.set(socket)
                    outputStream.set(socket.getOutputStream())

                    listener.onSitlConnected("$host:$port")

                    readThread = Thread({ readLoop(socket.getInputStream()) }, "SITL-Read-Thread")
                    readThread?.start()

                    // Wait for readThread to finish (meaning disconnected)
                    readThread?.join()

                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Failed to connect to SITL: ${e.message}")
                        listener.onSitlError("Connection failed: ${e.message}")
                        // Wait before reconnecting
                        try {
                            Thread.sleep(3000)
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
        Log.i(TAG, "SITL connect loop ended")
    }

    private fun readLoop(inputStream: InputStream) {
        Log.d(TAG, "SITL Read loop started")
        val buffer = ByteArray(READ_BUFFER_SIZE)

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = inputStream.read(buffer)

                if (bytesRead == -1) {
                    Log.i(TAG, "SITL disconnected (EOF)")
                    break
                }

                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    listener.onSitlDataReceived(data)
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "SITL socket timeout", e)
        } catch (e: SocketException) {
            if (isRunning.get()) {
                Log.i(TAG, "SITL socket closed")
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading from SITL", e)
                listener.onSitlError("Read error: ${e.message}")
            }
        } finally {
            if (isRunning.get()) {
                // Ensure UI is updated and socket is fully closed so connectLoop can try again
                disconnectClient()
            }
        }

        Log.d(TAG, "SITL Read loop ended")
    }
}
