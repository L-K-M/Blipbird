package ch.lkmc.blipbird.core.database

import java.io.Reader

/**
 * Minimal streaming RFC-4180 reader for the bundled reference CSVs: quoted
 * fields may contain commas, doubled quotes (`""` → `"`) and embedded line
 * breaks — the cases the old line-by-line parser mishandled (glm-A). Records
 * are emitted lazily so an import never holds a whole file in memory.
 */
internal object CsvReader {

    /** Parses [reader] into records; the caller owns closing the reader. */
    fun records(reader: Reader): Sequence<List<String>> = sequence {
        val field = StringBuilder()
        var record = ArrayList<String>(8)
        var inQuotes = false
        var next = reader.read()

        fun endField() {
            record.add(field.toString())
            field.setLength(0)
        }

        while (next != -1) {
            val c = next.toChar()
            next = reader.read()
            if (inQuotes) {
                when {
                    c == '"' && next == '"'.code -> { field.append('"'); next = reader.read() }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else when {
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && next == '\n'.code) next = reader.read()
                    endField()
                    yield(record)
                    record = ArrayList(8)
                }
                else -> field.append(c)
            }
        }
        // Final record without a trailing newline (or truncated mid-quotes).
        if (field.isNotEmpty() || record.isNotEmpty()) {
            endField()
            yield(record)
        }
    }
}
