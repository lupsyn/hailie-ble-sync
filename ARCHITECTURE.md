# BLE Sync Manager Architecture

The BLE Sync Manager is designed to provide reliable and predictable synchronization with Hailie smart inhaler devices in an environment where Bluetooth Low Energy (BLE) behavior is inherently unstable. Real-world usage regularly exposes issues such as unreliable pairing, fragile connections, and inconsistent data transfer rates.

Rather than masking these issues with opaque automation, the Sync Manager makes each step of the synchronization process explicit and observable. The system favors deterministic behavior, clear state transitions, and carefully controlled retries so that failures are understandable, diagnosable, and recoverable.

---

## A Deliberate Sync Lifecycle

Synchronization follows a strict, linear lifecycle. At any point in time, the system occupies exactly one well-defined state and can only move forward or terminate.

A sync begins in the **Idle** state, where no BLE activity is in progress. When synchronization is initiated, the manager transitions into **Bonding**, ensuring that the inhaler is properly paired and trusted. Once bonding is verified, the system proceeds to **Connecting**, opening a GATT connection to the device. After a stable connection is established, the manager enters **Syncing**, where events are read from the device, acknowledged in batches, and progress is reported. The process ends in either **Success** or **Failed**, both of which are terminal states that encapsulate the final outcome.

```
Idle → Bonding → Connecting → Syncing → Success | Failed
```

These states are represented as a sealed `SyncState` and exposed via `StateFlow`. State transitions are atomic and observable, making the system easier to reason about, debug, and bind to UI or logging layers. There are no hidden transitions or implicit background behavior.

---

## Serialized Execution for BLE Stability

Bluetooth stacks are notoriously sensitive to concurrent operations. Overlapping GATT connections or interleaved read and write operations can easily lead to undefined behavior.

To avoid this, the Sync Manager enforces strict serialization using a single `Mutex`. Only one sync operation may execute at any given time. Additional sync requests are naturally queued and executed sequentially. This approach trades raw throughput for predictability and stability, which is critical when dealing with BLE devices in uncontrolled environments.

---

## Errors as First-Class Concepts

Failures in the sync process are expected and explicitly modeled. Errors are categorized based on whether retrying is likely to succeed.

**Transient errors** typically result from temporary conditions such as radio interference, timing issues, or brief device unavailability. These include bonding failures, connection drops, read failures, and acknowledgment failures. Transient errors are considered retryable.

**Permanent errors** indicate logical or configuration problems that retries are unlikely to resolve. Examples include missing device bonds, invalid state transitions, or operation timeouts.

When a failure occurs, it propagates as a typed `SyncError` and results in a `SyncState.Failed`. The failure state contains enough contextual information for the caller to decide whether to retry, escalate, or abandon the operation. The Sync Manager intentionally avoids making these decisions automatically.

---

## Controlled and Bounded Retries

Retries are applied deliberately and locally rather than globally. Each operation—bonding, connecting, reading, and acknowledging—has its own retry scope and timeout, with a default operation timeout of 30 seconds. This prevents localized failures from forcing a full sync restart.

Retry delays follow an exponential backoff strategy with jitter to reduce BLE radio contention and avoid repeated collisions:

```kotlin
delay = min(initialDelay * (multiplier ^ attempt), maxDelay) + jitter
```

Default retry behavior balances responsiveness with device stability:

* **Max retries:** 3
* **Initial delay:** 1 second
* **Maximum delay:** 30 seconds
* **Backoff multiplier:** 2.0
* **Jitter:** 10%

Multiple retry policies are supported to reflect different operational needs. Aggressive policies favor faster recovery for critical paths, while conservative policies prioritize device health and radio stability.

---

## Performance-Oriented Data Transfer

The Sync Manager optimizes data transfer to accommodate unstable connections and limited device resources.

Events are read in configurable chunks, with a default size of 50 events per read. Chunked reads reduce packet fragmentation, shorten connection duration, and allow the system to report visible progress during long sync operations.

