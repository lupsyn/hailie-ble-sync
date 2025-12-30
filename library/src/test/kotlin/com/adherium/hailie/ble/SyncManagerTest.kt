package com.adherium.hailie.ble

import com.adherium.hailie.ble.retry.RetryPolicy
import com.adherium.hailie.ble.sensor.ActuationEvent
import com.adherium.hailie.ble.sensor.BondState
import com.adherium.hailie.ble.sensor.ConnectionState
import com.adherium.hailie.ble.sensor.GattConnection
import com.adherium.hailie.ble.sensor.HailieSensor
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncManagerTest {

    private lateinit var sensor: HailieSensor
    private lateinit var connection: GattConnection
    private lateinit var bondStateFlow: MutableStateFlow<BondState>
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionState>
    private lateinit var syncManager: SyncManager

    // Test data
    private val testEvents = listOf(
        ActuationEvent("1", Instant.now(), "device-1", 2),
        ActuationEvent("2", Instant.now(), "device-1", 1),
        ActuationEvent("3", Instant.now(), "device-1", 2)
    )

    @Before
    fun setup() {
        sensor = mockk()
        connection = mockk()
        bondStateFlow = MutableStateFlow(BondState.BONDED)
        connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

        every { sensor.bondState } returns bondStateFlow
        every { sensor.connectionState } returns connectionStateFlow

        syncManager = SyncManager(
            sensor = sensor,
            retryPolicy = RetryPolicy(
                maxRetries = 2,
                initialDelayMs = 10L, // Fast retries for tests
                maxDelayMs = 50L,
                jitterFactor = 0.0 // No jitter for predictable testing
            ),
            chunkSize = 2,
            operationTimeoutMs = 1000L // Short timeout for tests
        )
    }

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `sync succeeds with bonded device and valid events`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        assertEquals(3, result.events.size)
        assertEquals(testEvents, result.events)

        coVerify {
            sensor.connect()
            connection.readEventCount()
            connection.readEvents(0, 2)
            connection.readEvents(2, 1)
            connection.acknowledgeEvents(3)
            sensor.disconnect()
        }

        assertEquals(SyncState.Success(3), syncManager.syncState.value)
    }

    @Test
    fun `sync handles zero events gracefully`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(0)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        assertTrue(result.events.isEmpty())

        coVerify(exactly = 0) {
            connection.readEvents(any(), any())
            connection.acknowledgeEvents(any())
        }
    }

    @Test
    fun `sync reads events in chunks for performance`() = runTest {
        // Given
        val manyEvents = (1..100).map {
            ActuationEvent("$it", Instant.now(), "device-1", 1)
        }

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(100)
        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(manyEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        assertEquals(100, result.events.size)

        // Should have read in chunks of 2
        coVerify(exactly = 50) { connection.readEvents(any(), any()) }
    }

    @Test
    fun `sync bonds device when not bonded`() = runTest {
        // Given
        bondStateFlow.value = BondState.NONE

        coEvery { sensor.bond() } coAnswers {
            bondStateFlow.value = BondState.BONDED
            Result.success(Unit)
        }
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(0)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        coVerify { sensor.bond() }
    }

    @Test
    fun `sync fails after max bond retries`() = runTest {
        // Given
        bondStateFlow.value = BondState.NONE

        coEvery { sensor.bond() } returns Result.failure(Exception("Bond failed"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.BondingFailed>(result.error)
        coVerify(exactly = 3) { sensor.bond() } // Initial + 2 retries
    }

    @Test
    fun `sync fails when device is currently bonding`() = runTest {
        // Given
        bondStateFlow.value = BondState.BONDING

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.InvalidState>(result.error)
        assertEquals(result.error.message.contains("bonding"), true)
    }

    @Test
    fun `sync retries connection on failure`() = runTest {
        // Given
        coEvery { sensor.connect() } returnsMany listOf(
            Result.failure(Exception("Connection failed")),
            Result.success(connection)
        )
        coEvery { connection.readEventCount() } returns Result.success(0)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        coVerify(exactly = 2) { sensor.connect() }
    }

    @Test
    fun `sync fails when device is not bonded`() = runTest {
        // Given
        bondStateFlow.value = BondState.NONE
        coEvery { sensor.bond() } returns Result.failure(Exception("Cannot bond"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.BondingFailed>(result.error)
    }

    @Test
    fun `sync fails after max connection retries`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.failure(Exception("Connection failed"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.ConnectionFailed>(result.error)
        coVerify(exactly = 3) { sensor.connect() }
    }

    @Test
    fun `sync retries on read failure`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returnsMany listOf(
            Result.failure(Exception("Read failed")),
            Result.success(3)
        )
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        coVerify(atLeast = 2) { connection.readEventCount() }
    }

    @Test
    fun `sync retries on acknowledge failure`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(2)
        coEvery { connection.readEvents(any(), any()) } returns Result.success(testEvents.take(2))
        coEvery { connection.acknowledgeEvents(any()) } returnsMany listOf(
            Result.failure(Exception("Ack failed")),
            Result.success(Unit)
        )
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Success>(result)
        coVerify(atLeast = 2) { connection.acknowledgeEvents(any()) }
    }

    @Test
    fun `sync fails after max read retries`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.failure(Exception("Read failed"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.ReadFailed>(result.error)
    }

    @Test
    fun `sync transitions through correct states`() = runTest {
        // Given
        val states = mutableListOf<SyncState>()

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(1)
        coEvery { connection.readEvents(any(), any()) } returns Result.success(testEvents.take(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // Collect states
        states.add(syncManager.syncState.value)

        // When
        syncManager.sync()

        // Then - verify state progression
        // Should go: Idle -> Connecting -> Syncing -> Success
        assertEquals(SyncState.Idle, states[0])
        assertIs<SyncState.Success>(syncManager.syncState.value)
    }

    @Test
    fun `sync sets failed state with error details`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.failure(Exception("Connection error"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        val state = syncManager.syncState.value
        assertIs<SyncState.Failed>(state)
        assertIs<SyncError.ConnectionFailed>(state.error)
        assertTrue(state.canRetry) // Connection errors are transient
    }

    @Test
    fun `concurrent sync calls are serialized`() = runTest {
        // Given
        coEvery { sensor.connect() } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(connection)
        }
        coEvery { connection.readEventCount() } returns Result.success(0)
        coEvery { sensor.disconnect() } just Runs

        // When - launch two syncs concurrently
        val job1 = this.launch { syncManager.sync() }
        val job2 = this.launch { syncManager.sync() }

        job1.join()
        job2.join()

        // Then - should have connected twice (not concurrent)
        coVerify(exactly = 2) { sensor.connect() }
    }

    @Test
    fun `connection errors are marked as transient`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.failure(Exception("Connection failed"))

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertTrue(result.error.isTransient)
        val state = syncManager.syncState.value as SyncState.Failed
        assertTrue(state.canRetry)
    }

    @Test
    fun `invalid state errors are marked as non-transient`() = runTest {
        // Given
        bondStateFlow.value = BondState.BONDING

        // When
        val result = syncManager.sync()

        // Then
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.InvalidState>(result.error)
        assertTrue(!result.error.isTransient)
    }

    @Test
    fun `cancel disconnects and resets state`() = runTest {
        // Given
        connectionStateFlow.value = ConnectionState.CONNECTED
        coEvery { sensor.disconnect() } just Runs

        // When
        syncManager.cancelSync()

        // Then
        assertEquals(SyncState.Idle, syncManager.syncState.value)
        coVerify { sensor.disconnect() }
    }

    @Test
    fun `sync invokes callback after events are acknowledged`() = runTest {
        // Given
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = { events ->
                acknowledgedBatches.add(events)
            }
        )

        // Then
        assertIs<SyncResult.Success>(result)

        // Callback should have been invoked once with all 3 events
        // (chunkSize=2, ackBatchMultiplier=2, so ackBatchSize=4, meaning all 3 fit in one batch)
        assertEquals(1, acknowledgedBatches.size)
        assertEquals(3, acknowledgedBatches[0].size)
        assertEquals(testEvents, acknowledgedBatches[0])
    }

    @Test
    fun `sync invokes callback multiple times for multiple batches`() = runTest {
        // Given
        val manyEvents = (1..10).map {
            ActuationEvent("$it", Instant.now(), "device-1", 1)
        }
        val acknowledgedBatches = mutableListOf<List<ActuationEvent>>()

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(10)
        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(manyEvents.subList(offset, minOf(offset + count, manyEvents.size)))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = { events ->
                acknowledgedBatches.add(events)
            }
        )

        // Then
        assertIs<SyncResult.Success>(result)

        // With chunkSize=2 and ackBatchMultiplier=2, ackBatchSize=4
        // 10 events should trigger: batch of 4, batch of 4, batch of 2
        assertEquals(3, acknowledgedBatches.size)
        assertEquals(4, acknowledgedBatches[0].size)
        assertEquals(4, acknowledgedBatches[1].size)
        assertEquals(2, acknowledgedBatches[2].size)

        // All events should have been delivered
        val allAcknowledged = acknowledgedBatches.flatten()
        assertEquals(10, allAcknowledged.size)
        assertEquals(manyEvents, allAcknowledged)
    }

    // ========== CALLBACK ERROR HANDLING TESTS ==========

    @Test
    fun `sync fails if callback throws exception during persistence`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        var callbackInvoked = false

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = { events ->
                callbackInvoked = true
                throw IllegalStateException("Database write failed!")
            }
        )

        // Then - sync should fail with the callback exception
        assertIs<SyncResult.Failure>(result)
        assertIs<SyncError.InvalidState>(result.error)
        assertTrue(callbackInvoked, "Callback should have been invoked")
        assertTrue(result.error.message!!.contains("Unexpected error"))
    }

    @Test
    fun `sync callback receives events in correct order across multiple batches`() = runTest {
        // Given
        val orderedEvents = (1..8).map {
            ActuationEvent("event-$it", Instant.now(), "device-$it", it)
        }
        val receivedEvents = mutableListOf<ActuationEvent>()

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(8)
        coEvery { connection.readEvents(any(), any()) } answers {
            val offset = firstArg<Int>()
            val count = secondArg<Int>()
            Result.success(orderedEvents.subList(offset, offset + count))
        }
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = { events ->
                receivedEvents.addAll(events)
            }
        )

        // Then
        assertIs<SyncResult.Success>(result)
        assertEquals(8, receivedEvents.size)
        // Verify events are in the same order as sent
        assertEquals(orderedEvents, receivedEvents)
    }

    @Test
    fun `sync callback is not invoked if connection fails before reading events`() = runTest {
        // Given
        var callbackInvoked = false
        coEvery { sensor.connect() } returns Result.failure(Exception("Connection failed"))

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = {
                callbackInvoked = true
            }
        )

        // Then
        assertIs<SyncResult.Failure>(result)
        assertTrue(!callbackInvoked, "Callback should not be invoked if connection fails")
    }

    @Test
    fun `sync callback is not invoked if read fails before acknowledgment`() = runTest {
        // Given
        var callbackInvoked = false
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(any(), any()) } returns Result.failure(Exception("Read failed"))

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = {
                callbackInvoked = true
            }
        )

        // Then
        assertIs<SyncResult.Failure>(result)
        assertTrue(!callbackInvoked, "Callback should not be invoked if read fails")
    }

    @Test
    fun `sync without callback parameter uses default empty callback`() = runTest {
        // Given
        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When - sync without callback parameter (uses default)
        val result = syncManager.sync()

        // Then - should succeed even without explicit callback
        assertIs<SyncResult.Success>(result)
        assertEquals(3, result.events.size)
    }

    // ========== CONCURRENT SYNC EDGE CASES ==========

    @Test
    fun `concurrent sync calls with callbacks are properly serialized`() = runTest {
        // Given
        val callback1Events = mutableListOf<ActuationEvent>()
        val callback2Events = mutableListOf<ActuationEvent>()

        coEvery { sensor.connect() } coAnswers {
            kotlinx.coroutines.delay(50) // Simulate slow connection
            Result.success(connection)
        }
        coEvery { connection.readEventCount() } returns Result.success(2)
        coEvery { connection.readEvents(any(), any()) } returns Result.success(testEvents.take(2))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When - launch two syncs concurrently with different callbacks
        val job1 = launch {
            syncManager.sync(onEventsAcknowledged = { callback1Events.addAll(it) })
        }
        val job2 = launch {
            syncManager.sync(onEventsAcknowledged = { callback2Events.addAll(it) })
        }

        job1.join()
        job2.join()

        // Then - both should have completed with their own callbacks
        assertEquals(2, callback1Events.size)
        assertEquals(2, callback2Events.size)

        // Should have connected twice (serialized, not concurrent)
        coVerify(exactly = 2) { sensor.connect() }
    }

    @Test
    fun `sync returns all events even with callback parameter`() = runTest {
        // Given
        val callbackEvents = mutableListOf<ActuationEvent>()

        coEvery { sensor.connect() } returns Result.success(connection)
        coEvery { connection.readEventCount() } returns Result.success(3)
        coEvery { connection.readEvents(0, 2) } returns Result.success(testEvents.take(2))
        coEvery { connection.readEvents(2, 1) } returns Result.success(testEvents.takeLast(1))
        coEvery { connection.acknowledgeEvents(any()) } returns Result.success(Unit)
        coEvery { sensor.disconnect() } just Runs

        // When
        val result = syncManager.sync(
            onEventsAcknowledged = { callbackEvents.addAll(it) }
        )

        // Then
        assertIs<SyncResult.Success>(result)

        // Both result AND callback should have all events
        assertEquals(3, result.events.size)
        assertEquals(3, callbackEvents.size)
        assertEquals(testEvents, result.events)
        assertEquals(testEvents, callbackEvents)
    }
}

