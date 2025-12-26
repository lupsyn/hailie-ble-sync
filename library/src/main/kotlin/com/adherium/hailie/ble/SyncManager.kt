package com.adherium.hailie.ble

import com.adherium.hailie.ble.retry.ExponentialBackoffRetryStrategy
import com.adherium.hailie.ble.retry.RetryPolicy
import com.adherium.hailie.ble.retry.RetryStrategy
import com.adherium.hailie.ble.sensor.ActuationEvent
import com.adherium.hailie.ble.sensor.BondState
import com.adherium.hailie.ble.sensor.ConnectionState
import com.adherium.hailie.ble.sensor.GattConnection
import com.adherium.hailie.ble.sensor.HailieSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages synchronization of actuation events from a Hailie BLE sensor.
 *
 * This class orchestrates the complete sync workflow: bonding, connection,
 * data transfer, and acknowledgment. It delegates specialized concerns to
 * injected strategies, following SOLID principles for maintainability and testability.
 *
 * ## Usage
 * ```kotlin
 * val syncManager = SyncManager(
 *     sensor = AndroidHailieSensor(bluetoothDevice, context),
 *     retryStrategy = ExponentialBackoffRetryStrategy(RetryPolicy.DEFAULT),
 *     transferStrategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 50)
 * )
 *
 * // Observe state in UI
 * lifecycleScope.launch {
 *     syncManager.syncState.collect { state ->
 *         when (state) {
 *             is SyncState.Syncing -> showProgress(state.progress.percentage)
 *             is SyncState.Success -> showSuccess(state.eventsSynced)
 *             is SyncState.Failed -> showError(state.error)
 *             // ...
 *         }
 *     }
 * }
 *
 * // Execute sync
 * when (val result = syncManager.sync()) {
 *     is SyncResult.Success -> persistEvents(result.events)
 *     is SyncResult.Failure -> handleError(result.error)
 * }
 * ```
 *
 * ## Thread Safety
 * All public methods are thread-safe. Concurrent [sync] calls are serialized
 * via internal mutex - the second call blocks until the first completes.
 *
 * @property sensor The BLE sensor device to synchronize with
 * @property retryStrategy Strategy for retrying failed BLE operations
 * @property transferStrategy Strategy for transferring events from the device
 */
