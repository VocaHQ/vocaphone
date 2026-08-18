import Foundation

/// Content-free counters describing whether reporting is actually working.
///
/// ## Why this exists
///
/// The delivery path swallows every failure by design, so that a network problem
/// can never surface to someone who did not ask for this feature to work. That is
/// correct in production and actively harmful while debugging: with nothing
/// logged and nothing shown, an empty queue means either "delivered" or "never
/// recorded", and no signal on the device distinguishes them.
///
/// ## What it holds
///
/// Counts, an outcome name, and a clock time. No event names, no property
/// values, no payloads — nothing that could identify a person or reveal what
/// they dictated. In memory only; it resets with the process.
struct TelemetryStats {

    private var recordedCount = 0
    private var deliveredEvents = 0
    private var rejectedBatches = 0
    private var unavailableBatches = 0
    private var lastOutcome: TelemetryDelivery?
    private var lastAttemptAt: Date?

    mutating func recorded() {
        recordedCount += 1
    }

    mutating func attempted(_ delivery: TelemetryDelivery, batchSize: Int, at: Date) {
        lastOutcome = delivery
        lastAttemptAt = at
        switch delivery {
        case .delivered: deliveredEvents += batchSize
        case .rejected: rejectedBatches += 1
        case .unavailable: unavailableBatches += 1
        }
    }

    var summary: String {
        var line = "\(recordedCount) recorded · \(deliveredEvents) sent"
        if rejectedBatches > 0 { line += " · \(rejectedBatches) rejected" }
        if unavailableBatches > 0 {
            line += " · \(unavailableBatches) could not reach the server"
        }
        guard let lastOutcome, let lastAttemptAt else {
            return line + "\nNo send attempted yet this session."
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        line += "\nLast attempt \(formatter.string(from: lastAttemptAt)) — "
        switch lastOutcome {
        // Deliberately hedged. The server answers 200 to a batch it silently
        // discards — an unknown app key gets the same 200 as a good one — so the
        // honest claim is that it was accepted, not that it was stored. Only the
        // dashboard can confirm the rest.
        case .delivered:
            line += "the server accepted it (check the dashboard to confirm it was stored)"
        case .rejected:
            line += "the server refused it; the batch was dropped"
        case .unavailable:
            line += "could not reach the server; the batch is still queued"
        }
        return line
    }
}
