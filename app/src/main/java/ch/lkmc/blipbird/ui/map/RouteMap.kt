package ch.lkmc.blipbird.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Offline schematic route map (PLAN.md §11, v0.1): equirectangular projection of
 * the route corridor with graticule, day/night terminator shading, the flown
 * track (solid), remaining great-circle guide (dashed), origin/destination pins
 * and a heading-rotated aircraft marker. Tile-based MapLibre rendering remains a
 * tracked follow-up; this view works with zero network and themes cleanly.
 */
@Composable
fun RouteMap(
    dep: GreatCircle.Point?,
    arr: GreatCircle.Point?,
    lastFix: PositionFix?,
    track: List<PositionFix>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalExtendedColors.current
    val surfaceDim = MaterialTheme.colorScheme.surfaceVariant
    val landTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val routeColor = ext.routeLine
    val nightShade = Color.Black.copy(alpha = 0.30f)
    val markerColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(surfaceDim.copy(alpha = 0.35f), surfaceDim.copy(alpha = 0.7f))))
            .fillMaxSize(),
    ) {
        if (dep == null || arr == null) return@Canvas

        // --- viewport: route bbox + padding, aspect-corrected -------------
        val pts = buildList {
            add(dep); add(arr)
            lastFix?.let { add(GreatCircle.Point(it.lat, it.lon)) }
        }
        // Unwrap longitudes around the antimeridian relative to dep.
        fun unwrap(lon: Double): Double {
            var l = lon
            while (l - dep.lon > 180) l -= 360
            while (l - dep.lon < -180) l += 360
            return l
        }
        var minLat = pts.minOf { it.lat }; var maxLat = pts.maxOf { it.lat }
        var minLon = pts.minOf { unwrap(it.lon) }; var maxLon = pts.maxOf { unwrap(it.lon) }
        val padLat = max(2.0, (maxLat - minLat) * 0.25)
        val padLon = max(2.0, (maxLon - minLon) * 0.15)
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon
        minLat = max(-85.0, minLat); maxLat = min(85.0, maxLat)

        // Maintain aspect: widen the shorter axis.
        val lonSpan = maxLon - minLon
        val latSpan = maxLat - minLat
        val canvasAspect = size.width / size.height
        val geoAspect = (lonSpan / latSpan).toFloat()
        if (geoAspect < canvasAspect) {
            val extra = latSpan * (canvasAspect / geoAspect - 1) / 2
            minLon -= extra * canvasAspect; maxLon += extra * canvasAspect
        } else {
            val extra = lonSpan * (geoAspect / canvasAspect - 1) / 2
            minLat -= extra / canvasAspect; maxLat += extra / canvasAspect
        }

        fun project(lat: Double, lon: Double): Offset {
            val x = ((unwrap(lon) - minLon) / (maxLon - minLon)).toFloat() * size.width
            val y = (1f - ((lat - minLat) / (maxLat - minLat)).toFloat()) * size.height
            return Offset(x, y)
        }

        // --- graticule ----------------------------------------------------
        var g = (minLat / 10).toInt() * 10.0
        while (g <= maxLat) {
            val y = project(g, minLon).y
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            g += 10.0
        }
        g = (minLon / 10).toInt() * 10.0
        while (g <= maxLon) {
            val x = project(minLat, g).x
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            g += 10.0
        }

        // --- day/night shading -------------------------------------------
        val night = DaylightEngine.nightPolygon(Instant.now(), stepDeg = 3.0)
        // The polygon may need drawing at lon and lon±360 to cover the unwrapped view.
        for (shift in listOf(-360.0, 0.0, 360.0)) {
            val path = Path()
            var started = false
            for (p in night) {
                val o = project(p.lat, p.lon + shift)
                if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, nightShade)
        }

        // --- remaining route guide (dashed great circle) ------------------
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        for (segment in GreatCircle.routeSegments(dep, arr, steps = 72)) {
            val path = Path()
            segment.forEachIndexed { i, p ->
                val o = project(p.lat, p.lon)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, routeColor.copy(alpha = 0.55f), style = Stroke(width = 4f, pathEffect = dash, cap = StrokeCap.Round))
        }

        // --- flown track (solid) ------------------------------------------
        if (track.size >= 2) {
            val path = Path()
            track.forEachIndexed { i, f ->
                val o = project(f.lat, f.lon)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, routeColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }

        // --- endpoints ----------------------------------------------------
        drawAirportPin(project(dep.lat, dep.lon), markerColor)
        drawAirportPin(project(arr.lat, arr.lon), markerColor)

        // --- aircraft marker ----------------------------------------------
        lastFix?.let { fix ->
            val o = project(fix.lat, fix.lon)
            val stale = fix.seenPosAgeSec > 120
            val color = if (stale) markerColor.copy(alpha = 0.4f) else markerColor
            rotate(degrees = (fix.trackDeg ?: 0.0).toFloat(), pivot = o) {
                val s = 26f
                val plane = Path().apply {
                    moveTo(o.x, o.y - s * 0.6f)          // nose
                    lineTo(o.x + s * 0.42f, o.y + s * 0.35f)
                    lineTo(o.x, o.y + s * 0.15f)
                    lineTo(o.x - s * 0.42f, o.y + s * 0.35f)
                    close()
                }
                drawPath(plane, color)
            }
        }
    }
}

private fun DrawScope.drawAirportPin(at: Offset, color: Color) {
    drawCircle(color, radius = 9f, center = at)
    drawCircle(Color.White, radius = 4f, center = at)
}
