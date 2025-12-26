package com.adherium.hailie.ble.retry

import com.adherium.hailie.ble.SyncError

/**
 * Strategy for executing operations with retry logic.
 *
 * Implementations define how operations should be retried in case of failures,
 * including delay calculations, timeout handling, and error mapping.
 *
 * ## SOLID Principles
 * This interface follows:
 * - **Single Responsibility**: Only concerned with retry logic
 * - **Open/Closed**: New retry strategies can be added without modifying existing code
 * - **Dependency Inversion**: Clients depend on abstraction, not concrete implementations
 *
 * ## Example Implementation
 * ```kotlin
 * class ExponentialBackoffRetryStrategy(
 *     private val retryPolicy: RetryPolicy,
 *     private val timeoutMs: Long = 30000L
 * ) : RetryStrategy {
 *     override suspend fun <T> executeWithRetry(
 *         operation: suspend () -> Result<T>,
 *         errorMapper: (String) -> SyncError
 *     ): T {
 *         // Implementation with exponential backoff
 *     }
 * }
 * ```
 *
 * @see ExponentialBackoffRetryStrategy
 */
interface RetryStrategy {
    /**
     * Executes an operation with retry logic.
     *
     * Attempts the operation multiple times according to the strategy's policy.
     * Each retry may be delayed, and the operation may be subject to timeouts.
     *
     * @param T The type of result returned by the operation
     * @param operation Suspending function that returns a Result<T>
     * @param errorMapper Function to convert error message to SyncError
     * @return The successful result of type T
     * @throws com.adherium.hailie.ble.SyncError If all retry attempts fail (mapped via errorMapper)
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> Result<T>,
        errorMapper: (String) -> SyncError
    ): T
}