Acknowledgments are batched rather than sent per event. By acknowledging every N events (default: 100), the system significantly reduces write operations and improves overall throughput without sacrificing correctness.

Connections are opened only when necessary and closed immediately after synchronization completes. This minimizes radio usage and helps preserve the inhaler’s battery life.

---

## Design Trade-offs

The architecture reflects a series of deliberate trade-offs:

* Using `StateFlow` instead of `LiveData` enables coroutine-native, platform-agnostic state observation at the cost of requiring Flow collection.
* Serializing sync execution via a `Mutex` ensures predictable BLE behavior with minimal overhead.
* Chunked reads and batched acknowledgments improve stability and throughput but add implementation complexity.
* A typed error model enables precise recovery decisions while increasing the amount of code to maintain.

---

## Data Safety and Incremental Persistence

To prevent data loss during mid-sync crashes, the architecture implements an incremental persistence pattern via the `onEventsAcknowledged` callback.

When events are acknowledged on the device, they are immediately removed from device memory and cannot be re-read. To ensure these events survive app crashes, the callback is invoked **immediately after acknowledgment** with the batch of acknowledged events.

```kotlin
syncManager.sync(
    onEventsAcknowledged = { events ->
        // CRITICAL: Persist these events before returning
        // Once acknowledged, they cannot be recovered from the device
        database.insertEvents(events)
    }
)
```

This pattern follows the **Dependency Inversion Principle**—the library does not dictate storage implementation. The caller can persist to Room, SQLite, files, cloud storage, or any other mechanism. The sync library only ensures the callback is invoked at the correct time in the lifecycle.

**Lifecycle guarantee:**
```
Read chunk → Acknowledge on device → Invoke callback → Clear temp buffer
```

If the app crashes after the callback returns, the events are already persisted. If it crashes during the callback, the database transaction (if used) will roll back, and the events remain on the device for the next sync attempt.

---

## Background Synchronization with WorkManager

BLE sync operations are inherently long-running and can take several minutes for devices with thousands of events. Blocking the UI thread during this time creates poor user experience.

The architecture now includes `SyncWorker`, a `CoroutineWorker` that executes sync operations in the background using Android's WorkManager. This provides several critical benefits:

* **User freedom:** Users can leave the app, switch tasks, or lock the screen while sync continues
* **Automatic retry:** WorkManager handles retry logic for failed work
* **Constraint-based scheduling:** Sync only when battery is sufficient, device is charging, etc.
* **Process survival:** Work survives process death and app updates
* **OS-level optimization:** Android schedules work efficiently across the system

**Background sync architecture:**
```
MainActivity → WorkManager.enqueue(SyncWorker)
                    ↓
SyncWorker creates SyncManager in background thread
                    ↓
SyncManager.sync() with onEventsAcknowledged
                    ↓
Events persisted incrementally in Worker
                    ↓
WorkInfo.State.SUCCEEDED → UI shows result
```

The Worker reports progress via `WorkInfo.State` and outputs results via `Data`, allowing UI components to observe long-running sync operations without maintaining direct references to the sync manager.

**Example usage:**
```kotlin
val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
    .setInputData(workDataOf(
        SyncWorker.KEY_DEVICE_ID to deviceId,
        SyncWorker.KEY_CHUNK_SIZE to 150,
        SyncWorker.KEY_RETRY_POLICY to "AGGRESSIVE"
    ))
    .setConstraints(Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build())
    .build()

WorkManager.getInstance(context).enqueue(syncRequest)
```

This architectural pattern scales naturally to multiple devices by enqueuing multiple Workers in parallel, with each maintaining independent retry state and progress reporting.

---

## Future Extension Points

The design intentionally leaves room for additional enhancements, including:

* Resuming sync from the last acknowledged offset (partial recovery -TBD on the GATT behaviour)
* Adaptive retry policies based on historical failure patterns
* Foreground Service for long-running syncs requiring immediate visibility
