# Hailie BLE Sync Manager

Hailie BLE Sync Manager is an Android library for robust Bluetooth Low Energy synchronization of Hailie smartinhaler devices. It is built to reliably handle the common challenges of BLE in medical devices: pairing reliability, connection stability, and sync performance.

## Features

* **Robust Retry Logic**: Implements exponential backoff with jitter for BLE operations to maximize success.
* **Clean State Management**: Observable state machine using Kotlin Flows for predictable and testable UI updates.
* **Chunked Data Transfer**: Optimized batch reading and acknowledgment for performance and stability.
* **Graceful Error Handling**: Typed errors with transient and permanent classifications to inform retry decisions.
* **Concurrency Safe**: Mutex-protected operations prevent overlapping syncs.
* **Data Safety**: Incremental event persistence via callback prevents data loss on crashes.
* **Background Sync**: WorkManager integration for long-running operations without blocking UI.
* **Performance Tuning**: Configurable chunk sizes and retry policies for optimal sync speed.

## Prerequisites

* **Android Studio** Hedgehog | 2023.1.1 or later
* **JDK 17+**
* **Android SDK**:
    * compileSdk 34
    * minSdk 24
* **Gradle 8.4+** (via wrapper)

## Setup & Build

### 1. Configure Android SDK

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/your/Android/sdk
```

Or set `ANDROID_HOME`:

```bash
export ANDROID_HOME=/path/to/your/Android/sdk
```

Common locations:

* macOS: `/Users/YOUR_USERNAME/Library/Android/sdk`
* Linux: `/home/YOUR_USERNAME/Android/Sdk`
* Windows: `C:\Users\YOUR_USERNAME\AppData\Local\Android\sdk`

### 2. Build the Library

```bash
./gradlew :library:assembleRelease
```

Output: `library/build/outputs/aar/library-release.aar`

### 3. Run Tests

```bash
./gradlew :library:test
```

Results: `library/build/reports/tests/test/index.html`

### 4. Run Sample App (Optional)

```bash
./gradlew :app:installDebug
```

Or run from Android Studio.

## Usage Example

### Add to App

```kotlin
dependencies {
    implementation(files("libs/hailie-ble-sync-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0") // For background sync
}
```

### Implement HailieSensor

```kotlin
class AndroidHailieSensor(
    private val bluetoothDevice: BluetoothDevice,
    private val context: Context
) : HailieSensor {

    private var bluetoothGatt: BluetoothGatt? = null

    override val bondState = MutableStateFlow(...)
    override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    override suspend fun bond(): Result<Unit> = withContext(Dispatchers.IO) {
        if (bluetoothDevice.createBond()) Result.success(Unit)
        else Result.failure(Exception("Bonding failed"))
    }

    override suspend fun connect(): Result<GattConnection> { /* GATT implementation */ }

    override suspend fun disconnect() { /* Close connection */ }
}
```

### Option 1: Direct Sync with Event Persistence

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensor = AndroidHailieSensor(bluetoothDevice, this)
        syncManager = SyncManager(sensor)

        lifecycleScope.launch {
            syncManager.syncState.collect { state ->
                when (state) {
                    is SyncState.Idle -> updateUI("Ready")
                    is SyncState.Bonding -> updateUI("Bonding...")
                    is SyncState.Connecting -> updateUI("Connecting...")
                    is SyncState.Syncing -> updateProgress(state.progress.percentage)
                    is SyncState.Success -> showSuccess("Synced ${state.eventsSynced} events")
                    is SyncState.Failed -> showError(state.error, state.canRetry)
                }
            }
        }
    }

    fun onSyncButtonClick() {
        lifecycleScope.launch {
            when (val result = syncManager.sync(
                onEventsAcknowledged = { events ->
                    // CRITICAL: Persist events immediately to prevent data loss
                    database.insertEvents(events)
                }
            )) {
                is SyncResult.Success -> processEvents(result.events)
                is SyncResult.Failure -> handleError(result.error)
            }
        }
    }
}
```

### Option 2: Background Sync with WorkManager (Recommended)

```kotlin
class MainActivity : AppCompatActivity() {

    fun onSyncButtonClick() {
        // Create background sync request
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(
                SyncWorker.KEY_DEVICE_ID to deviceId,
                SyncWorker.KEY_CHUNK_SIZE to 150,  // Performance optimization
                SyncWorker.KEY_RETRY_POLICY to "AGGRESSIVE"
            ))
            .setConstraints(Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build())
            .build()

        // Enqueue work
        WorkManager.getInstance(this).enqueue(syncRequest)

        // Observe progress
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(syncRequest.id)
            .observe(this) { workInfo ->
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> updateUI("Syncing in background...")
                    WorkInfo.State.SUCCEEDED -> {
                        val eventsSynced = workInfo.outputData
                            .getInt(SyncWorker.KEY_EVENTS_SYNCED, 0)
                        showSuccess("Synced $eventsSynced events")
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData
                            .getString(SyncWorker.KEY_ERROR_MESSAGE)
                        showError(error)
                    }
                    else -> { /* Handle other states */ }
                }
            }
    }
}
```

### Required Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

## Testing

```bash
./gradlew test --info
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed design decisions.

### State Machine
```
Idle → Bonding → Connecting → Syncing → Success/Failed
```

### Error Hierarchy
- **Transient** (retriable): BondingFailed, ConnectionFailed, ReadFailed, etc.
- **Permanent** (non-retriable): DeviceNotBonded, InvalidState, Timeout

### Retry Policy
- **Default**: 3 retries, 1s→30s backoff
- **Aggressive**: 5 retries, 500ms→15s backoff
- **Conservative**: 3 retries, 2s→60s backoff

## Performance Optimization

See [PERFORMANCE_OPTIMIZATION.md](PERFORMANCE_OPTIMIZATION.md) for detailed performance tuning guidance.

**Quick wins:**
- Increase chunk size to 100-150 for faster sync
- Use `RetryPolicy.AGGRESSIVE` for time-critical operations
- Leverage WorkManager for background sync (already implemented!)
- For multiple devices, sync in parallel with separate Workers

**Example optimized configuration:**
```kotlin
val syncManager = SyncManager(
    sensor = sensor,
    retryPolicy = RetryPolicy.AGGRESSIVE,  // Faster retries
    chunkSize = 150,                       // Larger chunks
    operationTimeoutMs = 20000L            // Shorter timeout
)
```

## Recent Enhancements

### ✅ Implemented

* **Incremental Event Persistence**: `onEventsAcknowledged` callback prevents data loss on crashes
* **Background Sync**: WorkManager integration for long-running operations
* **Performance Tuning**: Configurable chunk sizes and retry policies
* **Parallel Sync**: Support for syncing multiple devices simultaneously

### TODO list

#### High Priority

Integration tests with real or simulated BLE devices are essential to validate behavior under varying radio conditions and firmware versions. 

#### Medium Priority

Multi-device sync orchestration with aggregated progress reporting. Enhanced observability and structured logging.

#### Low Priority

Security improvements, including event data encryption and certificate pinning, enhance compliance and data integrity.

## Design Considerations

* **Exponential Backoff** prevents BLE retry storms and maximizes success.
* **Chunked Reads** reduce BLE round trips, improving throughput.
* **Typed Errors** distinguish transient vs. permanent failures, improving UX.
* **StateFlow** enables clean, reactive UI integration.
* **No Auto-Retry**: user-initiated sync maintains transparency for medical device operations.
