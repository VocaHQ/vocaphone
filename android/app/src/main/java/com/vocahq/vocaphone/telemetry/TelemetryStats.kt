package com.vocahq.vocaphone.telemetry

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Content-free counters describing whether reporting is actually working.
 *
 * ## Why this exists
 *
 * The delivery path swallows every failure by design, so that a network problem
 * can never surface to someone who did not ask for this feature to work. That is
 * correct in production and actively harmful while debugging: with nothing
 * logged and nothing shown, an empty queue means either "delivered" or "never
 * recorded", and no signal on the device distinguishes them. Diagnosing a real
 * phone came down to guessing, twice, wrongly.
 *
 * ## What it holds
 *
 * Counts, an outcome name, and a clock time. No event names, no property values,
 * no payloads — the same class of data as the in-memory operational counters the
 * gateway already keeps, and nothing that could identify a person or reveal what
 * they dictated. It is in memory only and resets with the process.
 */
internal class TelemetryStats {

    private var recordedCount = 0
    private var deliveredBatches = 0
    private var deliveredEvents = 0
    private var rejectedBatches = 0
    private var unavailableBatches = 0
    private var lastOutcome: TelemetryDelivery? = null
    private var lastAttemptAt: Instant? = null

    @Synchronized
    fun recorded() {
        recordedCount++
    }

    @Synchronized
    fun attempted(delivery: TelemetryDelivery, batchSize: Int, at: Instant) {
        lastOutcome = delivery
        lastAttemptAt = at
        when (delivery) {
            TelemetryDelivery.DELIVERED -> {
                deliveredBatches++
                deliveredEvents += batchSize
            }
            TelemetryDelivery.REJECTED -> rejectedBatches++
            TelemetryDelivery.UNAVAILABLE -> unavailableBatches++
        }
    }

    @Synchronized
    fun summary(): String = buildString {
        append("$recordedCount recorded · $deliveredEvents sent")
        if (rejectedBatches > 0) append(" · $rejectedBatches rejected")
        if (unavailableBatches > 0) append(" · $unavailableBatches could not reach the server")
        val outcome = lastOutcome
        val at = lastAttemptAt
        if (outcome == null || at == null) {
            append("\nNo send attempted yet this session.")
            return@buildString
        }
        append("\nLast attempt ${TIME.format(at.atZone(ZoneId.systemDefault()))} — ")
        append(
            when (outcome) {
                // Deliberately hedged. The server answers 200 to a batch it
                // silently discards -- an unknown app key gets the same 200 as a
                // good one -- so the honest claim is that it was accepted, not
                // that it was stored. Only the dashboard can confirm the rest.
                TelemetryDelivery.DELIVERED -> "the server accepted it (check the dashboard to confirm it was stored)"
                TelemetryDelivery.REJECTED -> "the server refused it; the batch was dropped"
                TelemetryDelivery.UNAVAILABLE -> "could not reach the server; the batch is still queued"
            }
        )
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
