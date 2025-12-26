package com.adherium.hailie.ble

import com.adherium.hailie.ble.retry.RetryPolicy
import org.junit.Test
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `calculateDelay increases exponentially`() {
        // Given
        val policy = RetryPolicy(
            initialDelayMs = 100L,
            backoffMultiplier = 2.0,
            maxDelayMs = 10000L,
            jitterFactor = 0.0 // No jitter for predictable testing
        )

        // When
        val delay0 = policy.calculateDelay(0)
        val delay1 = policy.calculateDelay(1)
        val delay2 = policy.calculateDelay(2)

        // Then - should roughly double (without jitter)
        assertTrue(delay0 >= 90 && delay0 <= 110) // ~100ms
        assertTrue(delay1 >= 180 && delay1 <= 220) // ~200ms
        assertTrue(delay2 >= 360 && delay2 <= 440) // ~400ms
    }

    @Test
    fun `calculateDelay respects max delay`() {
        // Given
        val policy = RetryPolicy(
            initialDelayMs = 1000L,
            backoffMultiplier = 10.0,
            maxDelayMs = 5000L,
            jitterFactor = 0.0
        )

        // When
        val delay = policy.calculateDelay(5) // Would be 100,000ms without cap

        // Then
        assertTrue(delay <= 5000L)
    }

    @Test
    fun `calculateDelay adds jitter for randomness`() {
        // Given
        val policy = RetryPolicy(
            initialDelayMs = 1000L,
            backoffMultiplier = 2.0,
            jitterFactor = 0.2 // 20% jitter
        )

        // When - calculate multiple times to check for variation
        val delays = (0..10).map { policy.calculateDelay(1) }

        // Then - should have some variance due to jitter
        val uniqueDelays = delays.toSet()
        assertTrue(uniqueDelays.size > 1, "Jitter should produce different delays")
    }

    @Test
    fun `aggressive policy has shorter delays`() {
        // Given
        val aggressive = RetryPolicy.AGGRESSIVE
        val default = RetryPolicy.DEFAULT

        // When
        val aggressiveDelay = aggressive.calculateDelay(1)
        val defaultDelay = default.calculateDelay(1)

        // Then
        assertTrue(aggressiveDelay < defaultDelay)
    }

    @Test
    fun `conservative policy has longer delays`() {
        // Given
        val conservative = RetryPolicy.CONSERVATIVE
        val default = RetryPolicy.DEFAULT

        // When
        val conservativeDelay = conservative.calculateDelay(1)
        val defaultDelay = default.calculateDelay(1)

        // Then
        assertTrue(conservativeDelay > defaultDelay)
    }
}
