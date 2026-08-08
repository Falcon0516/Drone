package com.usbtcpbridge.ui

/**
 * Data class representing a single log entry in the UI
 */
data class LogItem(
    val message: String,
    val type: LogType,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class LogType {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        DATA_IN,
        DATA_OUT
    }
}
