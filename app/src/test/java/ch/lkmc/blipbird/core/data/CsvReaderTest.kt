package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.database.CsvReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvReaderTest {

    private fun parse(text: String): List<List<String>> =
        CsvReader.records(StringReader(text)).toList()

    @Test
    fun `plain fields split on commas`() {
        assertEquals(listOf(listOf("a", "b", "c")), parse("a,b,c"))
    }

    @Test
    fun `quoted fields keep commas`() {
        assertEquals(listOf(listOf("a,b", "c")), parse("\"a,b\",c"))
    }

    @Test
    fun `doubled quotes decode to a literal quote`() {
        assertEquals(listOf(listOf("He said \"hi\"", "x")), parse("\"He said \"\"hi\"\"\",x"))
    }

    @Test
    fun `trailing doubled quote at the end of a field`() {
        // The old line parser lost the closing state on `"a"""` and corrupted
        // the field (glm-A).
        assertEquals(listOf(listOf("a\"")), parse("\"a\"\"\""))
        assertEquals(listOf(listOf("a\"", "b")), parse("\"a\"\"\",b"))
    }

    @Test
    fun `embedded newline inside quotes stays in the field`() {
        assertEquals(
            listOf(listOf("line1\nline2", "x"), listOf("y", "z")),
            parse("\"line1\nline2\",x\ny,z"),
        )
    }

    @Test
    fun `crlf record separators`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parse("a,b\r\nc,d\r\n"))
    }

    @Test
    fun `final record without trailing newline is emitted`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parse("a,b\nc,d"))
    }

    @Test
    fun `empty fields survive`() {
        assertEquals(listOf(listOf("", "b", "")), parse(",b,"))
    }

    @Test
    fun `blank line is a single empty field record`() {
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), parse("a\n\nb"))
    }
}
