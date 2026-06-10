package com.moneymanager.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringSimilarityTest {
    @Test
    fun identicalStrings_areFullySimilar() {
        assertEquals(1.0, StringSimilarity.similarity("CASH WITHDRAWAL", "CASH WITHDRAWAL"))
    }

    @Test
    fun normalisationIgnoresCaseAndWhitespace() {
        assertEquals(1.0, StringSimilarity.similarity("  Cash   Withdrawal ", "cash withdrawal"))
    }

    @Test
    fun blankInput_scoresZero() {
        assertEquals(0.0, StringSimilarity.similarity("", "anything"))
        assertEquals(0.0, StringSimilarity.similarity("anything", "   "))
    }

    @Test
    fun trailingFormattingDrift_isHighlySimilar() {
        // The real Santander case: same transaction, one export appends "GBP" to the trailing amount.
        val a = "CASH WITHDRAWAL AT NATIONWIDE BUILDING SOCIETY ATM WIMBLEDON HILL, WIMBLEDON,20.00 GBP , O, 20.00"
        val b = "CASH WITHDRAWAL AT NATIONWIDE BUILDING SOCIETY ATM WIMBLEDON HILL, WIMBLEDON,20.00 GBP , O, 20.00GBP"
        assertTrue(
            StringSimilarity.similarity(a, b) >= StringSimilarity.DESCRIPTION_SIMILARITY_THRESHOLD,
            "expected similarity >= threshold for trailing 'GBP' drift",
        )
    }

    @Test
    fun differentPayees_areBelowThreshold() {
        val a = "DIRECT DEBIT PAYMENT TO THAMES WATER REF 0183323799"
        val b = "DIRECT DEBIT PAYMENT TO BRITISH GAS REF 9981112223"
        assertTrue(
            StringSimilarity.similarity(a, b) < StringSimilarity.DESCRIPTION_SIMILARITY_THRESHOLD,
            "expected genuinely different payees to score below threshold",
        )
    }

    @Test
    fun levenshtein_basics() {
        assertEquals(0, StringSimilarity.levenshtein("abc", "abc"))
        assertEquals(3, StringSimilarity.levenshtein("abc", "abcGBP"))
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(5, StringSimilarity.levenshtein("", "hello"))
    }
}
