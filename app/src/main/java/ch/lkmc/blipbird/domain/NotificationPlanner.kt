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

    enum class EventType { GATE_ASSIGNED, GATE_CHANGE, DELAY, DELAY_RECOVERED, CANCELLED, DIVERTED, DEPARTED, LANDED }

    data class Event(
        val type: EventType,
        val fingerprint: String,
        val oldValue: String? = null,
        val newValue: String? = null,
        /** Real delay minutes for display copy; the bucket is only a dedup key. */
        val delayMinutes: Long? = null,
    )

    /** First delay notification at ≥ this threshold; further slips at ≥ this step. */
    private val DELAY_THRESHOLD: Duration = Duration.ofMinutes(15)

    /** Dedup granularity: slips and recoveries re-notify per bucket of this size. */
    private const val DELAY_BUCKET_MINUTES = 15L

    fun diff(previous: StatusSnapshot?, current: StatusSnapshot): List<Event> {
        val events = mutableListOf<Event>()
        val prev = previous

        // Gate: the first assignment is at least as valuable as a change, but uses
        // a distinct fingerprint namespace so a later change BACK to the same gate
        // can still notify as a change.
        val oldGate = prev?.depGate
        val newGate = current.depGate
        if (prev != null && oldGate == null && newGate != null) {
            events += Event(EventType.GATE_ASSIGNED, "gate-assigned:$newGate", null, newGate)
        }
        if (oldGate != null && newGate != null && oldGate != newGate) {
            events += Event(EventType.GATE_CHANGE, "gate:$newGate", oldGate, newGate)
        }

        // Delay vs schedule, crossing the threshold or slipping further — and the
        // happier direction: a delay shrinking by a bucket, or vanishing entirely.
        val sched = current.depTimes.scheduled
        val est = current.depTimes.estimated
        val delayBucket = delayBucketMinutes(current)
        val prevDelayBucket = prev?.let { delayBucketMinutes(it) } ?: 0
        // A shrinking delay is exclusively a recovery — never also a DELAY, or the
        // user would get "delayed 20m" and "delay shortened to 20m" side by side.
        val recovering = prevDelayBucket >= DELAY_THRESHOLD.toMinutes() && delayBucket < prevDelayBucket
        if (!recovering && sched != null && est != null && est.isAfter(sched)) {
            val delay = Duration.between(sched, est)
            if (delay >= DELAY_THRESHOLD) {
                events += Event(
                    EventType.DELAY, "delay:$delayBucket",
                    sched.toString(), est.toString(),
                    delayMinutes = delay.toMinutes(),
                )
            }
        }
        if (recovering) {
            val nowDepartingAt = est ?: sched
            // Fingerprint encodes both endpoints so the same destination bucket
            // reached from a different (notified) delay still counts as news.
            events += Event(
                EventType.DELAY_RECOVERED, "delay-recovered:$prevDelayBucket->$delayBucket",
                prev?.depTimes?.estimated?.toString(), nowDepartingAt?.toString(),
                delayMinutes = if (sched != null && est != null && est.isAfter(sched))
                    Duration.between(sched, est).toMinutes() else 0L,
            )
        }

        // Status-only delay (B9): AeroDataBox frequently reports status=delayed
        // with no revised time, which the timed path above can't see. Guarded on
        // the bucket so a timestamped delay never notifies twice, once per path.
        if (current.status == FlightStatus.DELAYED && prev?.status != FlightStatus.DELAYED &&
            delayBucket < DELAY_THRESHOLD.toMinutes()
        ) {
            events += Event(EventType.DELAY, "delay:status")
        }
        // ...and its recovery: delayed status clearing without the timed path
        // ever having seen a bucket (no estimate to shrink).
        if (prev?.status == FlightStatus.DELAYED && current.status != FlightStatus.DELAYED &&
            current.status != FlightStatus.CANCELLED && current.status != FlightStatus.DIVERTED &&
            prevDelayBucket < DELAY_THRESHOLD.toMinutes() && !recovering
        ) {
            events += Event(
                EventType.DELAY_RECOVERED, "delay-recovered:status",
                null, (est ?: sched)?.toString(), delayMinutes = 0L,
            )
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

    /** 15-min delay bucket of a snapshot, 0 when not delayed (or no estimate). */
    private fun delayBucketMinutes(s: StatusSnapshot): Long {
        val sched = s.depTimes.scheduled ?: return 0
        val est = s.depTimes.estimated ?: return 0
        if (!est.isAfter(sched)) return 0
        return (Duration.between(sched, est).toMinutes() / DELAY_BUCKET_MINUTES) * DELAY_BUCKET_MINUTES
    }
}
