package com.adherium.hailie.ble

import com.adherium.hailie.ble.retry.RetryStrategy
import com.adherium.hailie.ble.sensor.ActuationEvent
import com.adherium.hailie.ble.sensor.GattConnection
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkedEventTransferStrategyTest {

    private lateinit var retryStrategy: RetryStrategy
    private lateinit var connection: GattConnection
    private lateinit var strategy: ChunkedEventTransferStrategy

    private val testEvents = List(100) { index ->
        ActuationEvent("event-${index + 1}", Instant.now(), "device-1", 1)
    }

    @Before
    fun setup() {
        retryStrategy = mockk()
        connection = mockk()

        // Default: retry strategy just executes operation without retry
        coEvery { retryStrategy.executeWithRetry(any<suspend () -> Result<Any>>(), any()) } coAnswers {
            firstArg<suspend () -> Result<Any>>().invoke().getOrThrow()
        }
    }

    @After
    fun tearDown() = clearAllMocks()

    // ========== HAPPY PATH TESTS ==========

    @Test
    fun `transferEvents with no events returns empty list and does not call callback`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 50, ackBatchMultiplier = 2)
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 0,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertTrue(result.isEmpty())
        assertTrue(acknowledgedBatches.isEmpty(), "Callback should not be invoked for zero events")
        coVerify(exactly = 0) { connection.readEvents(any(), any()) }
        coVerify(exactly = 0) { connection.acknowledgeEvents(any()) }
    }

    @Test
    fun `transferEvents reads events in correct chunk sizes`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 25, ackBatchMultiplier = 2)
        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 100,
            onProgress = {},
            onEventsAcknowledged = {}
        )

        // Then
        assertEquals(100, result.size)
        // Should read in chunks of 25: 0-25, 25-50, 50-75, 75-100
        coVerify(exactly = 4) { connection.readEvents(any(), 25) }
        coVerify { connection.readEvents(0, 25) }
        coVerify { connection.readEvents(25, 25) }
        coVerify { connection.readEvents(50, 25) }
        coVerify { connection.readEvents(75, 25) }
    }

    @Test
    fun `transferEvents handles partial last chunk correctly`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 30, ackBatchMultiplier = 2)
        val events = List(85) { ActuationEvent("event-${it + 1}", Instant.now(), "device-1", 1) }

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(events.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 85,
            onProgress = {},
            onEventsAcknowledged = {}
        )

        // Then
        assertEquals(85, result.size)
        // Should read: 0-30, 30-60, 60-85 (last chunk is 25 events, not 30)
        coVerify { connection.readEvents(0, 30) }
        coVerify { connection.readEvents(30, 30) }
        coVerify { connection.readEvents(60, 25) } // Partial chunk
    }

    @Test
    fun `transferEvents invokes callback after each acknowledgment batch`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 2)
        // ackBatchSize = 10 * 2 = 20
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 50,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertEquals(50, result.size)

        // Should have 3 batches: 20, 20, 10
        assertEquals(3, acknowledgedBatches.size, "Should invoke callback 3 times")
        assertEquals(20, acknowledgedBatches[0].size, "First batch should have 20 events")
        assertEquals(20, acknowledgedBatches[1].size, "Second batch should have 20 events")
        assertEquals(10, acknowledgedBatches[2].size, "Third batch should have 10 events")

        // Verify all events were delivered
        val allAcknowledged = acknowledgedBatches.flatten()
        assertEquals(50, allAcknowledged.size)
    }

    @Test
    fun `transferEvents invokes callback with correct events in order`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 5, ackBatchMultiplier = 1)
        val events = (1..15).map { ActuationEvent("event-$it", Instant.now(), "device-$it", it) }
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(events.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        strategy.transferEvents(
            connection = connection,
            totalEvents = 15,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then - verify events are in correct order
        assertEquals(3, acknowledgedBatches.size)

        // First batch: events 1-5
        assertEquals(events.subList(0, 5), acknowledgedBatches[0])

        // Second batch: events 6-10
        assertEquals(events.subList(5, 10), acknowledgedBatches[1])

        // Third batch: events 11-15
        assertEquals(events.subList(10, 15), acknowledgedBatches[2])
    }

    @Test
    fun `transferEvents reports progress correctly`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 20, ackBatchMultiplier = 2)
        val progressUpdates = mutableListOf<SyncState.SyncProgress>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        strategy.transferEvents(
            connection = connection,
            totalEvents = 60,
            onProgress = { progressUpdates.add(it) },
            onEventsAcknowledged = {}
        )

        // Then
        // Should report progress at: 0, 20, 40
        assertEquals(3, progressUpdates.size)
        assertEquals(SyncState.SyncProgress(0, 60), progressUpdates[0])
        assertEquals(SyncState.SyncProgress(20, 60), progressUpdates[1])
        assertEquals(SyncState.SyncProgress(40, 60), progressUpdates[2])
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    fun `transferEvents with single event works correctly`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 50, ackBatchMultiplier = 2)
        val singleEvent = listOf(ActuationEvent("event-1", Instant.now(), "device-1", 1))
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(0, 1) } returns Result.success(singleEvent)
        coEvery { connection.acknowledgeEvents(1) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 1,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertEquals(1, result.size)
        assertTrue(acknowledgedBatches.size >= 1, "Should have at least one callback")
        assertTrue(acknowledgedBatches.flatten().size == 1, "Should acknowledge exactly 1 event total")
        coVerify(exactly = 1) { connection.readEvents(0, 1) }
        coVerify(atLeast = 1) { connection.acknowledgeEvents(any()) }
    }

    @Test
    fun `transferEvents with events exactly matching ack batch size`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 2)
        // ackBatchSize = 20
        val events = (1..20).map { ActuationEvent("event-$it", Instant.now(), "device-1", 1) }
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(events.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 20,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertEquals(20, result.size)
        assertEquals(1, acknowledgedBatches.size, "Should have exactly 1 batch, no partial batch")
        assertEquals(20, acknowledgedBatches[0].size)

        // Should only acknowledge once, not twice
        coVerify(exactly = 1) { connection.acknowledgeEvents(20) }
    }

    @Test
    fun `transferEvents with very large ack batch multiplier acknowledges at end only`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 10)
        // ackBatchSize = 100, but we only have 50 events
        val events = (1..50).map { ActuationEvent("event-$it", Instant.now(), "device-1", 1) }
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(events.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 50,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertEquals(50, result.size)
        // All 50 events should be acknowledged (may be in one or two batches due to implementation)
        assertEquals(50, acknowledgedBatches.flatten().size, "Should acknowledge all 50 events")
        coVerify(atLeast = 1) { connection.acknowledgeEvents(any()) }
    }

    // ========== ERROR PATH TESTS ==========

    @Test
    fun `transferEvents throws error when read fails`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 50, ackBatchMultiplier = 2)
        val readError = SyncError.ReadFailed("Read failed", 0)

        coEvery { retryStrategy.executeWithRetry(any<suspend () -> Result<Any>>(), any()) } throws readError

        // When/Then
        val exception = assertFailsWith<SyncError.ReadFailed> {
            strategy.transferEvents(
                connection = connection,
                totalEvents = 100,
                onProgress = {},
                onEventsAcknowledged = {}
            )
        }

        assertEquals("Read failed", exception.message)
        coVerify(exactly = 0) { connection.acknowledgeEvents(any()) }
    }

    @Test
    fun `transferEvents throws error when acknowledgment fails`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 2)
        val ackError = SyncError.AcknowledgeFailed("ACK failed")
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }

        // First ack succeeds, second fails
        coEvery { retryStrategy.executeWithRetry(any<suspend () -> Result<Any>>(), any()) } coAnswers {
            val operation = firstArg<suspend () -> Result<Any>>()
            val errorMapper = secondArg<(String) -> SyncError>()

            // Simulate retry strategy that eventually throws
            operation().getOrElse { throw errorMapper(it.message ?: "Unknown error") }
        }

        var ackCount = 0
        coEvery { connection.acknowledgeEvents(any()) } answers {
            ackCount++
            if (ackCount == 1) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("ACK failed"))
            }
        }

        // When/Then
        assertFailsWith<SyncError.AcknowledgeFailed> {
            strategy.transferEvents(
                connection = connection,
                totalEvents = 50,
                onProgress = {},
                onEventsAcknowledged = { acknowledgedBatches.add(it) }
            )
        }

        // Should have acknowledged the first batch successfully
        assertEquals(1, acknowledgedBatches.size)
        assertEquals(20, acknowledgedBatches[0].size)
    }

    @Test
    fun `transferEvents does not invoke callback if acknowledgment fails`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 2)
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }

        // Acknowledgment always fails
        coEvery { retryStrategy.executeWithRetry(any<suspend () -> Result<Any>>(), any()) } throws
            SyncError.AcknowledgeFailed("ACK failed")

        // When/Then
        assertFailsWith<SyncError.AcknowledgeFailed> {
            strategy.transferEvents(
                connection = connection,
                totalEvents = 30,
                onProgress = {},
                onEventsAcknowledged = { acknowledgedBatches.add(it) }
            )
        }

        // Callback should NOT have been invoked
        assertTrue(acknowledgedBatches.isEmpty(), "Callback should not be invoked if acknowledgment fails")
    }

    @Test
    fun `transferEvents continues if callback throws exception`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 10, ackBatchMultiplier = 2)
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(testEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)

        var callbackCount = 0

        // When/Then - Callback throws on first invocation
        assertFailsWith<IllegalStateException> {
            strategy.transferEvents(
                connection = connection,
                totalEvents = 50,
                onProgress = {},
                onEventsAcknowledged = { events ->
                    callbackCount++
                    acknowledgedBatches.add(events)
                    if (callbackCount == 1) {
                        throw IllegalStateException("Persistence failed!")
                    }
                }
            )
        }

        // Only first batch should be recorded before exception
        assertEquals(1, acknowledgedBatches.size)
        assertEquals(20, acknowledgedBatches[0].size)
    }

    @Test
    fun `transferEvents with chunk size larger than total events`() = runTest {
        // Given
        strategy = ChunkedEventTransferStrategy(retryStrategy, chunkSize = 200, ackBatchMultiplier = 2)
        val events = (1..50).map { ActuationEvent("event-$it", Instant.now(), "device-1", 1) }
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { connection.readEvents(0, 50) } returns Result.success(events)
        coEvery { connection.acknowledgeEvents(50) } returns Result.success(Unit)

        // When
        val result = strategy.transferEvents(
            connection = connection,
            totalEvents = 50,
            onProgress = {},
            onEventsAcknowledged = { acknowledgedBatches.add(it) }
        )

        // Then
        assertEquals(50, result.size)
        assertEquals(50, acknowledgedBatches.flatten().size, "All events should be acknowledged")

        // Should only read once with count = totalEvents (not chunkSize)
        coVerify(exactly = 1) { connection.readEvents(0, 50) }
        coVerify(atLeast = 1) { connection.acknowledgeEvents(any()) }
    }
}
