package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.data.StatusProviderChain
import ch.lkmc.blipbird.core.data.failureOutcome
import ch.lkmc.blipbird.core.data.failureProviders
import ch.lkmc.blipbird.domain.LookupOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A failed lookup has to be able to say *which* service failed: "rate limited"
 * or "no API key" is only actionable if the user knows whose key to go and look
 * at. A pass can consult several providers across up to three dates, so the
 * attribution has to survive the collapse to a single worst outcome.
 */
class LookupAttributionTest {

    private fun lookup(outcome: LookupOutcome, vararg providers: String) =
        StatusProviderChain.Lookup(
            candidates = emptyList(),
            outcome = outcome,
            failedProviders = providers.toList(),
        )

    @Test
    fun namesOnlyTheProviderBehindTheWinningOutcome() {
        val lookups = listOf(
            lookup(LookupOutcome.QUOTA_EXHAUSTED, "aerodatabox"),
            lookup(LookupOutcome.RATE_LIMITED, "aeroapi"),
        )

        // RATE_LIMITED outranks QUOTA_EXHAUSTED, so the quota-blocked provider
        // must not be the one the user is sent to look at.
        assertEquals(LookupOutcome.RATE_LIMITED, lookups.failureOutcome())
        assertEquals(listOf("aeroapi"), lookups.failureProviders())
    }

    @Test
    fun namesBothWhenBothFailedTheSameWay() {
        val lookups = listOf(lookup(LookupOutcome.NO_KEY, "aerodatabox", "aeroapi"))

        assertEquals(LookupOutcome.NO_KEY, lookups.failureOutcome())
        assertEquals(listOf("aerodatabox", "aeroapi"), lookups.failureProviders())
    }

    /** Three dates can be probed for one flight; the same provider shouldn't be listed thrice. */
    @Test
    fun collapsesARepeatedProviderAcrossSeveralDateProbes() {
        val lookups = listOf(
            lookup(LookupOutcome.RATE_LIMITED, "aeroapi"),
            lookup(LookupOutcome.RATE_LIMITED, "aeroapi"),
            lookup(LookupOutcome.RATE_LIMITED, "aeroapi"),
        )

        assertEquals(listOf("aeroapi"), lookups.failureProviders())
    }

    /**
     * A pass where any date found candidates reads as NOT_FOUND for the user, and
     * that is a fact about the flight rather than about a service — nothing to
     * name, and nothing to send anyone to Settings for.
     */
    @Test
    fun attributesNothingWhenTheOutcomeIsNotFound() {
        val lookups = listOf(
            lookup(LookupOutcome.NOT_FOUND, "aerodatabox"),
            lookup(LookupOutcome.NO_KEY, "aeroapi"),
        )

        assertEquals(LookupOutcome.NOT_FOUND, lookups.failureOutcome())
        assertEquals(listOf("aerodatabox"), lookups.failureProviders())
    }

    @Test
    fun attributesNothingWhenNoProviderWasRecorded() {
        val lookups = listOf(lookup(LookupOutcome.NONRETRYABLE_ERROR))

        assertEquals(emptyList(), lookups.failureProviders())
    }
}
