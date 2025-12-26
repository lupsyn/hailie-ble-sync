package com.adherium.hailie.sample

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.adherium.hailie.ble.*
import com.adherium.hailie.ble.retry.RetryPolicy
import kotlinx.coroutines.launch

/**
 * Sample app demonstrating Hailie BLE Sync Manager integration.
 *
 * This demonstrates:
 * - Library integration
 * - State observation
 * - Sync execution
 * - UI updates based on sync state
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var syncButton: Button
    private lateinit var eventsText: TextView

    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        syncButton = findViewById(R.id.syncButton)
        eventsText = findViewById(R.id.eventsText)

        // Create sync manager with mock sensor for demo
        // In production, replace with actual HailieSensor implementation
        val mockSensor = MockHailieSensor(
            deviceId = "demo-hailie-001",
            failureRate = 0.0, // No failures for demo
            eventCount = 25
        )

        syncManager = SyncManager(
            sensor = mockSensor,
            retryPolicy = RetryPolicy.DEFAULT
        )

        // Observe sync state
        lifecycleScope.launch {
            syncManager.syncState.collect { state ->
                updateUI(state)
            }
        }

        // Sync button click
        syncButton.setOnClickListener {
            performSync()
        }

        statusText.text = "Ready to sync"
    }

    private fun updateUI(state: SyncState) {
        when (state) {
            is SyncState.Idle -> {
                statusText.text = "Ready to sync"
                progressBar.isVisible = false
                syncButton.isEnabled = true
            }

            is SyncState.Bonding -> {
                statusText.text = "Bonding with device..."
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
                syncButton.isEnabled = false
            }

            is SyncState.Connecting -> {
                statusText.text = "Connecting to device..."
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
                syncButton.isEnabled = false
            }

            is SyncState.Syncing -> {
                val progress = state.progress
                statusText.text = "Syncing: ${progress.currentOffset}/${progress.totalEvents} events"
                progressBar.isVisible = true
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = progress.percentage
                syncButton.isEnabled = false
            }

            is SyncState.Success -> {
                statusText.text = "✓ Sync complete: ${state.eventsSynced} events"
                progressBar.isVisible = false
                syncButton.isEnabled = true
            }

            is SyncState.Failed -> {
                statusText.text = "✗ Sync failed: ${state.error.message}"
                progressBar.isVisible = false
                syncButton.isEnabled = true

                if (state.canRetry) {
                    syncButton.text = "Retry Sync"
                } else {
                    syncButton.text = "Check Device & Retry"
                }
            }
        }
    }

    private fun performSync() {
        lifecycleScope.launch {
            syncButton.text = "Syncing..."

            when (val result = syncManager.sync()) {
                is SyncResult.Success -> {
                    // Display synced events
                    val eventsList = result.events.take(10).joinToString("\n") { event ->
                        "• ${event.id}: ${event.puffs} puffs at ${event.timestamp}"
                    }

                    val summary = if (result.events.size > 10) {
                        "$eventsList\n... and ${result.events.size - 10} more"
                    } else {
                        eventsList
                    }

                    eventsText.text = "Synced Events:\n$summary"
                }

                is SyncResult.Failure -> {
                    val errorType = when (result.error) {
                        is SyncError.BondingFailed -> "Bonding"
                        is SyncError.ConnectionFailed -> "Connection"
                        is SyncError.ConnectionLost -> "Connection Lost"
                        is SyncError.ReadFailed -> "Data Read"
                        is SyncError.AcknowledgeFailed -> "Acknowledgment"
                        is SyncError.DeviceNotBonded -> "Not Bonded"
                        is SyncError.InvalidState -> "Invalid State"
                        is SyncError.Timeout -> "Timeout"
                    }

                    eventsText.text = "Error Details:\n" +
                            "Type: $errorType\n" +
                            "Message: ${result.error.message}\n" +
                            "Can Retry: ${result.error.isTransient}"
                }
            }

            syncButton.text = "Sync Device"
        }
    }
}
