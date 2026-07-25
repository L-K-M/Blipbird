package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.datastore.DossierSection
import ch.lkmc.blipbird.core.datastore.DossierSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dossier-card visibility (PLAN.md §9.6). The storage shape matters more than it
 * looks: persisting the *hidden* set rather than the visible one is what makes a
 * card added by a later version visible by default instead of silently missing
 * for everyone who had already opened Settings once.
 */
class DossierSectionsTest {

    @Test fun `everything shows until the user hides something`() {
        val sections = DossierSections()
        DossierSection.entries.forEach { assertTrue(sections.shows(it), "$it should default to visible") }
    }

    @Test fun `hiding one card leaves the rest alone`() {
        val sections = DossierSections().with(DossierSection.WEATHER, visible = false)
        assertFalse(sections.shows(DossierSection.WEATHER))
        assertTrue(sections.shows(DossierSection.MAP))
        assertTrue(sections.shows(DossierSection.RIBBON))
        assertTrue(sections.shows(DossierSection.BODY_CLOCK))
        assertTrue(sections.shows(DossierSection.AIRLINE))
    }

    @Test fun `showing a card again clears it from the hidden set`() {
        val sections = DossierSections()
            .with(DossierSection.MAP, visible = false)
            .with(DossierSection.MAP, visible = true)
        assertTrue(sections.shows(DossierSection.MAP))
        assertEquals(emptySet(), sections.hidden)
    }

    @Test fun `hiding twice is idempotent`() {
        val once = DossierSections().with(DossierSection.AIRLINE, visible = false)
        val twice = once.with(DossierSection.AIRLINE, visible = false)
        assertEquals(once, twice)
    }

    @Test fun `showing a card that was never hidden changes nothing`() {
        val sections = DossierSections().with(DossierSection.RIBBON, visible = true)
        assertEquals(DossierSections(), sections)
    }

    @Test fun `an unknown stored name decodes to null rather than throwing`() {
        // A preference written by a newer build (or a hand-edited one) must not
        // crash the settings read; the repository drops what it can't decode.
        assertNull(DossierSection.decode("TELEPORTER"))
        assertNull(DossierSection.decode(""))
        assertNull(DossierSection.decode("weather"))
        assertEquals(DossierSection.WEATHER, DossierSection.decode("WEATHER"))
    }

    @Test fun `the departure-board core is not switchable`() {
        // §9.2's hero, key facts, and timeline are the app's opinion; a dossier
        // that can be emptied has none. Guard the enum against a well-meaning
        // future addition.
        val names = DossierSection.entries.map { it.name }
        assertEquals(listOf("MAP", "RIBBON", "BODY_CLOCK", "WEATHER", "AIRLINE"), names)
    }
}
