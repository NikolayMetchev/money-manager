package com.moneymanager.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompileRegexTest {
    @Test
    fun `inline flags apply`() {
        val regex = compileRegex("(?i)^CRV\\*\\s*(.+)$")

        assertEquals("Navan", regex.firstMatch("crv*Navan")?.groupValues?.get(1))
        assertTrue(compileRegex("(?im)^a$").containsMatchIn("x\nA"))
        assertTrue(compileRegex("(?i)(?m)^a$").containsMatchIn("x\nA"))
        assertTrue(compileRegex("x(?i)abc").matches("xABC"))
        assertFalse(compileRegex("(?i)a(?-i)b").matches("AB"))
    }

    @Test
    fun `the pattern is kept as given and its options apply`() {
        val regex = compileRegex("(?i)x", RegexOption.IGNORE_CASE)

        assertEquals("(?i)x", regex.pattern)
        assertTrue(compileRegex("abc", RegexOption.IGNORE_CASE).matches("ABC"))
        assertFalse(compileRegex("abc").matches("ABC"))
    }

    @Test
    fun `Java syntax works on every platform`() {
        assertTrue(compileRegex("^\\Qa.b*c\\E$").matches("a.b*c"))
        assertFalse(compileRegex("^\\Qa.b\\E$").matches("axb"))
        assertEquals("42", compileRegex("(?<=£)\\d+").firstMatch("cost £42")?.value)
        assertFalse(compileRegex("a++a").containsMatchIn("aaa"))
        assertEquals(
            "b",
            compileRegex("(?<name>b)")
                .firstMatch("ab")
                ?.groups
                ?.get(1)
                ?.value,
        )
    }

    @Test
    fun `an escaped literal matches only itself`() {
        for (literal in listOf("Coffee Shop", "a.b", "x\\Ey", "\\Q(", "[]^$|?*+{}")) {
            val regex = compileRegex("^" + escapeRegex(literal) + "$")

            assertTrue(regex.matches(literal), literal)
        }
        assertEquals("\\QCoffee Shop\\E", escapeRegex("Coffee Shop"))
        assertEquals("\\Qx\\E\\\\E\\Qy\\E", escapeRegex("x\\Ey"))
        assertFalse(compileRegex(escapeRegex("a.b")).matches("axb"))
    }

    @Test
    fun `replaceFirst replaces the first match`() {
        assertEquals("b-a", compileRegex("a").replaceFirst("a-a", "b"))
        assertNull(compileRegex("z").firstMatch("abc"))
    }

    @Test
    fun `an invalid pattern is an IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { compileRegex("(unclosed") }
        assertFailsWith<IllegalArgumentException> { compileRegex("(?i)[a-") }
    }
}
