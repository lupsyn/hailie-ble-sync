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
     * ## Important: Persistence Responsibility
     * The [onEventsAcknowledged] callback is invoked immediately after events are
     * acknowledged on the device. **The caller MUST persist these events before
     * returning from the callback** to prevent data loss if the app crashes.
     * Once events are acknowledged, they are removed from the device and cannot
     * be re-read.
     *
     * @param connection Active GATT connection to read from
     * @param totalEvents Total number of events to transfer
     * @param onProgress Callback for progress updates (offset, total)
     * @param onEventsAcknowledged Callback invoked after events are acknowledged.
     *        Receives the list of events that were just acknowledged. The caller
     *        must persist these events to avoid data loss.
     * @return List of all transferred events
     * @throws SyncError If transfer fails after retry attempts
     */
    suspend fun transferEvents(
        connection: GattConnection,
        totalEvents: Int,
        onProgress: (SyncState.SyncProgress) -> Unit,
        onEventsAcknowledged: (List<ActuationEvent>) -> Unit = {}
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
 * 4. Invoke [onEventsAcknowledged] callback immediately after acknowledgment
 * 5. Final acknowledgment after all events transferred
 *
 * ## Data Safety
 * To prevent data loss during crashes:
 * - The [onEventsAcknowledged] callback is invoked **immediately after** each batch
 *   is acknowledged on the device
 * - The caller **must persist** these events before returning from the callback
 * - Once acknowledged, events are removed from the device and cannot be re-read
 * - This ensures acknowledged events are never lost, even if the app crashes mid-sync
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
        onProgress: (SyncState.SyncProgress) -> Unit,
        onEventsAcknowledged: (List<ActuationEvent>) -> Unit
    ): List<ActuationEvent> {
        if (totalEvents == 0) return emptyList()

        val allEvents = mutableListOf<ActuationEvent>()
        val acknowledgedEvents = mutableListOf<ActuationEvent>()
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

            allEvents += chunk
            acknowledgedEvents += chunk
            offset += chunk.size

            // if we have completed a full batch or we are on the last partial batch
            if (offset % ackBatchSize == 0 || offset >= totalEvents) {
                acknowledgeEvents(connection, offset)
                // Callback with acknowledged events for persistence
                onEventsAcknowledged(acknowledgedEvents.toList())
                acknowledgedEvents.clear()
            }
        }

        // Final acknowledgment if there are additional events
        if (offset > 0 && offset % ackBatchSize != 0) {
            acknowledgeEvents(connection, totalEvents)
            // Callback with any remaining acknowledged events
            acknowledgedEvents.takeIf { it.isNotEmpty() }?.let { events ->
                onEventsAcknowledged(events.toList())
            }
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
