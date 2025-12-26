package com.adherium.hailie.ble.sensor

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Represents a Hailie BLE sensor device.
 *
 * This interface abstracts the Bluetooth Low Energy operations required to communicate
 * with a Hailie smartinhaler device. Implementations should handle platform-specific
 * BLE stack interactions.
 *
 * ## Thread Safety
 * All suspend functions must be safe to call from any coroutine context.
 * StateFlow properties are thread-safe by design.
 *
 * ## Example Implementation
 * ```kotlin
 * class AndroidHailieSensor(
 *     private val bluetoothDevice: BluetoothDevice,
 *     private val context: Context
 * ) : HailieSensor {
 *     override suspend fun bond(): Result<Unit> = ...
 *     override suspend fun connect(): Result<GattConnection> = ...
 *     override suspend fun disconnect() = ...
 * }
 * ```
 */
interface HailieSensor {
    /**
     * Current Bluetooth bonding (pairing) state of the device.
     * Updated automatically when bonding state changes.
     */
    val bondState: StateFlow<BondState>

    /**
     * Current GATT connection state of the device.
     * Updated automatically when connection state changes.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Initiates Bluetooth bonding (pairing) with the device.
     *
     * @return Success if bonding initiated, Failure with exception if bonding fails
     */
    suspend fun bond(): Result<Unit>

    /**
     * Establishes a GATT connection to the bonded device.
     *
     * The device must be bonded before calling this method.
     *
     * @return Success with [GattConnection] if connected, Failure with exception on error
     */
    suspend fun connect(): Result<GattConnection>

    /**
     * Disconnects the GATT connection and releases resources.
     *
     * This method is idempotent - calling on an already disconnected device is safe.
     */
    suspend fun disconnect()
}
