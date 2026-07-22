package ch.lkmc.blipbird

import ch.lkmc.blipbird.domain.DesignatorParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignatorParserTest {

    @Test fun `parses IATA form`() {
        val p = DesignatorParser.parseToken("CA861").single()
        assertEquals("CA", p.prefix)
        assertEquals("861", p.number)
        assertTrue(p.prefixIsIataShaped)
    }

    @Test fun `parses ICAO form`() {
        val p = DesignatorParser.parseToken("CCA861").single()
        assertEquals("CCA", p.prefix)
        assertTrue(p.prefixIsIcaoShaped)
    }

    @Test fun `parses lowercase with space`() {
        val p = DesignatorParser.parseToken("ca 861").single()
        assertEquals("CA", p.prefix)
        assertEquals("861", p.number)
    }

    @Test fun `slash pair merges into one designator with both codes`() {
        val parses = DesignatorParser.parseToken("CCA861/CA861")
        val d = DesignatorParser.mergePair(parses)!!
        assertEquals("CA861", d.iata)
        assertEquals("CCA861", d.icao)
    }

    @Test fun `slash pair with different numbers does not merge`() {
        val parses = DesignatorParser.parseToken("CCA861/CA862")
        assertNull(DesignatorParser.mergePair(parses))
    }

    @Test fun `batch splits on commas and newlines`() {
        val tokens = DesignatorParser.splitBatch("CA861, LX1612\nba249")
        assertEquals(listOf("CA861", "LX1612", "BA249"), tokens)
    }

    @Test fun `optional letter suffix survives`() {
        val p = DesignatorParser.parseToken("BA249A").single()
        assertEquals("A", p.suffix)
    }

    @Test fun `digit-containing IATA prefix parses`() {
        val p = DesignatorParser.parseToken("U21234").single()
        assertEquals("U2", p.prefix)
        assertTrue(p.prefixIsIataShaped)
    }

    @Test fun `leading zeros normalize`() {
        val p = DesignatorParser.parseToken("CA0861").single()
        assertEquals("861", p.number)
    }

    @Test fun `garbage does not parse`() {
        assertTrue(DesignatorParser.parseToken("HELLO WORLD").isEmpty())
        assertTrue(DesignatorParser.parseToken("12345").isEmpty())
    }
}
