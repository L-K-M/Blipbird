package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.StatusSnapshot
import java.time.Duration
import java.time.Instant

/**
 * Picks the right flight *instance* when a dateless lookup returns several
 * candidates (PLAN.md §5 step 3/4). Priority:
 *
 * 1. Confirmed airborne: actually departed, not yet actually arrived.
 * 2. Schedule-wise in flight right now: bestDep ≤ now ≤ bestArr + 30 min.
 * 3. Actually arrived within the last 3 h (user checks just after landing).
 * 4. Next upcoming instance (bestDep > now − 30 min), earliest first.
 * 5. Most recent past instance.
 *
 * Operating (non-codeshare) records are preferred throughout.
 */
object InstanceSelector {

    fun select(candidates: List<StatusSnapshot>, now: Instant): StatusSnapshot? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { it.codeshareOf == null }.ifEmpty { candidates }

        fun departed(s: StatusSnapshot): Instant? = s.depTimes.actual ?: s.depTimes.runwayActual
        fun arrived(s: StatusSnapshot): Instant? = s.arrTimes.actual ?: s.arrTimes.runwayActual

        pool.filter { departed(it) != null && arrived(it) == null }
            .maxByOrNull { departed(it)!! }
            ?.let { return it }

        pool.filter { s ->
            val dep = s.depTimes.best ?: return@filter false
            val arr = s.arrTimes.best ?: return@filter false
            !dep.isAfter(now) && !arr.plus(Duration.ofMinutes(30)).isBefore(now)
        }.maxByOrNull { it.depTimes.best!! }?.let { return it }

        pool.filter { s ->
            arrived(s)?.let { Duration.between(it, now) in Duration.ZERO..Duration.ofHours(3) } == true
        }.maxByOrNull { arrived(it)!! }?.let { return it }

        pool.filter { s ->
            s.depTimes.best?.isAfter(now.minus(Duration.ofMinutes(30))) == true
        }.minByOrNull { it.depTimes.best!! }?.let { return it }

        return pool.maxByOrNull { it.depTimes.best ?: Instant.MIN }
    }
}
