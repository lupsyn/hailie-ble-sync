# Sample App - Hailie BLE Sync Manager Demo

This Android app demonstrates how to integrate and use the Hailie BLE Sync Manager library.

## Running the App

### Prerequisites
- Android SDK (minSdk 24, targetSdk 34)
- Android Studio or command line tools

### Build & Run

```bash
# From project root
./gradlew :app:assembleDebug

# Install on connected device/emulator
./gradlew :app:installDebug
```

## Code Highlights

### SyncWorker.kt

Background worker that handles sync operations:

```kotlin
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID)
        val syncManager = createSyncManager(deviceId)

        val syncResult = syncManager.sync(
            onEventsAcknowledged = { events ->
                // Persist events immediately to prevent data loss
                persistEvents(deviceId, events)
            }
        )

        return when (syncResult) {
            is SyncResult.Success -> Result.success(...)
            is SyncResult.Failure -> if (syncResult.error.isTransient)
                Result.retry() else Result.failure(...)
        }
    }
}
```

### MainActivity.kt

The sample app shows:

1. **Enqueuing Background Sync**:
```kotlin
val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
    .setInputData(workDataOf(
        SyncWorker.KEY_DEVICE_ID to "demo-hailie-001",
        SyncWorker.KEY_CHUNK_SIZE to 150,
        SyncWorker.KEY_RETRY_POLICY to "AGGRESSIVE"
    ))
    .setConstraints(Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build())
    .build()

WorkManager.getInstance(this).enqueue(syncRequest)
```

2. **Observing Work Progress**:
```kotlin
WorkManager.getInstance(this)
    .getWorkInfoByIdLiveData(syncRequest.id)
    .observe(this) { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> updateUI("Syncing...")
            WorkInfo.State.SUCCEEDED -> {
                val events = workInfo.outputData.getInt(KEY_EVENTS_SYNCED, 0)
                showSuccess("Synced $events events")
            }
            WorkInfo.State.FAILED -> showError(workInfo.outputData.getString(KEY_ERROR_MESSAGE))
            else -> { /* Handle other states */ }
        }
    }
```

3. **Event Persistence in Worker**:
```kotlin
private fun persistEvents(deviceId: String, events: List<ActuationEvent>) {
    // In production: database.insertEvents(events)
    // For demo: Log events
    Log.d(TAG, "Persisting ${events.size} events for $deviceId")
}
```

## Production Integration

In production, replace `MockHailieSensor` with your actual BLE implementation:

```kotlin
class RealHailieSensor(
    private val bluetoothDevice: BluetoothDevice
) : HailieSensor {
    // Implement actual BLE operations
    override suspend fun bond(): Result<Unit> { ... }
    override suspend fun connect(): Result<GattConnection> { ... }
    // ...
}
```

## Architecture Highlights

### Background Sync Flow
```
User taps Sync → WorkManager.enqueue(SyncWorker)
                       ↓
           SyncWorker runs in background
                       ↓
           Events persisted incrementally
                       ↓
           WorkInfo updates → UI observes
```

### Data Safety
- Events acknowledged on device are immediately persisted
- App can crash mid-sync without losing acknowledged events
- WorkManager survives process death
- Automatic retry for transient failures

## Notes

- Demonstrates WorkManager best practices
- Shows proper LiveData observation in Activity
- Background work continues even if app is backgrounded
- Battery-conscious with constraints
