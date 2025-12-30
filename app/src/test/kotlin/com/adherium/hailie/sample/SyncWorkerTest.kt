package com.adherium.hailie.sample

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for SyncWorker.
 *
 * These tests verify the Worker's behavior:
 * - Input data validation
 * - Output data creation
 * - Error handling and retry logic
 * - Integration with MockHailieSensor
 *
 * Note: We test with MockHailieSensor since SyncWorker uses it for the demo.
 * In production, you'd inject a real HailieSensor implementation.
 */
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var syncWorker: SyncWorker

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
    }

    @After
    fun tearDown() = clearAllMocks()

    // ========== HAPPY PATH TESTS ==========

    @Test
    fun `doWork returns success when sync completes successfully`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 25,
            SyncWorker.KEY_RETRY_POLICY to "DEFAULT",
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then - MockHailieSensor with failureRate=0.0 should succeed
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(25, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
        assertEquals("device-001", result.outputData.getString(SyncWorker.KEY_DEVICE_ID))
    }

    @Test
    fun `doWork reads device ID from input data`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "custom-device-123",
            SyncWorker.KEY_EVENT_COUNT to 10,
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals("custom-device-123", result.outputData.getString(SyncWorker.KEY_DEVICE_ID))
        assertEquals(10, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork handles empty event list successfully`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 0,
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(0, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    // ========== UNHAPPY PATH TESTS ==========

    @Test
    fun `doWork returns failure when device ID is missing`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_EVENT_COUNT to 25
            // No device ID
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Failure>(result)
        assertTrue(result.outputData.getString(SyncWorker.KEY_ERROR_MESSAGE)!!.contains("Device ID"))
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    fun `doWork uses DEFAULT retry policy when not specified`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 25,
            SyncWorker.KEY_CHUNK_SIZE to 50
            // No retry policy specified
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then - Should use DEFAULT policy and succeed
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(25, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork handles AGGRESSIVE retry policy from input data`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 20,
            SyncWorker.KEY_RETRY_POLICY to "AGGRESSIVE",
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(20, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork handles CONSERVATIVE retry policy from input data`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 15,
            SyncWorker.KEY_RETRY_POLICY to "CONSERVATIVE",
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(15, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork handles unknown retry policy gracefully`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 10,
            SyncWorker.KEY_RETRY_POLICY to "UNKNOWN_POLICY",
            SyncWorker.KEY_CHUNK_SIZE to 50
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then - Should default to DEFAULT policy and still work
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(10, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork uses default chunk size of 50 when not specified`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 30
            // No chunk size specified
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then - Should use default chunk size of 50
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(30, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork handles large event counts`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 500,
            SyncWorker.KEY_CHUNK_SIZE to 100
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(500, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }

    @Test
    fun `doWork with custom chunk size uses it correctly`() = runTest {
        // Given
        every { workerParams.inputData } returns workDataOf(
            SyncWorker.KEY_DEVICE_ID to "device-001",
            SyncWorker.KEY_EVENT_COUNT to 100,
            SyncWorker.KEY_CHUNK_SIZE to 20
        )
        syncWorker = SyncWorker(context, workerParams)

        // When
        val result = syncWorker.doWork()

        // Then
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(100, result.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0))
    }
}
