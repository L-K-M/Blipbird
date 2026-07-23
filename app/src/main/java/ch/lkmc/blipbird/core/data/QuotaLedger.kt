package ch.lkmc.blipbird.core.data

import androidx.room.withTransaction
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.QuotaLedgerDao
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local per-provider usage accounting (PLAN.md §8). Estimates only — the provider
 * account owns real billing. Period key approximates the billing cycle as a UTC
 * calendar month (RapidAPI cycles actually anchor to subscription date; the soft
 * stops below leave headroom for that mismatch).
 */
@Singleton
class QuotaLedger @Inject constructor(
    private val db: OpsDatabase,
    private val dao: QuotaLedgerDao,
) {
    data class Budget(val softStop: Long, val cycleAllowance: Long)

    private val budgets = mapOf(
        "aerodatabox" to Budget(softStop = 560, cycleAllowance = 600),   // free units/cycle
        "aeroapi" to Budget(softStop = 800, cycleAllowance = 1000),     // ≈$4 of $5 waiver
    )

    fun periodKey(): String = YearMonth.now(ZoneOffset.UTC).toString()

    /**
     * Atomically reserve [units] for [provider] this period, but only if that keeps
     * usage at or under the soft stop. Reading the current total and adding to it
     * run in one DB transaction (B18): without it, two concurrent lookups could
     * each pass a separate check-then-record and overshoot the cap. Returns whether
     * the units were recorded — [refund] them if the lookup turns out non-billable.
     */
    suspend fun trySpend(provider: String, units: Long): Boolean {
        val budget = budgets[provider] ?: return true
        // Snapshot the period once: computing it inside the transaction risks
        // reading one month and writing the next if the body straddles UTC
        // midnight at month-end. Floor units at zero (as [refund] does) so a
        // misconfigured negative unitsPerLookup can't *reduce* usage and hand
        // out free quota.
        val period = periodKey()
        val safeUnits = units.coerceAtLeast(0)
        return db.withTransaction {
            val used = dao.used(provider, period) ?: 0
            // Compare via subtraction so a pathologically large safeUnits can't
            // overflow `used + safeUnits` into a negative that slips past the cap.
            if (used > budget.softStop - safeUnits) {
                false
            } else {
                dao.add(provider, period, safeUnits)
                true
            }
        }
    }

    /**
     * Give back units reserved by [trySpend] when no billable request was made.
     * Clamped so a refund can never drive the period below zero (which would hand
     * out free quota): a lookup that straddles the UTC month boundary reserves
     * under one period key and would refund under the next — and any future
     * double-refund would do the same. The period is snapshot once (same reason
     * as [trySpend]) and a stray negative arg is floored at zero. Read + clamp +
     * subtract atomically.
     */
    suspend fun refund(provider: String, units: Long) {
        if (!budgets.containsKey(provider)) return
        val period = periodKey()
        db.withTransaction {
            val current = dao.used(provider, period) ?: 0
            val safeRefund = units.coerceAtLeast(0).coerceAtMost(current)
            if (safeRefund > 0) dao.add(provider, period, -safeRefund)
        }
    }

    suspend fun used(provider: String): Long = dao.used(provider, periodKey()) ?: 0

    fun allowance(provider: String): Long? = budgets[provider]?.cycleAllowance

    fun observeAll() = dao.observeAll()
}
