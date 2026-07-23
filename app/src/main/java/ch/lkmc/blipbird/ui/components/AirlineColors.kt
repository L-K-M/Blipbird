package ch.lkmc.blipbird.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Airline monogram colors (PLAN.md §4.3 logo strategy, REVIEW.md I6).
 *
 * Bundled IATA → brand color for the ~100 most-flown carriers so a UA or LX
 * monogram reads instantly as "that airline", with the deterministic hash
 * palette as the fallback for everyone else. The values are approximations of
 * each carrier's dominant livery/wordmark color and are purely decorative —
 * no trademark claim, and the monogram foreground stays WCAG-picked via
 * [statusContentColor], so even pale brands (Spirit yellow, airBaltic lime)
 * keep a readable letter pair.
 */
private val BRAND_COLORS: Map<String, Color> = mapOf(
    // Europe
    "LH" to Color(0xFF05164D), // Lufthansa
    "LX" to Color(0xFFE30614), // SWISS
    "WK" to Color(0xFFCE0E2D), // Edelweiss Air
    "OS" to Color(0xFFE3000B), // Austrian
    "SN" to Color(0xFF00235F), // Brussels Airlines
    "EW" to Color(0xFFAA0061), // Eurowings
    "DE" to Color(0xFFFFAD00), // Condor
    "BA" to Color(0xFF075AAA), // British Airways
    "VS" to Color(0xFFDA0530), // Virgin Atlantic
    "EI" to Color(0xFF00754A), // Aer Lingus
    "AF" to Color(0xFF002157), // Air France
    "KL" to Color(0xFF00A1DE), // KLM
    "TO" to Color(0xFF00D66C), // Transavia France
    "HV" to Color(0xFF00D66C), // Transavia
    "IB" to Color(0xFFD7192D), // Iberia
    "VY" to Color(0xFFFFCC00), // Vueling
    "UX" to Color(0xFF00449B), // Air Europa
    "TP" to Color(0xFF00945E), // TAP Air Portugal
    "AZ" to Color(0xFF003DA5), // ITA Airways
    "A3" to Color(0xFF00508F), // Aegean
    "U2" to Color(0xFFFF6600), // easyJet
    "FR" to Color(0xFF073590), // Ryanair
    "W6" to Color(0xFFC6007E), // Wizz Air
    "LS" to Color(0xFFD3072A), // Jet2.com
    "X3" to Color(0xFFE2001A), // TUI fly
    "DY" to Color(0xFFD81939), // Norwegian
    "D8" to Color(0xFFD81939), // Norwegian Air Intl
    "FI" to Color(0xFF003897), // Icelandair
    "AY" to Color(0xFF001A72), // Finnair
    "SK" to Color(0xFF003D85), // SAS
    "BT" to Color(0xFFC4D600), // airBaltic
    "LO" to Color(0xFF002B7F), // LOT Polish
    "TK" to Color(0xFFC70A0C), // Turkish Airlines
    "PC" to Color(0xFFF9B000), // Pegasus
    "SU" to Color(0xFF003B84), // Aeroflot
    "S7" to Color(0xFFBED600), // S7 Airlines
    "KC" to Color(0xFF00A99D), // Air Astana
    // North America
    "AA" to Color(0xFF0078D2), // American
    "UA" to Color(0xFF002244), // United
    "DL" to Color(0xFFE01933), // Delta
    "WN" to Color(0xFF304CB2), // Southwest
    "B6" to Color(0xFF003876), // JetBlue
    "AS" to Color(0xFF01426A), // Alaska
    "NK" to Color(0xFFFFE300), // Spirit
    "F9" to Color(0xFF046A38), // Frontier
    "G4" to Color(0xFFFBB03B), // Allegiant
    "HA" to Color(0xFFB1063A), // Hawaiian
    "AC" to Color(0xFFF01428), // Air Canada
    "WS" to Color(0xFF00A3AD), // WestJet
    "PD" to Color(0xFF123F6D), // Porter
    "AM" to Color(0xFF0B2343), // Aeroméxico
    "Y4" to Color(0xFF93268F), // Volaris
    "VB" to Color(0xFF00A650), // Viva Aerobus
    // Latin America
    "LA" to Color(0xFF1B0088), // LATAM
    "G3" to Color(0xFFFF7020), // GOL
    "AD" to Color(0xFF00579D), // Azul
    "AV" to Color(0xFFE8112D), // Avianca
    "CM" to Color(0xFF004A97), // Copa
    "AR" to Color(0xFF6CACE4), // Aerolíneas Argentinas
    // Middle East
    "EK" to Color(0xFFD71920), // Emirates
    "EY" to Color(0xFFBD8B13), // Etihad
    "QR" to Color(0xFF5C0632), // Qatar Airways
    "SV" to Color(0xFF00693C), // Saudia
    "GF" to Color(0xFFB4985A), // Gulf Air
    "FZ" to Color(0xFF0078BE), // flydubai
    "LY" to Color(0xFF003399), // El Al
    // Asia-Pacific
    "SQ" to Color(0xFF011E41), // Singapore Airlines
    "TR" to Color(0xFFFDD023), // Scoot
    "CX" to Color(0xFF006564), // Cathay Pacific
    "JL" to Color(0xFFE60012), // Japan Airlines
    "NH" to Color(0xFF13448F), // ANA
    "KE" to Color(0xFF7CB2D6), // Korean Air
    "OZ" to Color(0xFFC81432), // Asiana
    "CA" to Color(0xFFE30E19), // Air China
    "MU" to Color(0xFF002E8E), // China Eastern
    "CZ" to Color(0xFF008BCB), // China Southern
    "HU" to Color(0xFFD01F3C), // Hainan
    "MF" to Color(0xFF0066B3), // Xiamen Air
    "9C" to Color(0xFF6EBE4A), // Spring Airlines
    "BR" to Color(0xFF006041), // EVA Air
    "CI" to Color(0xFFD7003A), // China Airlines
    "TG" to Color(0xFF520F8A), // Thai Airways
    "MH" to Color(0xFF002B5C), // Malaysia Airlines
    "GA" to Color(0xFF007D8A), // Garuda Indonesia
    "VN" to Color(0xFF00599F), // Vietnam Airlines
    "VJ" to Color(0xFFEC2227), // VietJet Air
    "PR" to Color(0xFF00308F), // Philippine Airlines
    "5J" to Color(0xFFFDD000), // Cebu Pacific
    "AK" to Color(0xFFE4002B), // AirAsia
    "D7" to Color(0xFFE4002B), // AirAsia X
    "AI" to Color(0xFFDA0E29), // Air India
    "IX" to Color(0xFFF04E23), // Air India Express
    "6E" to Color(0xFF001B94), // IndiGo
    "SG" to Color(0xFFCE202F), // SpiceJet
    "UK" to Color(0xFF4A2A85), // Vistara
    "QF" to Color(0xFFE40000), // Qantas
    "JQ" to Color(0xFFFF5115), // Jetstar
    "VA" to Color(0xFFE4002B), // Virgin Australia
    "NZ" to Color(0xFF1A1A1A), // Air New Zealand
    // Africa
    "ET" to Color(0xFF078930), // Ethiopian
    "MS" to Color(0xFF003580), // EgyptAir
    "AT" to Color(0xFFC4122F), // Royal Air Maroc
    "KQ" to Color(0xFFC82127), // Kenya Airways
    "SA" to Color(0xFF0B2742), // South African Airways
)

/**
 * Deterministic monogram color for an airline code: bundled brand color when
 * the (IATA) code is known, hash palette otherwise.
 */
fun monogramColor(code: String): Color {
    val normalized = code.trim().uppercase()
    BRAND_COLORS[normalized]?.let { return it }
    val palette = listOf(
        Color(0xFF1667D9), Color(0xFF00696E), Color(0xFF7B4FA6), Color(0xFFB3541E),
        Color(0xFF2E7D32), Color(0xFF9C27B0), Color(0xFF00838F), Color(0xFF5D4037),
        Color(0xFFAD1457), Color(0xFF283593), Color(0xFF00695C), Color(0xFFEF6C00),
    )
    val idx = abs(normalized.hashCode()) % palette.size
    return palette[idx]
}

/** WCAG-picked black/white for the monogram letters on [monogramColor]. */
fun monogramContentColor(code: String): Color = statusContentColor(monogramColor(code))
