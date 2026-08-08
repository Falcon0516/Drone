package com.usbtcpbridge.bridge

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DataBridge manages bi-directional data transfer between USB and TCP.
 * Uses dedicated threads and queues for each direction to prevent blocking.
 */
class DataBridge(
    private val listener: BridgeListener
) {
    companion object {
        private const val TAG = "DataBridge"
        private const val QUEUE_CAPACITY = 1000
        private const val POLL_TIMEOUT_MS = 100L
    }

    interface BridgeListener {
        fun onUsbToTcpData(data: ByteArray): Boolean
        fun onTcpToUsbData(data: ByteArray): Boolean
        fun onUsbToTcpBytes(count: Long)
        fun onTcpToUsbBytes(count: Long)
        fun onError(direction: String, error: String)
        fun onBufferOverflow(direction: String)
    }

    private val isRunning = AtomicBoolean(false)

    // Queues for buffering data between threads
    private val usbToTcpQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val tcpToUsbQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    // Statistics
    private val usbToTcpBytes = AtomicLong(0)
    private val tcpToUsbBytes = AtomicLong(0)

    private var usbToTcpThread: Thread? = null
    private var tcpToUsbThread: Thread? = null

    /**
     * Start the bridge threads
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Bridge already running")
            return
        }

        Log.i(TAG, "Starting data bridge...")

        usbToTcpBytes.set(0)
        tcpToUsbBytes.set(0)

        usbToTcpThread = Thread({ usbToTcpLoop() }, "USB-to-TCP-Thread").apply { start() }
        tcpToUsbThread = Thread({ tcpToUsbLoop() }, "TCP-to-USB-Thread").apply { start() }

        Log.i(TAG, "Data bridge started")
    }

    /**
     * Stop the bridge threads
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping data bridge...")

        // Interrupt threads
        usbToTcpThread?.interrupt()
        tcpToUsbThread?.interrupt()

        // Wait for threads to finish
        usbToTcpThread?.join(1000)
        tcpToUsbThread?.join(1000)

        usbToTcpThread = null
        tcpToUsbThread = null

        // Clear queues
        usbToTcpQueue.clear()
        tcpToUsbQueue.clear()

        Log.i(TAG, "Data bridge stopped. Total bytes - USB→TCP: ${usbToTcpBytes.get()}, TCP→USB: ${tcpToUsbBytes.get()}")
    }

    /**
     * Queue data from USB to be sent to TCP
     */
    fun queueUsbData(data: ByteArray) {
        if (!isRunning.get()) return

        if (!usbToTcpQueue.offer(data)) {
            // Queue full - drop oldest data and add new
            Log.w(TAG, "USB→TCP queue full, dropping oldest data")
            listener.onBufferOverflow("USB→TCP")
            usbToTcpQueue.poll()
            usbToTcpQueue.offer(data)
        }
    }

    /**
     * Queue data from TCP to be sent to USB
     */
    fun queueTcpData(data: ByteArray) {
        if (!isRunning.get()) return

        if (!tcpToUsbQueue.offer(data)) {
            // Queue full - drop oldest data and add new
            Log.w(TAG, "TCP→USB queue full, dropping oldest data")
            listener.onBufferOverflow("TCP→USB")
            tcpToUsbQueue.poll()
            tcpToUsbQueue.offer(data)
        }
    }

    /**
     * Get total bytes transferred from USB to TCP
     */
    fun getUsbToTcpBytes(): Long = usbToTcpBytes.get()

    /**
     * Get total bytes transferred from TCP to USB
     */
    fun getTcpToUsbBytes(): Long = tcpToUsbBytes.get()

    /**
     * Check if bridge is running
     */
    fun isActive(): Boolean = isRunning.get()

    /**
     * USB to TCP transfer loop
     */
    private fun usbToTcpLoop() {
        Log.d(TAG, "USB→TCP loop started")

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val data = usbToTcpQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                
                if (data != null) {
                    val success = listener.onUsbToTcpData(data)
                    if (success) {
                        val total = usbToTcpBytes.addAndGet(data.size.toLong())
                        listener.onUsbToTcpBytes(total)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "USB→TCP loop interrupted")
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in USB→TCP loop", e)
                listener.onError("USB→TCP", e.message ?: "Unknown error")
            }
        }

        Log.d(TAG, "USB→TCP loop ended")
    }

    /**
     * TCP to USB transfer loop
     */
    private fun tcpToUsbLoop() {
        Log.d(TAG, "TCP→USB loop started")

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val data = tcpToUsbQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                
                if (data != null) {
                    val success = listener.onTcpToUsbData(data)
                    if (success) {
                        val total = tcpToUsbBytes.addAndGet(data.size.toLong())
                        listener.onTcpToUsbBytes(total)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "TCP→USB loop interrupted")
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in TCP→USB loop", e)
                listener.onError("TCP→USB", e.message ?: "Unknown error")
            }
        }

        Log.d(TAG, "TCP→USB loop ended")
    }
}
