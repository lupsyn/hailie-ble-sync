package com.adherium.hailie.ble

import com.adherium.hailie.ble.sensor.ActuationEvent

/**
 * Represents the current state of a sync operation.
 *
 * The sync operation follows a defined state machine:
 * ```
 * Idle → Bonding → Connecting → Syncing → Success/Failed → Idle
 * ```
 *
 * ## Usage
 * Observe state changes via [SyncManager.syncState] to update UI:
 * ```kotlin
 * syncManager.syncState.collect { state ->
 *     when (state) {
 *         is SyncState.Idle -> showReadyUI()
 *         is SyncState.Syncing -> updateProgress(state.progress.percentage)
 *         is SyncState.Success -> showSuccess(state.eventsSynced)
 *         is SyncState.Failed -> showError(state.error, state.canRetry)
 *         // ...
 *     }
 * }
 * ```
 */
sealed class SyncState {
    /** Ready to begin sync operation */
    data object Idle : SyncState()

    /** Establishing Bluetooth bonding (pairing) with device */
    data object Bonding : SyncState()

    /** Opening GATT connection to bonded device */
    data object Connecting : SyncState()

    /**
     * Actively transferring event data from device.
     *
     * @property progress Current sync progress with percentage calculation
     */
    data class Syncing(val progress: SyncProgress) : SyncState()

    /**
     * Sync completed successfully.
     *
     * @property eventsSynced Total number of events synchronized
     */
    data class Success(val eventsSynced: Int) : SyncState()

    /**
     * Sync failed with an error.
     *
     * @property error The error that caused the failure
     * @property canRetry Whether the operation can be retried (based on error type)
     */
    data class Failed(val error: SyncError, val canRetry: Boolean) : SyncState()

    /**
     * Progress information during sync operation.
     *
     * @property currentOffset Number of events synced so far
     * @property totalEvents Total number of events to sync
     */
    data class SyncProgress(
        val currentOffset: Int,
        val totalEvents: Int
    ) {
        /**
         * Completion percentage (0-100).
         * Returns 0 if totalEvents is 0.
         */
        val percentage: Int
            get() = if (totalEvents > 0) (currentOffset * 100) / totalEvents else 0
    }
}

/**
 * Categorizes sync errors for appropriate handling and recovery.
 *
 * Errors are classified as either transient (retriable) or permanent (non-retriable).
 * This distinction enables UIs to present appropriate recovery actions to users.
 *
 * ## Error Classification
 * - **Transient**: Temporary failures due to BLE radio issues, device state, etc.
 *   May succeed on retry. Example: ConnectionFailed, ReadFailed
 * - **Permanent**: Logic errors or invalid states that won't improve with retry.
 *   Require user action or state correction. Example: DeviceNotBonded, Timeout
 *
 * ## Usage
 * ```kotlin
 * when (result) {
 *     is SyncResult.Failure -> {
 *         if (result.error.isTransient) {
 *             showRetryButton()
 *         } else {
 *             showErrorAndRequireUserFix(result.error)
 *         }
 *     }
 * }
 * ```
 */
sealed class SyncError : Exception() {
    // ========== Transient Errors (Retriable) ==========

    /** Bluetooth bonding (pairing) operation failed - may succeed on retry */
    data class BondingFailed(override val message: String) : SyncError()

    /** GATT connection failed - may succeed on retry */
    data class ConnectionFailed(override val message: String) : SyncError()

    /** GATT connection lost during operation - reconnection may succeed */
    data class ConnectionLost(override val message: String) : SyncError()

    /**
     * Failed to read events from device - may succeed on retry.
     *
     * @property offset The event offset where the read failed (for partial recovery)
     */
    data class ReadFailed(override val message: String, val offset: Int) : SyncError()

    /** Failed to acknowledge events on device - may succeed on retry */
    data class AcknowledgeFailed(override val message: String) : SyncError()

    // ========== Permanent Errors (Non-Retriable) ==========

    /** Device is not bonded - user must manually bond in Bluetooth settings */
    data class DeviceNotBonded(override val message: String) : SyncError()

    /** Operation attempted in invalid state - indicates logic error */
    data class InvalidState(override val message: String) : SyncError()

    /** Operation exceeded timeout deadline - device may be unresponsive */
    data class Timeout(override val message: String) : SyncError()

    /**
     * Whether this error is transient and may resolve with retry.
     *
     * @return true for transient errors, false for permanent errors
     */
    val isTransient: Boolean
        get() = when (this) {
            is BondingFailed, is ConnectionFailed, is ConnectionLost,
            is ReadFailed, is AcknowledgeFailed -> true
            is DeviceNotBonded, is InvalidState, is Timeout -> false
        }
}

/**
 * Result of a sync operation returned by [SyncManager.sync].
 *
 * ## Usage
 * ```kotlin
 * when (val result = syncManager.sync()) {
 *     is SyncResult.Success -> {
 *         persistEvents(result.events)
 *         showSuccess("Synced ${result.events.size} events")
 *     }
 *     is SyncResult.Failure -> {
 *         logError(result.error)
 *         showError(result.error.message)
 *     }
 * }
 * ```
 */
sealed class SyncResult {
    /**
     * Sync completed successfully.
     *
     * @property events List of actuation events retrieved from the device
     */
    data class Success(val events: List<ActuationEvent>) : SyncResult()

    /**
     * Sync failed with an error.
     *
     * @property error The error that caused the failure
     */
    data class Failure(val error: SyncError) : SyncResult()
}
