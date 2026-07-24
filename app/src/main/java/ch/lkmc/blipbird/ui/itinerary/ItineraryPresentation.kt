package ch.lkmc.blipbird.ui.itinerary

import android.content.Context
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.model.Itinerary
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun Context.dateFormatter(style: FormatStyle): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(style).withLocale(resources.configuration.locales[0])

fun Itinerary.displayTitle(context: Context): String = name ?: run {
    val firstDate = legs.mapNotNull { it.date }.minOrNull()?.format(context.dateFormatter(FormatStyle.MEDIUM))
    if (firstDate == null) context.getString(R.string.itinerary_unnamed_title, legs.size)
    else context.getString(R.string.itinerary_unnamed_dated_title, legs.size, firstDate)
}

fun Itinerary.dateSpan(context: Context): String? {
    val dates = legs.mapNotNull { it.date }
    val first = dates.minOrNull() ?: return null
    val last = dates.maxOrNull() ?: first
    val formatter = context.dateFormatter(FormatStyle.MEDIUM)
    return if (first == last) first.format(formatter)
    else context.getString(R.string.itinerary_date_span, first.format(formatter), last.format(formatter))
}

fun Itinerary.designatorLine(
    limit: Int = Int.MAX_VALUE,
    separator: String = " -> ",
    overflowLabel: (Int) -> String = { "+$it" },
): String {
    val values = legs.take(limit).map { it.flight.designator().display }
    return values.joinToString(separator) +
        if (legs.size > limit) "  ${overflowLabel(legs.size - limit)}" else ""
}
