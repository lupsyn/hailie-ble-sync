package com.adherium.hailie.ble.sensor

/**
 * Represents an active GATT connection to a bonded device.
 *
 * This interface provides methods to read actuation events from the device
 * and acknowledge successful synchronization.
 *
 * ## Connection Lifecycle
 * 1. Obtain GattConnection from [HailieSensor.connect]
 * 2. Read event count to determine sync scope
 * 3. Read events in chunks for efficiency
 * 4. Acknowledge events after successful processing
 * 5. Connection is closed when [HailieSensor.disconnect] is called
 */
interface GattConnection {
    /**
     * Reads the total number of unacknowledged events on the device.
     *
     * @return Success with event count, Failure with exception on read error
     */
    suspend fun readEventCount(): Result<Int>

    /**
     * Reads a batch of actuation events from the device.
     *
     * @param offset Zero-based index of the first event to read
     * @param count Maximum number of events to read in this batch
     * @return Success with list of events (may be fewer than count if at end),
     *         Failure with exception on read error
     */
    suspend fun readEvents(offset: Int, count: Int): Result<List<ActuationEvent>>

    /**
     * Acknowledges successful synchronization of events up to the specified offset.
     *
     * After acknowledgment, the device may delete these events from its storage.
     * This operation should only be called after events are safely persisted.
     *
     * @param upToOffset The number of events successfully synced (1-indexed)
     * @return Success if acknowledged, Failure with exception on error
     */
    suspend fun acknowledgeEvents(upToOffset: Int): Result<Unit>
}
