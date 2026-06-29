package com.moneymanager.apiimporter

import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestCounterpartyNameTest {
    @Test
    fun `picks the common substring shared by all spellings`() {
        val names =
            listOf(
                "120386070PAXOS TE",
                "991204771PAXOS TE",
                "455120987PAXOS TE",
            )
        assertEquals("PAXOS TE", suggestCounterpartyName(names))
    }

    @Test
    fun `trims whitespace around the common substring`() {
        val names = listOf("12 TESCO STORES 01", "99 TESCO STORES 47")
        assertEquals("TESCO STORES", suggestCounterpartyName(names))
    }

    @Test
    fun `returns the single name unchanged`() {
        assertEquals("Acme Ltd", suggestCounterpartyName(listOf("Acme Ltd")))
    }

    @Test
    fun `falls back to the first name when nothing meaningful is shared`() {
        val names = listOf("John Smith", "Tesco", "Amazon")
        assertEquals("John Smith", suggestCounterpartyName(names))
    }

    @Test
    fun `ignores blank names`() {
        val names = listOf("", "  ", "111PAYPAL", "222PAYPAL")
        assertEquals("PAYPAL", suggestCounterpartyName(names))
    }

    @Test
    fun `returns Unknown when all names are blank`() {
        assertEquals("Unknown", suggestCounterpartyName(listOf("", "   ")))
    }

    @Test
    fun `longestCommonSubstring returns leftmost longest match`() {
        assertEquals("abc", longestCommonSubstring(listOf("xabcy", "abc", "1abc2")))
        assertEquals("", longestCommonSubstring(listOf("abc", "xyz")))
    }
}
