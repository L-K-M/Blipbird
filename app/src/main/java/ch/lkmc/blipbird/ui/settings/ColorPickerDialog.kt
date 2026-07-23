package ch.lkmc.blipbird.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.ui.theme.toHsv
import androidx.compose.ui.unit.dp

/**
 * Hand-rolled HSV picker: saturation/value field + hue bar + hex entry (the hex
 * field doubles as the accessible, keyboard-friendly input path).
 */
@Composable
fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val (h0, s0, v0) = remember(initial) { initial.toHsv() }
    var hue by remember { mutableFloatStateOf(h0) }
    var sat by remember { mutableFloatStateOf(s0) }
    var value by remember { mutableFloatStateOf(v0) }
    val color = Color.hsv(hue, sat, value)
    var hexText by remember { mutableStateOf("%06X".format(color.toArgbInt() and 0xFFFFFF)) }
    var hexFromPicker by remember { mutableStateOf(true) }

    fun syncHexFromPicker() {
        hexFromPicker = true
        hexText = "%06X".format(Color.hsv(hue, sat, value).toArgbInt() and 0xFFFFFF)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column {
                // Saturation (x) / value (y) field for the current hue
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
                        )
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                syncHexFromPicker()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                syncHexFromPicker()
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val center = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.Black, radius = 9.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Hue bar
                val hueStops = remember {
                    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(hueStops))
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                hue = (p.x / size.width).coerceIn(0f, 1f) * 359.9f
                                syncHexFromPicker()
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                hue = (change.position.x / size.width).coerceIn(0f, 1f) * 359.9f
                                syncHexFromPicker()
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = (hue / 360f) * size.width
                        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black, radius = 9.5.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { raw ->
                            hexFromPicker = false
                            val cleaned = raw.removePrefix("#").uppercase().take(6)
                            hexText = cleaned
                            if (cleaned.length == 6 && cleaned.all { it.isDigit() || it in 'A'..'F' }) {
                                val (h, s, v) = Color(0xFF000000L or cleaned.toLong(16)).toHsv()
                                hue = h; sat = s; value = v
                            }
                        },
                        prefix = { Text("#") },
                        label = { Text(stringResource(R.string.color_picker_hex)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        isError = !hexFromPicker && hexText.length == 6 &&
                            !hexText.all { it.isDigit() || it in 'A'..'F' },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(Color.hsv(hue, sat, value)) }) {
                Text(stringResource(R.string.color_picker_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun Color.toArgbInt(): Int {
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
