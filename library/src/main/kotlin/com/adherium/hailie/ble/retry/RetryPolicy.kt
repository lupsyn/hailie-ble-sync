package com.adherium.hailie.ble.retry

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Configuration for retry behavior with exponential backoff and jitter.
 *
 * Implements exponential backoff to handle transient BLE failures while preventing
 * retry storms through randomized jitter. This is critical for medical devices where
 * BLE radio contention and environmental interference are common.
 *
 * ## Algorithm
 * ```
 * delay = min(initialDelayMs * backoffMultiplier^attempt, maxDelayMs)
 * delay += jitter (random ±jitterFactor * delay)
 * ```
 *
 * ## Presets
 * - [DEFAULT]: Balanced 3 retries, 1s→30s backoff
 * - [AGGRESSIVE]: Fast 5 retries, 500ms→15s backoff (for time-critical ops)
 * - [CONSERVATIVE]: Cautious 3 retries, 2s→60s backoff (to avoid device overwhelm)
 *
 * ## Example Usage
 * ```kotlin
 * val syncManager = SyncManager(
 *     sensor = sensor,
 *     retryPolicy = RetryPolicy.AGGRESSIVE
 * )
 * ```
 *
 * @property maxRetries Maximum number of retry attempts (after initial attempt)
 * @property initialDelayMs Starting delay in milliseconds
 * @property maxDelayMs Maximum delay cap in milliseconds
 * @property backoffMultiplier Exponential growth factor (typically 1.5-2.5)
 * @property jitterFactor Randomization factor (0.0-0.5, typically 0.1)
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    val backoffMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.1
) {
    /**
     * Calculates delay for a given retry attempt with exponential backoff and jitter.
     *
     * @param attempt Zero-based attempt number (0 = first retry)
     * @return Delay in milliseconds before next retry
     */
    fun calculateDelay(attempt: Int): Long {
        val exponentialDelay = initialDelayMs * backoffMultiplier.pow(attempt.toDouble())
        val clampedDelay = min(exponentialDelay, maxDelayMs.toDouble())

        // Add jitter to prevent thundering herd
        val jitter = clampedDelay * jitterFactor * (Math.random() - 0.5)
        return (clampedDelay + jitter).toLong().coerceAtLeast(0)
    }

    /**
     * Suspends for the calculated delay based on the attempt number.
     *
     * @param attempt One-based attempt number (1 = first attempt, no delay)
     */
    suspend fun delayForAttempt(attempt: Int) {
        if (attempt > 0) {
            delay(calculateDelay(attempt - 1))
        }
    }

    companion object {
        /**
         * Aggressive retry policy for critical operations
         */
        val AGGRESSIVE = RetryPolicy(
            maxRetries = 5,
            initialDelayMs = 500L,
            maxDelayMs = 15000L,
            backoffMultiplier = 1.5
        )

        /**
         * Conservative retry policy to avoid overwhelming the device
         */
        val CONSERVATIVE = RetryPolicy(
            maxRetries = 3,
            initialDelayMs = 2000L,
            maxDelayMs = 60000L,
            backoffMultiplier = 2.5
        )

        /**
         * Default balanced retry policy
         */
        val DEFAULT = RetryPolicy()
    }
}
