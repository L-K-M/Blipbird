package ch.lkmc.blipbird.core.data

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
    private val dao: QuotaLedgerDao,
) {
    data class Budget(val softStop: Long, val cycleAllowance: Long)

    private val budgets = mapOf(
        "aerodatabox" to Budget(softStop = 560, cycleAllowance = 600),   // free units/cycle
        "aeroapi" to Budget(softStop = 800, cycleAllowance = 1000),     // ≈$4 of $5 waiver
    )

    fun periodKey(): String = YearMonth.now(ZoneOffset.UTC).toString()

    suspend fun canSpend(provider: String, units: Long): Boolean {
        val budget = budgets[provider] ?: return true
        val used = dao.used(provider, periodKey()) ?: 0
        return used + units <= budget.softStop
    }

    suspend fun record(provider: String, units: Long) = dao.add(provider, periodKey(), units)

    suspend fun used(provider: String): Long = dao.used(provider, periodKey()) ?: 0

    fun allowance(provider: String): Long? = budgets[provider]?.cycleAllowance

    fun observeAll() = dao.observeAll()
}
