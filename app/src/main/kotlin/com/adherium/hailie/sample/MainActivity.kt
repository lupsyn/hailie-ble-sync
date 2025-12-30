package com.adherium.hailie.sample

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/**
 * Sample app demonstrating Hailie BLE Sync Manager integration with WorkManager.
 *
 * This demonstrates:
 * - Background sync with WorkManager
 * - Work observation and progress tracking
 * - Sync execution with persistent event storage
 * - UI updates based on work status
 * - Critical: Event persistence callback to prevent data loss
 *
 * ## Background Sync
 * Sync operations run in a background Worker, allowing users to leave the app
 * while sync completes. This provides better UX for long-running BLE operations.
 *
 * ## Data Safety
 * Events are persisted incrementally via the [onEventsAcknowledged] callback
 * in the Worker to prevent data loss if the app crashes mid-sync.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var syncButton: Button
    private lateinit var eventsText: TextView

    private var currentWorkId: UUID? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        syncButton = findViewById(R.id.syncButton)
        eventsText = findViewById(R.id.eventsText)

        // Sync button click
        syncButton.setOnClickListener {
            startBackgroundSync()
        }

        statusText.text = "Ready to sync"
    }

    /**
     * Starts a background sync operation using WorkManager.
     *
     * This enqueues a SyncWorker that will run in the background, allowing
     * the user to leave the app while sync completes.
     */
    private fun startBackgroundSync() {
        // Create work request with input data
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_DEVICE_ID to "demo-hailie-001",
                    SyncWorker.KEY_EVENT_COUNT to 1500,
                    SyncWorker.KEY_RETRY_POLICY to "DEFAULT",
                    SyncWorker.KEY_CHUNK_SIZE to 50
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true) // Don't drain battery
                    .build()
            )
            .build()

        currentWorkId = syncRequest.id

        // Enqueue the work
        WorkManager.getInstance(this).enqueue(syncRequest)

        // Observe work progress
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(syncRequest.id)
            .observe(this) { workInfo ->
                workInfo?.let { updateUIFromWorkInfo(it) }
            }

        Log.d(TAG, "Sync work enqueued: ${syncRequest.id}")
    }

    /**
     * Updates UI based on WorkInfo state and progress.
     */
    private fun updateUIFromWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> {
                statusText.text = "Sync queued..."
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
                syncButton.isEnabled = false
            }

            WorkInfo.State.RUNNING -> {
                // Check for progress updates
                val currentOffset = workInfo.progress.getInt(SyncWorker.KEY_PROGRESS_CURRENT, 0)
                val totalEvents = workInfo.progress.getInt(SyncWorker.KEY_PROGRESS_TOTAL, 0)
                val percentage = workInfo.progress.getInt(SyncWorker.KEY_PROGRESS_PERCENTAGE, 0)

                if (totalEvents > 0) {
                    // Show determinate progress
                    statusText.text = "Syncing: $currentOffset/$totalEvents events ($percentage%)"
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.progress = percentage
                } else {
                    // No progress data yet, show indeterminate
                    statusText.text = "Syncing in background..."
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = true
                }
                syncButton.isEnabled = false
            }

            WorkInfo.State.SUCCEEDED -> {
                val eventsSynced = workInfo.outputData.getInt(SyncWorker.KEY_EVENTS_SYNCED, 0)
                val deviceId = workInfo.outputData.getString(SyncWorker.KEY_DEVICE_ID)

                statusText.text = "✓ Sync complete: $eventsSynced events from $deviceId"
                progressBar.isVisible = false
                syncButton.isEnabled = true
                syncButton.text = "Sync Device"

                eventsText.text = "Synced $eventsSynced events successfully!\n\n" +
                        "Events are persisted in the background worker.\n" +
                        "In production, query them from your database."

                Log.d(TAG, "Sync completed successfully: $eventsSynced events")
            }

            WorkInfo.State.FAILED -> {
                val errorMessage = workInfo.outputData.getString(SyncWorker.KEY_ERROR_MESSAGE)
                val deviceId = workInfo.outputData.getString(SyncWorker.KEY_DEVICE_ID)

                statusText.text = "✗ Sync failed for $deviceId"
                progressBar.isVisible = false
                syncButton.isEnabled = true
                syncButton.text = "Retry Sync"

                eventsText.text = "Error Details:\n$errorMessage"

                Log.e(TAG, "Sync failed: $errorMessage")
            }

            WorkInfo.State.CANCELLED -> {
                statusText.text = "Sync cancelled"
                progressBar.isVisible = false
                syncButton.isEnabled = true
                syncButton.text = "Sync Device"

                Log.d(TAG, "Sync cancelled")
            }

            WorkInfo.State.BLOCKED -> {
                statusText.text = "Sync blocked (waiting for constraints)"
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
                syncButton.isEnabled = false

                Log.d(TAG, "Sync blocked")
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
