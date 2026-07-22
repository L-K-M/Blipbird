package ch.lkmc.blipbird.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import java.time.Instant
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Real vector-tile live map (PLAN.md §11): OpenFreeMap style per theme, night-side
 * shading from DaylightEngine, great-circle route guide, flown ADS-B track, and a
 * heading-rotated aircraft symbol.
 */
@Composable
fun MapLibreRouteMap(
    dep: GreatCircle.Point?,
    arr: GreatCircle.Point?,
    lastFix: PositionFix?,
    track: List<PositionFix>,
    modifier: Modifier = Modifier,
) {
    val ext = LocalExtendedColors.current
    if (dep == null || arr == null) return

    // ---- camera: fit the route corridor --------------------------------
    val focus = lastFix?.let { GreatCircle.Point(it.lat, it.lon) }
    val pts = listOfNotNull(dep, arr, focus)
    val camera = rememberCameraState(
        firstPosition = remember(dep, arr) {
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            var minLon = pts.minOf { it.lon }; var maxLon = pts.maxOf { it.lon }
            if (maxLon - minLon > 180) { // antimeridian: center on the short way round
                val tmp = minLon; minLon = maxLon; maxLon = tmp + 360
            }
            val centerLat = (minLat + maxLat) / 2
            val centerLon = ((minLon + maxLon) / 2 + 540) % 360 - 180
            val span = max(maxLon - minLon, (maxLat - minLat) * 1.6).coerceAtLeast(0.5)
            val zoom = (ln(360.0 / span) / ln(2.0)).coerceIn(1.0, 9.0) - 0.4
            CameraPosition(target = Position(centerLon, centerLat), zoom = zoom)
        },
    )

    // ---- geojson sources ----------------------------------------------
    val routeJson = remember(dep, arr) {
        multiLineJson(GreatCircle.routeSegments(dep, arr, steps = 96).map { seg -> seg.map { it.lon to it.lat } })
    }
    val trackJson = remember(track.size) {
        if (track.size < 2) EMPTY_FC
        else multiLineJson(splitAtAntimeridian(track.map { it.lon to it.lat }))
    }
    val endpointsJson = remember(dep, arr) {
        pointsJson(listOf(dep.lon to dep.lat, arr.lon to arr.lat))
    }
    val planeJson = remember(lastFix?.at) {
        lastFix?.let { pointsJson(listOf(it.lon to it.lat)) } ?: EMPTY_FC
    }
    val nightJson = remember(lastFix?.at?.epochSecond?.div(600)) {
        nightPolygonJson(Instant.now())
    }

    val routeSource = rememberGeoJsonSource(GeoJsonData.JsonString(routeJson))
    val trackSource = rememberGeoJsonSource(GeoJsonData.JsonString(trackJson))
    val endpointSource = rememberGeoJsonSource(GeoJsonData.JsonString(endpointsJson))
    val planeSource = rememberGeoJsonSource(GeoJsonData.JsonString(planeJson))
    val nightSource = rememberGeoJsonSource(GeoJsonData.JsonString(nightJson))

    val planeIcon = remember(ext.routeLine) { planeBitmap(ext.routeLine, sizePx = 72) }
    val stale = (lastFix?.seenPosAgeSec ?: 0.0) > 120

    MaplibreMap(
        modifier = modifier.clip(RoundedCornerShape(20.dp)),
        baseStyle = BaseStyle.Uri(ext.mapStyleUrl),
        cameraState = camera,
    ) {
        FillLayer(
            id = "blipbird-night",
            source = nightSource,
            color = const(Color.Black),
            opacity = const(0.22f),
        )
        LineLayer(
            id = "blipbird-route",
            source = routeSource,
            color = const(ext.routeLine.copy(alpha = 0.45f)),
            width = const(3.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
        LineLayer(
            id = "blipbird-track",
            source = trackSource,
            color = const(ext.routeLine),
            width = const(4.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
        )
        CircleLayer(
            id = "blipbird-endpoints",
            source = endpointSource,
            radius = const(5.dp),
            color = const(Color.White),
            strokeColor = const(ext.routeLine),
            strokeWidth = const(2.5.dp),
        )
        SymbolLayer(
            id = "blipbird-plane",
            source = planeSource,
            iconImage = image(planeIcon),
            iconSize = const(0.62f),
            iconRotate = const((lastFix?.trackDeg ?: 0.0).toFloat()),
            iconAllowOverlap = const(true),
            iconOpacity = const(if (stale) 0.45f else 1f),
        )
    }
}

// ------------------------------------------------------------------ helpers

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

// Locale.ROOT throughout: GeoJSON needs dot decimal separators regardless of the
// device locale (comma-decimal locales otherwise emit invalid geometry).
private fun multiLineJson(segments: List<List<Pair<Double, Double>>>): String {
    val coords = segments.filter { it.size >= 2 }.joinToString(",") { seg ->
        seg.joinToString(",", prefix = "[", postfix = "]") { (lon, lat) -> "[%.5f,%.5f]".format(Locale.ROOT, lon, lat) }
    }
    if (coords.isEmpty()) return EMPTY_FC
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"MultiLineString","coordinates":[$coords]}}]}"""
}

private fun pointsJson(points: List<Pair<Double, Double>>): String {
    val features = points.joinToString(",") { (lon, lat) ->
        """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[%.5f,%.5f]}}""".format(Locale.ROOT, lon, lat)
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun nightPolygonJson(at: Instant): String {
    val ring = DaylightEngine.nightPolygon(at, stepDeg = 3.0).toMutableList()
    if (ring.first().lat != ring.last().lat || ring.first().lon != ring.last().lon) ring += ring.first()
    val coords = ring.joinToString(",") { "[%.3f,%.3f]".format(Locale.ROOT, it.lon, it.lat.coerceIn(-89.9, 89.9)) }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"Polygon","coordinates":[[$coords]]}}]}"""
}

