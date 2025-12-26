# Sample App - Hailie BLE Sync Manager Demo

This Android app demonstrates how to integrate and use the Hailie BLE Sync Manager library.

## What It Demonstrates

1. **Library Integration** - How to add the library as a dependency
2. **SyncManager Usage** - How to create and use the SyncManager
3. **State Observation** - How to observe and react to sync states
4. **UI Updates** - How to update UI based on sync progress
5. **Error Handling** - How to handle and display errors

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

### MainActivity.kt

The sample app shows:

1. **Creating SyncManager**:
```kotlin
val mockSensor = MockHailieSensor(
    deviceId = "demo-hailie-001",
    failureRate = 0.0,
    eventCount = 25
)

syncManager = SyncManager(
    sensor = mockSensor,
    retryPolicy = RetryPolicy.DEFAULT
)
```

2. **Observing State**:
```kotlin
lifecycleScope.launch {
    syncManager.syncState.collect { state ->
        when (state) {
            is SyncState.Idle -> updateUI("Ready")
            is SyncState.Syncing -> showProgress(state.progress)
            is SyncState.Success -> showSuccess(state.eventsSynced)
            is SyncState.Failed -> showError(state.error)
        }
    }
}
```

3. **Performing Sync**:
```kotlin
when (val result = syncManager.sync()) {
    is SyncResult.Success -> displayEvents(result.events)
    is SyncResult.Failure -> showError(result.error)
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

## Features Demonstrated

- ✅ Library dependency configuration
- ✅ State machine observation
- ✅ Progress tracking
- ✅ Error classification
- ✅ Retry logic
- ✅ Event display
- ✅ Proper coroutine lifecycle management

## UI

Simple Material Design UI with:
- Status text showing current state
- Progress bar for sync progress
- Sync button
- Event list display
- Error details

## Notes

- Uses MockHailieSensor for demo (no real BLE device needed)
- Shows all sync states (Idle, Bonding, Connecting, Syncing, Success, Failed)
- Demonstrates proper coroutine scoping with lifecycleScope
- Shows how to handle both success and failure cases
