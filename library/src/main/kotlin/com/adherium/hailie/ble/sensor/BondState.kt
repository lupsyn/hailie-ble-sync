package com.adherium.hailie.ble.sensor
/**
 * Bluetooth bonding (pairing) states.
 */
enum class BondState {
    /** Device is not bonded - bonding required before connection */
    NONE,

    /** Bonding operation is in progress - do not attempt to bond again */
    BONDING,

    /** Device is successfully bonded - ready for GATT connection */
    BONDED
}