private fun splitAtAntimeridian(points: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
    val out = mutableListOf(mutableListOf(points.first()))
    for (i in 1 until points.size) {
        if (kotlin.math.abs(points[i].first - points[i - 1].first) > 180) out += mutableListOf(points[i])
        else out.last() += points[i]
    }
    return out
}

/** North-up aircraft silhouette rendered into an ImageBitmap for the symbol layer. */
private fun planeBitmap(tint: Color, sizePx: Int): ImageBitmap {
    val bmp = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bmp)
    val paint = Paint().apply { color = tint; isAntiAlias = true }
    val s = sizePx.toFloat()
    val cx = s / 2
    val path = Path().apply {
        moveTo(cx, s * 0.04f)                    // nose
        cubicTo(cx + s * 0.055f, s * 0.10f, cx + s * 0.06f, s * 0.20f, cx + s * 0.06f, s * 0.32f)
        lineTo(cx + s * 0.46f, s * 0.55f)        // right wing tip
        lineTo(cx + s * 0.46f, s * 0.62f)
        lineTo(cx + s * 0.07f, s * 0.52f)
        lineTo(cx + s * 0.06f, s * 0.74f)        // fuselage to tail
        lineTo(cx + s * 0.20f, s * 0.84f)        // right stabilizer
        lineTo(cx + s * 0.20f, s * 0.90f)
        lineTo(cx, s * 0.86f)
        lineTo(cx - s * 0.20f, s * 0.90f)
        lineTo(cx - s * 0.20f, s * 0.84f)
        lineTo(cx - s * 0.06f, s * 0.74f)
        lineTo(cx - s * 0.07f, s * 0.52f)
        lineTo(cx - s * 0.46f, s * 0.62f)
        lineTo(cx - s * 0.46f, s * 0.55f)        // left wing tip
        lineTo(cx - s * 0.06f, s * 0.32f)
        cubicTo(cx - s * 0.06f, s * 0.20f, cx - s * 0.055f, s * 0.10f, cx, s * 0.04f)
        close()
    }
    // white halo for contrast on any base map
    val halo = Paint().apply { color = Color.White.copy(alpha = 0.9f); isAntiAlias = true
        style = androidx.compose.ui.graphics.PaintingStyle.Stroke; strokeWidth = s * 0.05f }
    canvas.drawPath(path, halo)
    canvas.drawPath(path, paint)
    return bmp
}