class SyncManager(
    private val sensor: HailieSensor,
    private val retryStrategy: RetryStrategy,
    private val transferStrategy: EventTransferStrategy
) {
    /**
     * Convenience constructor with default strategies.
     *
     * Creates a SyncManager with exponential backoff retry and chunked transfer strategies.
     *
     * @param sensor The BLE sensor device to synchronize with
     * @param retryPolicy Retry configuration (default: RetryPolicy.DEFAULT)
     * @param chunkSize Number of events per read operation (default: 50)
     * @param operationTimeoutMs Timeout for BLE operations in milliseconds (default: 30s)
     */
    constructor(
        sensor: HailieSensor,
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
        chunkSize: Int = 50,
        operationTimeoutMs: Long = 30000L
    ) : this(
        sensor = sensor,
        retryStrategy = ExponentialBackoffRetryStrategy(retryPolicy, operationTimeoutMs),
        transferStrategy = ChunkedEventTransferStrategy(
            retryStrategy = ExponentialBackoffRetryStrategy(retryPolicy, operationTimeoutMs),
            chunkSize = chunkSize
        )
    )
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)

    /**
     * Observable sync state updated throughout the sync lifecycle.
     *
     * Collect this flow in your UI layer to update user-facing state:
     * ```kotlin
     * syncManager.syncState.collect { state ->
     *     // Update UI based on state
     * }
     * ```
     */
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Prevents concurrent sync operations
    private val syncMutex = Mutex()

    /**
     * Synchronizes all unacknowledged events from the sensor.
     *
     * This is the primary entry point for sync operations. It executes the full
     * sync workflow: bonding (if needed), connection, event transfer, and acknowledgment.
     *
     * ## Behavior
     * - **Concurrent calls**: Serialized via mutex - second call blocks until first completes
     * - **Retry logic**: Transient failures are retried per [retryPolicy]
     * - **State updates**: [syncState] is updated throughout the operation
     * - **Device cleanup**: GATT connection is always disconnected on completion
     *
     * ## Error Handling
     * Transient errors (connection failures, etc.) are retried automatically.
     * Permanent errors (device not bonded, etc.) fail immediately.
     *
     * @return [SyncResult.Success] with synced events, or [SyncResult.Failure] with error details
     * @throws CancellationException if the coroutine is cancelled
     */
    suspend fun sync(): SyncResult = syncMutex.withLock {
        try {
            _syncState.value = SyncState.Idle

            // Step 1: Ensure device is bonded
            ensureBonded()

            // Step 2: Establish connection
            val connection = establishConnection()

            // Step 3: Sync events
            val events = syncEvents(connection)

            _syncState.value = SyncState.Success(events.size)
            SyncResult.Success(events)

        } catch (e: SyncError) {
            _syncState.value = SyncState.Failed(e, e.isTransient)
            SyncResult.Failure(e)
        } catch (e: Exception) {
            val error = SyncError.InvalidState("Unexpected error: ${e.message}")
            _syncState.value = SyncState.Failed(error, false)
            SyncResult.Failure(error)
        }
    }

    /**
     * Ensures the device is bonded, with retry logic.
     *
     * @throws SyncError.BondingFailed if bonding fails after all retries
     * @throws SyncError.InvalidState if device is currently in BONDING state
     */
    private suspend fun ensureBonded() {
        when (sensor.bondState.value) {
            BondState.BONDED -> return
            BondState.BONDING -> {
                throw SyncError.InvalidState("Device is currently bonding")
            }
            BondState.NONE -> {
                _syncState.value = SyncState.Bonding
                retryStrategy.executeWithRetry(
                    operation = { sensor.bond() },
                    errorMapper = { SyncError.BondingFailed("Bond failed: $it") }
                )
            }
        }
    }

    /**
     * Establishes a GATT connection with retry logic
     */
    private suspend fun establishConnection(): GattConnection {
        // Verify device is bonded first
        if (sensor.bondState.value != BondState.BONDED) {
            throw SyncError.DeviceNotBonded("Cannot connect to unbonded device")
        }

        _syncState.value = SyncState.Connecting

        return retryStrategy.executeWithRetry(
            operation = { sensor.connect() },
            errorMapper = { SyncError.ConnectionFailed("Connection failed: $it") }
        )
    }

    /**
     * Syncs all events from the device using the transfer strategy.
     */
    private suspend fun syncEvents(connection: GattConnection): List<ActuationEvent> {
        // Read total event count
        val totalEvents = retryStrategy.executeWithRetry(
            operation = { connection.readEventCount() },
            errorMapper = { SyncError.ReadFailed("Failed to read event count: $it", 0) }
        )

        if (totalEvents == 0) {
            return emptyList()
        }

        // Delegate event transfer to strategy
        val events = transferStrategy.transferEvents(
            connection = connection,
            totalEvents = totalEvents,
            onProgress = { progress ->
                _syncState.value = SyncState.Syncing(progress)
            }
        )

        // Cleanup
        sensor.disconnect()

        return events
    }

    /**
     * Cancels any ongoing sync operation and resets to idle state.
     *
     * Disconnects from the device and cleans up resources. This is a best-effort
     * operation - if sync is between operations, cancellation may not be immediate.
     *
     * Safe to call even if no sync is in progress.
     */
    suspend fun cancelSync() {
        if (sensor.connectionState.value != ConnectionState.DISCONNECTED) {
            sensor.disconnect()
        }
        _syncState.value = SyncState.Idle
    }
}
