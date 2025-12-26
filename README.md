# Hailie BLE Sync Manager

Hailie BLE Sync Manager is an Android library for robust Bluetooth Low Energy synchronization of Hailie smartinhaler devices. It is built to reliably handle the common challenges of BLE in medical devices: pairing reliability, connection stability, and sync performance.

## Features

* **Robust Retry Logic**: Implements exponential backoff with jitter for BLE operations to maximize success.
* **Clean State Management**: Observable state machine using Kotlin Flows for predictable and testable UI updates.
* **Chunked Data Transfer**: Optimized batch reading and acknowledgment for performance and stability.
* **Graceful Error Handling**: Typed errors with transient and permanent classifications to inform retry decisions.
* **Concurrency Safe**: Mutex-protected operations prevent overlapping syncs.
* **Comprehensive Testing**: Unit tests with MockK covering normal and edge-case scenarios.

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
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

### Use SyncManager

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
            when (val result = syncManager.sync()) {
                is SyncResult.Success -> processEvents(result.events)
                is SyncResult.Failure -> handleError(result.error)
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

SyncManager is built around a clear state machine:

```
Idle → Bonding → Connecting → Syncing → Success / Failed
```

Errors are typed as:

* **Transient**: retriable (BondingFailed, ConnectionFailed, ReadFailed, etc.)
* **Permanent**: non-retriable (DeviceNotBonded, InvalidState, Timeout)

Retry policy:

* Default: 3 retries, 1s → 30s backoff
* Aggressive: 5 retries, 500ms → 15s backoff
* Conservative: 3 retries, 2s → 60s backoff

## Future Enhancements

### High Priority

These are features critical to the reliability and correctness of the library. Integration tests with real or simulated BLE devices are essential to validate behavior under varying radio conditions and firmware versions. Partial sync recovery ensures that if a connection drops, the library can resume from the last acknowledged offset rather than starting over, saving time and reducing user frustration. Connection state monitoring allows the system to detect unexpected disconnects and react gracefully, either by alerting the user or attempting a controlled reconnection. Adaptive retry policies will analyze past failures and automatically adjust retry timing and counts to maximize success without overwhelming the device or radio.

### Medium Priority

Medium priority items improve user experience and operational efficiency. Background sync via WorkManager allows data synchronization without requiring the app to be in the foreground, optimizing battery usage and user convenience. Multi-device sync support lets multiple smartinhalers be synchronized in parallel or sequentially, aggregating results for a more comprehensive view. Enhanced observability and structured logging provide developers with insight into performance metrics, BLE operations, and error patterns, simplifying debugging and future optimizations.

### Low Priority

Low priority items focus on fine-tuning and long-term maintainability. Configuration tuning, such as adjusting chunk sizes or operation timeouts, enables optimized performance across different devices and network conditions. Security improvements, including event data encryption and certificate pinning, enhance compliance and data integrity. Developer tooling, like sample apps, BLE simulators, and debug overlays, lowers the learning curve for new users and simplifies development and testing workflows.

## Design Considerations

* **Exponential Backoff** prevents BLE retry storms and maximizes success.
* **Chunked Reads** reduce BLE round trips, improving throughput.
* **Typed Errors** distinguish transient vs. permanent failures, improving UX.
* **StateFlow** enables clean, reactive UI integration.
* **No Auto-Retry**: user-initiated sync maintains transparency for medical device operations.
