package com.adherium.hailie.sample

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.adherium.hailie.ble.SyncManager
import com.adherium.hailie.ble.SyncResult
import com.adherium.hailie.ble.retry.RetryPolicy
import com.adherium.hailie.ble.sensor.ActuationEvent

/**
 * WorkManager Worker for background BLE sync operations.
 *
 * This worker handles long-running BLE sync operations in the background,
 * allowing users to leave the app while sync completes. It provides:
 *
 * - **Background execution**: Sync continues even if app is backgrounded
 * - **Progress tracking**: Reports sync progress via WorkInfo
 * - **Data persistence**: Saves acknowledged events incrementally
 * - **Automatic retry**: WorkManager handles retry logic for failed syncs
 * - **Battery optimization**: Uses constraints to sync when optimal
 *
 * ## Usage
 * ```kotlin
 * val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
 *     .setInputData(workDataOf(
 *         KEY_DEVICE_ID to "device-001"
 *     ))
 *     .setConstraints(Constraints.Builder()
 *         .setRequiresBatteryNotLow(true)
 *         .build())
 *     .build()
 *
 * WorkManager.getInstance(context).enqueue(syncRequest)
 * ```
 *
 * ## Progress Updates
 * Observe progress using WorkInfo:
 * ```kotlin
 * WorkManager.getInstance(context)
 *     .getWorkInfoByIdLiveData(syncRequest.id)
 *     .observe(this) { workInfo ->
 *         val progress = workInfo?.progress
 *         val currentOffset = progress?.getInt(KEY_PROGRESS_CURRENT, 0) ?: 0
 *         val totalEvents = progress?.getInt(KEY_PROGRESS_TOTAL, 0) ?: 0
 *     }
 * ```
 *
 * ## Performance Optimization
 * For better sync performance, consider:
 *
 * 1. **Increase chunk size**: Use chunkSize=100-200 for faster reads
 * 2. **Reduce retry delays**: Use RetryPolicy.AGGRESSIVE for time-critical syncs
 * 3. **Batch acknowledgments**: Increase ackBatchMultiplier to reduce BLE round trips
 * 4. **Parallel operations**: If multiple devices, use separate Workers in parallel
 *
 * Example with optimizations:
 * ```kotlin
 * val syncManager = SyncManager(
 *     sensor = sensor,
 *     retryPolicy = RetryPolicy.AGGRESSIVE,  // Faster retries
 *     chunkSize = 150,                       // Larger chunks
 *     operationTimeoutMs = 20000L            // Shorter timeout
 * )
 * ```
 *
 * @see SyncManager
 * @see androidx.work.WorkManager
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Device ID not provided"))

        Log.d(TAG, "Starting sync for device: $deviceId")

        return try {
            // TODO: In production, retrieve the actual HailieSensor from dependency injection
            // or a repository. For demo, we use MockHailieSensor.
            val sensor = MockHailieSensor(
                deviceId = deviceId,
                failureRate = 0.0,
                eventCount = inputData.getInt(KEY_EVENT_COUNT, 25)
            )

            val retryPolicy = inputData.getString(KEY_RETRY_POLICY)
                ?.let { policyName ->
                    when (policyName) {
                        "AGGRESSIVE" -> RetryPolicy.AGGRESSIVE
                        "CONSERVATIVE" -> RetryPolicy.CONSERVATIVE
                        else -> RetryPolicy.DEFAULT
                    }
                } ?: RetryPolicy.DEFAULT

            val syncManager = SyncManager(
                sensor = sensor,
                retryPolicy = retryPolicy,
                chunkSize = inputData.getInt(KEY_CHUNK_SIZE, 50)
            )

            // Perform sync with progress reporting and event persistence
            when (val syncResult = syncManager.sync(
                onEventsAcknowledged = { events ->
                    // Persist events immediately to prevent data loss
                    persistEvents(deviceId, events)
                },
                onProgress = { currentOffset, totalEvents, percentage ->
                    Log.d(TAG, "Sync progress: $currentOffset/$totalEvents ($percentage%)")
                    // Report progress to WorkManager for UI updates
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_CURRENT to currentOffset,
                            KEY_PROGRESS_TOTAL to totalEvents,
                            KEY_PROGRESS_PERCENTAGE to percentage
                        )
                    )
                }
            )) {
                is SyncResult.Success -> {
                    Log.d(TAG, "Sync successful: ${syncResult.events.size} events")
                    Result.success(
                        workDataOf(
                            KEY_EVENTS_SYNCED to syncResult.events.size,
                            KEY_DEVICE_ID to deviceId
                        )
                    )
                }

                is SyncResult.Failure -> {
                    Log.e(TAG, "Sync failed: ${syncResult.error.message}")
                    if (syncResult.error.isTransient) {
                        Result.retry()
                    } else {
                        Result.failure(
                            workDataOf(
                                KEY_ERROR_MESSAGE to syncResult.error.message,
                                KEY_DEVICE_ID to deviceId
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sync", e)
            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to "Unexpected error: ${e.message}",
                    KEY_DEVICE_ID to deviceId
                )
            )
        }
    }

    /**
     * Persists acknowledged events to durable storage.
     *
     * In production, this should:
     * - Insert events into Room database
     * - Upload to backend API
     * - Update local cache
     *
     * This method runs synchronously in the Worker thread, so database
     * operations are safe without additional coroutine context switching.
     */
    private fun persistEvents(deviceId: String, events: List<ActuationEvent>) {
        Log.d(TAG, "Persisting ${events.size} events for device $deviceId")

        // TODO: In production, implement actual persistence:
        // database.eventDao().insertAll(events)
        // or
        // repository.saveEvents(deviceId, events)

        Log.d(TAG, "Successfully persisted ${events.size} events")
    }

    companion object {
        private const val TAG = "SyncWorker"

        // Input data keys
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_EVENT_COUNT = "event_count"
        const val KEY_RETRY_POLICY = "retry_policy"
        const val KEY_CHUNK_SIZE = "chunk_size"

        // Progress data keys
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_PERCENTAGE = "progress_percentage"

        // Output data keys
        const val KEY_EVENTS_SYNCED = "events_synced"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}
