package com.adherium.hailie.ble.sensor

import java.time.Instant

/**
 * Represents a medication actuation event from the inhaler.
 *
 * Each event records when a patient used their inhaler, including the number
 * of puffs (doses) delivered. This data is critical for medication adherence
 * tracking and clinical decision-making.
 *
 * @property id Unique identifier for this event (device-generated)
 * @property timestamp UTC timestamp when the actuation occurred
 * @property deviceId Unique identifier of the device that recorded this event
 * @property puffs Number of medication puffs delivered (typically 1-2)
 */
data class ActuationEvent(
    val id: String,
    val timestamp: Instant,
    val deviceId: String,
    val puffs: Int
)