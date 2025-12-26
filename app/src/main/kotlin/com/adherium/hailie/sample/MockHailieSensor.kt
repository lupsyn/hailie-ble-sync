package com.adherium.hailie.sample

import com.adherium.hailie.ble.sensor.ActuationEvent
import com.adherium.hailie.ble.sensor.BondState
import com.adherium.hailie.ble.sensor.ConnectionState
import com.adherium.hailie.ble.sensor.GattConnection
import com.adherium.hailie.ble.sensor.HailieSensor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import kotlin.random.Random

/**
 * Mock implementation of HailieSensor for testing and demonstration.
 * Simulates realistic BLE behavior including occasional failures.
 */
class MockHailieSensor(
    private val deviceId: String = "mock-hailie-001",
    private val failureRate: Double = 0.0, // 0.0 = never fail, 1.0 = always fail
    private val eventCount: Int = 10
) : HailieSensor {

    private val _bondState = MutableStateFlow(BondState.NONE)
    override val bondState: StateFlow<BondState> = _bondState

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var acknowledgedOffset = 0

    override suspend fun bond(): Result<Unit> {
        delay(500) // Simulate bonding time

        if (shouldFail()) {
            return Result.failure(Exception("Bonding failed - device not responding"))
        }

        _bondState.value = BondState.BONDING
        delay(1000)
        _bondState.value = BondState.BONDED

        return Result.success(Unit)
    }

    override suspend fun connect(): Result<GattConnection> {
        delay(300) // Simulate connection time

        if (bondState.value != BondState.BONDED) {
            return Result.failure(Exception("Cannot connect - device not bonded"))
        }

        if (shouldFail()) {
            return Result.failure(Exception("Connection failed - GATT error 133"))
        }

        _connectionState.value = ConnectionState.CONNECTING
        delay(500)
        _connectionState.value = ConnectionState.CONNECTED

        return Result.success(MockGattConnection())
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        delay(200)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun shouldFail(): Boolean {
        return Random.Default.nextDouble() < failureRate
    }

    inner class MockGattConnection : GattConnection {

        override suspend fun readEventCount(): Result<Int> {
            delay(100)

            if (shouldFail()) {
                return Result.failure(Exception("Failed to read event count"))
            }

            return Result.success(eventCount)
        }

        override suspend fun readEvents(offset: Int, count: Int): Result<List<ActuationEvent>> {
            delay(200) // Simulate BLE read time

            if (shouldFail()) {
                return Result.failure(Exception("Failed to read events - connection lost"))
            }

            val events = (offset until minOf(offset + count, eventCount)).map { index ->
                ActuationEvent(
                    id = "event-$index",
                    timestamp = Instant.now().minusSeconds((eventCount - index) * 3600L),
                    deviceId = deviceId,
                    puffs = Random.Default.nextInt(1, 3)
                )
            }

            return Result.success(events)
        }

        override suspend fun acknowledgeEvents(upToOffset: Int): Result<Unit> {
            delay(150)

            if (shouldFail()) {
                return Result.failure(Exception("Failed to acknowledge events"))
            }

            acknowledgedOffset = upToOffset
            return Result.success(Unit)
        }
    }
}