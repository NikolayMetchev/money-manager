package com.moneymanager.csv

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvParserTest {
    private val parser = CsvParser()

    // Basic parsing tests

    @Test
    fun parse_basicCommaDelimitedCsv_parsesCorrectly() {
        val csv = "name,age,city\nAlice,30,New York\nBob,25,Los Angeles"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30", "New York"), result.rows[0])
        assertEquals(listOf("Bob", "25", "Los Angeles"), result.rows[1])
    }

    @Test
    fun parse_semicolonDelimiter_parsesCorrectly() {
        val csv = "name;age;city\nAlice;30;New York\nBob;25;Los Angeles"

        val result = parser.parse(csv, CsvParseOptions(delimiter = ';'))

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30", "New York"), result.rows[0])
    }

    @Test
    fun parse_tabDelimiter_parsesCorrectly() {
        val csv = "name\tage\tcity\nAlice\t30\tNew York"

        val result = parser.parse(csv, CsvParseOptions(delimiter = '\t'))

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Alice", "30", "New York"), result.rows[0])
    }

    // Quoted field tests

    @Test
    fun parse_quotedFieldContainingDelimiter_parsesCorrectly() {
        val csv = "name,description,price\n\"Widget\",\"A small, useful item\",9.99"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "description", "price"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Widget", "A small, useful item", "9.99"), result.rows[0])
    }

    @Test
    fun parse_quotedFieldContainingEscapedQuotes_parsesCorrectly() {
        val csv = "name,quote\nAlice,\"She said \"\"Hello\"\"\""

        val result = parser.parse(csv)

        assertEquals(listOf("name", "quote"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Alice", "She said \"Hello\""), result.rows[0])
    }

    @Test
    fun parse_quotedFieldWithNewline_parsesCorrectly() {
        val csv = "name,address\nAlice,\"123 Main St\nApt 4\""

        val result = parser.parse(csv)

        assertEquals(listOf("name", "address"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Alice", "123 Main St\nApt 4"), result.rows[0])
    }

    // Empty and edge cases

    @Test
    fun parse_emptyContent_returnsEmptyResult() {
        val result = parser.parse("")

        assertEquals(emptyList(), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_blankContent_returnsEmptyResult() {
        val result = parser.parse("   \n  \n  ")

        assertEquals(emptyList(), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_headersOnly_returnsHeadersWithNoRows() {
        val csv = "name,age,city"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_noHeaders_returnsRowsWithEmptyHeaders() {
        val csv = "Alice,30,New York\nBob,25,Los Angeles"

        val result = parser.parse(csv, CsvParseOptions(hasHeaders = false))

        assertEquals(emptyList(), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30", "New York"), result.rows[0])
        assertEquals(listOf("Bob", "25", "Los Angeles"), result.rows[1])
    }

    // Inconsistent row lengths

    @Test
    fun parse_rowWithFewerColumnsThanHeaders_padsWithEmptyStrings() {
        val csv = "name,age,city\nAlice,30\nBob,25,Los Angeles"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30", ""), result.rows[0])
        assertEquals(listOf("Bob", "25", "Los Angeles"), result.rows[1])
    }

    @Test
    fun parse_rowWithMoreColumnsThanHeaders_truncates() {
        val csv = "name,age\nAlice,30,New York,Extra\nBob,25"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30"), result.rows[0])
        assertEquals(listOf("Bob", "25"), result.rows[1])
    }

    // Line ending tests

    @Test
    fun parse_windowsLineEndings_parsesCorrectly() {
        val csv = "name,age\r\nAlice,30\r\nBob,25"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice", "30"), result.rows[0])
        assertEquals(listOf("Bob", "25"), result.rows[1])
    }

    @Test
    fun parse_unixLineEndings_parsesCorrectly() {
        val csv = "name,age\nAlice,30\nBob,25"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age"), result.headers)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun parse_mixedLineEndings_parsesCorrectly() {
        val csv = "name,age\r\nAlice,30\nBob,25"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age"), result.headers)
        assertEquals(2, result.rows.size)
    }

    // UTF-8 and special characters

    @Test
    fun parse_utf8Characters_parsesCorrectly() {
        val csv = "name,city\nMuller,Munchen\nTanaka,Tokyo"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "city"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Muller", "Munchen"), result.rows[0])
        assertEquals(listOf("Tanaka", "Tokyo"), result.rows[1])
    }

    @Test
    fun parse_emptyFields_preservesEmptyStrings() {
        val csv = "a,b,c\n1,,3\n,2,"

        val result = parser.parse(csv)

        assertEquals(listOf("a", "b", "c"), result.headers)
        assertEquals(listOf("1", "", "3"), result.rows[0])
        assertEquals(listOf("", "2", ""), result.rows[1])
    }

    // Delimiter detection tests

    @Test
    fun detectDelimiter_commaDelimited_detectsComma() {
        val csv = "name,age,city\nAlice,30,New York\nBob,25,Los Angeles"

        val delimiter = parser.detectDelimiter(csv)

        assertEquals(',', delimiter)
    }

    @Test
    fun detectDelimiter_semicolonDelimited_detectsSemicolon() {
        val csv = "name;age;city\nAlice;30;New York\nBob;25;Los Angeles"

        val delimiter = parser.detectDelimiter(csv)

        assertEquals(';', delimiter)
    }

    @Test
    fun detectDelimiter_tabDelimited_detectsTab() {
        val csv = "name\tage\tcity\nAlice\t30\tNew York"

        val delimiter = parser.detectDelimiter(csv)

        assertEquals('\t', delimiter)
    }

    @Test
    fun detectDelimiter_pipeDelimited_detectsPipe() {
        val csv = "name|age|city\nAlice|30|New York\nBob|25|Los Angeles"

        val delimiter = parser.detectDelimiter(csv)

        assertEquals('|', delimiter)
    }

    @Test
    fun detectDelimiter_emptyContent_defaultsToComma() {
        val delimiter = parser.detectDelimiter("")

        assertEquals(',', delimiter)
    }

    @Test
    fun detectDelimiter_quotedFieldWithComma_detectsCorrectDelimiter() {
        val csv = "name;description;price\nWidget;\"A small, useful item\";9.99"

        val delimiter = parser.detectDelimiter(csv)

        assertEquals(';', delimiter)
    }

    // Whitespace handling

    @Test
    fun parse_preservesLeadingAndTrailingSpaces() {
        val csv = "name,city\n Alice , New York "

        val result = parser.parse(csv)

        assertEquals(listOf("name", "city"), result.headers)
        assertEquals(listOf(" Alice ", " New York "), result.rows[0])
    }

    // Complex scenarios

    @Test
    fun parse_complexCsvWithEscapedQuotes_parsesCorrectly() {
        val csv = "id,name,description,price\n1,\"Widget\",\"A \"\"useful\"\" item\",9.99"

        val result = parser.parse(csv)

        assertEquals(listOf("id", "name", "description", "price"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("1", "Widget", "A \"useful\" item", "9.99"), result.rows[0])
    }

    @Test
    fun parse_multilineQuotedField_parsesCorrectly() {
        val csv = "id,name,description\n1,Gadget,\"Multi-line\ndescription here\""

        val result = parser.parse(csv)

        assertEquals(listOf("id", "name", "description"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("1", "Gadget", "Multi-line\ndescription here"), result.rows[0])
    }

    @Test
    fun parse_singleColumn_parsesCorrectly() {
        val csv = "name\nAlice\nBob"

        val result = parser.parse(csv)

        assertEquals(listOf("name"), result.headers)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Alice"), result.rows[0])
        assertEquals(listOf("Bob"), result.rows[1])
    }

    @Test
    fun parse_singleRow_parsesCorrectly() {
        val csv = "name,age,city\nAlice,30,New York"

        val result = parser.parse(csv)

        assertEquals(listOf("name", "age", "city"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Alice", "30", "New York"), result.rows[0])
    }

    @Test
    fun parse_quotedFieldWithCommaAndQuotes_parsesCorrectly() {
        val csv = "name,value\nTest,\"Hello, \"\"World\"\"\""

        val result = parser.parse(csv)

        assertEquals(listOf("name", "value"), result.headers)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Test", "Hello, \"World\""), result.rows[0])
    }
}
