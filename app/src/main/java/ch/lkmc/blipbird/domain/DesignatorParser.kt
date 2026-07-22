package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.Designator

/**
 * Parses user flight-number input (PLAN.md §5 step 1).
 *
 * Accepts "CA861", "ca 861", "CCA861", "CCA861/CA861" (slash pair = one flight),
 * and batch input separated by commas or newlines. Airline-code resolution against
 * the bundled airline table happens in [IdentityResolver]; this class only does the
 * lexical split.
 */
object DesignatorParser {

    data class Parsed(
        val raw: String,
        val prefix: String,     // 2-char alnum (IATA-shaped) or 3-letter (ICAO-shaped)
        val number: String,
        val suffix: String?,
        val prefixIsIcaoShaped: Boolean,
        val prefixIsIataShaped: Boolean,
    )

    private val TOKEN = Regex("^([A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z]{3})\\s?([0-9]{1,4})([A-Z])?$")

    /** Split batch input into individual flight tokens. Slash pairs stay together. */
    fun splitBatch(input: String): List<String> =
        input.uppercase()
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Parse one token. A "CCA861/CA861" pair is treated as alternate designators of the
     * same flight; both interpretations are returned (ICAO-shaped first when present).
     */
    fun parseToken(token: String): List<Parsed> {
        val parts = token.uppercase().split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Parsed>()
        for (part in parts) {
            val compact = part.replace(Regex("\\s+"), " ")
            val m = TOKEN.matchEntire(compact) ?: TOKEN.matchEntire(compact.replace(" ", "")) ?: continue
            val (prefix, number, suffix) = m.destructured
            out += Parsed(
                raw = part,
                prefix = prefix,
                number = number.trimStart('0').ifEmpty { "0" },
                suffix = suffix.ifEmpty { null },
                prefixIsIcaoShaped = prefix.length == 3 && prefix.all { it.isLetter() },
                prefixIsIataShaped = prefix.length == 2,
            )
        }
        return out
    }

    /**
     * Merge the parses of one token (e.g. the two halves of a slash pair) into a single
     * [Designator] when they agree on the flight number.
     */
    fun mergePair(parses: List<Parsed>): Designator? {
        if (parses.isEmpty()) return null
        val number = parses.first().number
        if (parses.any { it.number != number }) return null
        val suffix = parses.firstNotNullOfOrNull { it.suffix }
        val iata = parses.firstOrNull { it.prefixIsIataShaped }?.prefix
        val icao = parses.firstOrNull { it.prefixIsIcaoShaped }?.prefix
        if (iata == null && icao == null) return null
        return Designator(airlineIata = iata, airlineIcao = icao, number = number, suffix = suffix)
    }
}
