package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.StatusSnapshot
import java.time.Duration

/**
 * Pure snapshot-diff → event decisions (PLAN.md §12). Emission dedup lives in the
 * persisted EmittedEvent ledger; each event carries a fingerprint that includes the
 * *new* value, so a further slip re-notifies while a re-observed same value doesn't.
 */
object NotificationPlanner {

    enum class EventType { GATE_CHANGE, DELAY, CANCELLED, DIVERTED, DEPARTED, LANDED, DELAY_RECOVERED }

    data class Event(
        val type: EventType,
        val fingerprint: String,
        val oldValue: String? = null,
        val newValue: String? = null,
    )

    /** First delay notification at ≥ this threshold; further slips at ≥ this step. */
    private val DELAY_THRESHOLD: Duration = Duration.ofMinutes(15)

    fun diff(previous: StatusSnapshot?, current: StatusSnapshot): List<Event> {
        val events = mutableListOf<Event>()
        val prev = previous

        // Gate change (only when both sides known — a gate appearing is not a "change")
        val oldGate = prev?.depGate
        val newGate = current.depGate
        if (oldGate != null && newGate != null && oldGate != newGate) {
            events += Event(EventType.GATE_CHANGE, "gate:$newGate", oldGate, newGate)
        }

        // Status-only delay: provider says "delayed" but may not have revised times.
        // Fires on the status transition itself; timed slip buckets fire separately.
        if (current.status == FlightStatus.DELAYED && prev?.status != FlightStatus.DELAYED) {
            events += Event(EventType.DELAY, "delay:status")
        }

        // Delay recovered: was delayed, now back on-schedule
        if (prev?.status == FlightStatus.DELAYED && current.status != FlightStatus.DELAYED
            && current.status != FlightStatus.CANCELLED && current.status != FlightStatus.DIVERTED
        ) {
            events += Event(EventType.DELAY_RECOVERED, "recovered")
        }

        // Delay vs schedule, crossing the threshold or slipping further
        val sched = current.depTimes.scheduled
        val est = current.depTimes.estimated
        if (sched != null && est != null && est.isAfter(sched)) {
            val delay = Duration.between(sched, est)
            if (delay >= DELAY_THRESHOLD) {
                val bucket = (delay.toMinutes() / 15) * 15   // re-notify per 15-min slip bucket
                events += Event(EventType.DELAY, "delay:$bucket", sched.toString(), est.toString())
            }
        }

        // Status transitions
        if (current.status == FlightStatus.CANCELLED && prev?.status != FlightStatus.CANCELLED) {
            events += Event(EventType.CANCELLED, "cancelled")
        }
        if (current.status == FlightStatus.DIVERTED && prev?.status != FlightStatus.DIVERTED) {
            events += Event(EventType.DIVERTED, "diverted:${current.arrival?.code}", null, current.arrival?.code)
        }
        val prevDeparted = prev?.depTimes?.actual ?: prev?.depTimes?.runwayActual
        val nowDeparted = current.depTimes.actual ?: current.depTimes.runwayActual
        if (nowDeparted != null && prevDeparted == null) {
            events += Event(EventType.DEPARTED, "departed", null, nowDeparted.toString())
        }
        val prevLanded = prev?.arrTimes?.actual ?: prev?.arrTimes?.runwayActual
        val nowLanded = current.arrTimes.actual ?: current.arrTimes.runwayActual
        if (nowLanded != null && prevLanded == null) {
            events += Event(EventType.LANDED, "landed", null, nowLanded.toString())
        }
        return events
    }
}
