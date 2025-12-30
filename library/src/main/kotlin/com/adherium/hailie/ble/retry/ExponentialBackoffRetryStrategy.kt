package com.adherium.hailie.ble.retry

import com.adherium.hailie.ble.SyncError
import kotlinx.coroutines.withTimeout

/**
 * Retry strategy using exponential backoff with jitter.
 *
 * This implementation uses a [RetryPolicy] to configure exponential backoff timing
 * and applies timeouts to each operation attempt. It's designed for transient BLE
 * failures where retry attempts should be progressively delayed.
 *
 * ## Algorithm
 * For each attempt (0..maxRetries):
 * 1. Wait for exponential delay (with jitter) if attempt > 0
 * 2. Execute operation with timeout
 * 3. Return on success, retry on failure
 * 4. Throw SyncError if all retries exhausted
 *
 * @property retryPolicy Configuration for retry timing and limits
 * @property operationTimeoutMs Timeout for each individual operation attempt (default: 30s)
 *
 * @see RetryPolicy
 */
class ExponentialBackoffRetryStrategy(
    private val retryPolicy: RetryPolicy,
    private val operationTimeoutMs: Long = 30000L
) : RetryStrategy {

    override suspend fun <T> executeWithRetry(
        operation: suspend () -> Result<T>,
        errorMapper: (String) -> SyncError
    ): T {
        var lastError: Throwable? = null

        repeat(retryPolicy.maxRetries + 1) { attempt ->
            try {
                // Delay before retry (no delay on first attempt)
                retryPolicy.delayForAttempt(attempt)

                // Execute with timeout and return on success
                return withTimeout(operationTimeoutMs) {
                    operation()
                }.getOrThrow()

            } catch (e: Exception) {
                lastError = e
                // Continue to next retry attempt unless this was the last
            }
        }

        // All retries exhausted
        throw errorMapper(lastError?.message ?: "Unknown error")
    }
}
