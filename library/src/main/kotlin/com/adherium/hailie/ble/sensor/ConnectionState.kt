package com.adherium.hailie.ble.sensor

/**
 * GATT connection states.
 */
enum class ConnectionState {
    /** No active GATT connection */
    DISCONNECTED,

    /** Connection attempt in progress */
    CONNECTING,

    /** Active GATT connection established */
    CONNECTED,

    /** Disconnection in progress */
    DISCONNECTING
}
