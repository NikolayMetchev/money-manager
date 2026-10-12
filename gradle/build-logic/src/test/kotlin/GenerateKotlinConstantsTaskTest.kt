import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateKotlinConstantsTaskTest {
    @Test
    fun `short values render as const vals with escapes`() {
        val source = renderKotlinConstants("a.b", "Gen", ":m:gen", mapOf("V" to "x\t\"\$\\\né"), emptyMap())

        val expected = "const val V: String = \"x\\t\\\"\\\$\\\\\\n\\u00e9\""
        assertTrue(expected in source, source)
        assertTrue("package a.b" in source, source)
        assertTrue("internal object Gen {" in source, source)
        assertFalse("decode" in source, source)
    }

    @Test
    fun `long values are split into literals that join back to the value`() {
        val value = "a".repeat(MAX_LITERAL_CHARS - 1) + "😀" + "b".repeat(MAX_LITERAL_CHARS)

        val chunks = chunkLiteral(value)

        assertEquals(value, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= MAX_LITERAL_CHARS })
        assertFalse(chunks.first().last().isHighSurrogate(), "a surrogate pair was split")
        val source = renderKotlinConstants("a", "Gen", ":gen", mapOf("BIG" to value), emptyMap())
        assertTrue("val BIG: String =\n        listOf(" in source, source)
    }

    @Test
    fun `obfuscated values decode back and never contain the plaintext`() {
        val secret = "GOCSPX-secret"

        val hex = xorObfuscate(secret)
        val source = renderKotlinConstants("a", "Gen", ":gen", emptyMap(), mapOf("SECRET" to hex))

        assertFalse("GOCSPX" in source, source)
        assertTrue("val SECRET: String = decode(intArrayOf(" in source, source)
        // Mirrors the generated decode().
        val decoded =
            hex
                .chunked(4)
                .mapIndexed { index, code -> (code.toInt(16) xor ((0x3B + index) and 0xFFFF)).toChar() }
                .joinToString("")
        assertEquals(secret, decoded)
    }
}
