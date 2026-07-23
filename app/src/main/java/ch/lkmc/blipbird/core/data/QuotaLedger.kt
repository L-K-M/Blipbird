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
        return db.withTransaction {
            val used = dao.used(provider, periodKey()) ?: 0
            if (used + units > budget.softStop) {
                false
            } else {
                dao.add(provider, periodKey(), units)
                true
            }
        }
    }

    /** Give back units reserved by [trySpend] when no billable request was made. */
    suspend fun refund(provider: String, units: Long) {
        if (budgets.containsKey(provider)) dao.add(provider, periodKey(), -units)
    }

    suspend fun used(provider: String): Long = dao.used(provider, periodKey()) ?: 0

    fun allowance(provider: String): Long? = budgets[provider]?.cycleAllowance

    fun observeAll() = dao.observeAll()
}
