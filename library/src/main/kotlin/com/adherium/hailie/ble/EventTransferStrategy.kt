package com.adherium.hailie.ble

import com.adherium.hailie.ble.retry.RetryStrategy
import com.adherium.hailie.ble.sensor.ActuationEvent
import com.adherium.hailie.ble.sensor.GattConnection

/**
 * Strategy for transferring events from a GATT connection.
 *
 * Implementations define how events should be read from the BLE device,
 * including chunking, progress reporting, and acknowledgment batching.
 *
 * ## SOLID Principles
 * This interface follows:
 * - **Single Responsibility**: Only concerned with event transfer mechanics
 * - **Open/Closed**: New transfer strategies can be added without modifying existing code
 * - **Dependency Inversion**: Clients depend on abstraction, not concrete implementations
 *
 * ## Example Implementation
 * ```kotlin
 * class AdaptiveChunkingStrategy(
 *     private val retryStrategy: RetryStrategy,
 *     private val baseChunkSize: Int = 50
 * ) : EventTransferStrategy {
 *     override suspend fun transferEvents(
 *         connection: GattConnection,
 *         totalEvents: Int,
 *         onProgress: (SyncState.SyncProgress) -> Unit
 *     ): List<ActuationEvent> {
 *         // Adjust chunk size based on total events
 *         val chunkSize = when {
 *             totalEvents < 10 -> totalEvents
 *             totalEvents < 100 -> 50
 *             else -> 100
 *         }
 *         // Transfer with optimized chunk size
 *     }
 * }
 * ```
 *
 * @see ChunkedEventTransferStrategy
 */
interface EventTransferStrategy {
    /**
     * Transfers all events from the GATT connection.
     *
     * Reads events from the device and reports progress throughout the transfer.
     * The strategy determines how to chunk the reads, when to acknowledge, and
     * how to handle errors during transfer.
     *
     * @param connection Active GATT connection to read from
     * @param totalEvents Total number of events to transfer
     * @param onProgress Callback for progress updates (offset, total)
     * @return List of all transferred events
     * @throws SyncError If transfer fails after retry attempts
     */
    suspend fun transferEvents(
        connection: GattConnection,
        totalEvents: Int,
        onProgress: (SyncState.SyncProgress) -> Unit
    ): List<ActuationEvent>
}

/**
 * Event transfer strategy using fixed-size chunks with batched acknowledgments.
 *
 * This implementation reads events in fixed-size chunks to optimize BLE performance
 * and reduces acknowledgment overhead by batching ACKs. It achieves ~95% reduction
 * in BLE round trips compared to per-event operations.
 *
 * ## Algorithm
 * 1. Read events in chunks of [chunkSize]
 * 2. Report progress after each chunk
 * 3. Acknowledge every [ackBatchSize] events
 * 4. Final acknowledgment after all events transferred
 *
 * ## Performance Characteristics
 * - **Chunk size 50**: Balances MTU constraints with round-trip reduction
 * - **ACK batch 2x chunk**: Reduces acknowledgment overhead by 50%
 * - **Retry support**: Uses injected RetryStrategy for transient failures
 *
 * @property retryStrategy Strategy for retrying failed BLE operations
 * @property chunkSize Number of events to read per GATT operation (default: 50)
 * @property ackBatchMultiplier Multiplier for ACK batching (default: 2)
 *
 * @see com.adherium.hailie.ble.retry.RetryStrategy
 */
class ChunkedEventTransferStrategy(
    private val retryStrategy: RetryStrategy,
    private val chunkSize: Int = 50,
    private val ackBatchMultiplier: Int = 2
) : EventTransferStrategy {

    private val ackBatchSize: Int = chunkSize * ackBatchMultiplier

    override suspend fun transferEvents(
        connection: GattConnection,
        totalEvents: Int,
        onProgress: (SyncState.SyncProgress) -> Unit
    ): List<ActuationEvent> {
        if (totalEvents == 0) {
            return emptyList()
        }

        val allEvents = mutableListOf<ActuationEvent>()
        var offset = 0

        // Read events in chunks
        while (offset < totalEvents) {
            val count = minOf(chunkSize, totalEvents - offset)

            // Report progress
            onProgress(SyncState.SyncProgress(offset, totalEvents))

            // Read chunk with retry
            val chunk = retryStrategy.executeWithRetry(
                operation = { connection.readEvents(offset, count) },
                errorMapper = { SyncError.ReadFailed("Failed to read events at offset $offset: $it", offset) }
            )

            allEvents.addAll(chunk)
            offset += chunk.size

            // if we have completed a full batch or we are on the last partial batch
            if (offset % ackBatchSize == 0 || offset >= totalEvents) {
                acknowledgeEvents(connection, offset)
            }
        }

        // Final acknowledgment if there are additional events
        if (offset > 0 && offset % ackBatchSize != 0) {
            acknowledgeEvents(connection, totalEvents)
        }

        return allEvents
    }

    /**
     * Acknowledges synced events on the device with retry support.
     */
    private suspend fun acknowledgeEvents(connection: GattConnection, upToOffset: Int) {
        retryStrategy.executeWithRetry(
            operation = { connection.acknowledgeEvents(upToOffset) },
            errorMapper = { SyncError.AcknowledgeFailed("Failed to acknowledge events: $it") }
        )
    }
}
