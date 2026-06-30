package com.moneymanager.importer

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.PassThroughDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PassThroughDetectorTest {
    // Mirrors the seeded "Curve" definition: one rule covering both Crypto.com and Monzo descriptions.
    private fun curve(enabled: Boolean = true) =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            enabled = enabled,
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^CRV\\*",
                        merchantPattern = "(?i)^CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        merchantTemplate = "$1",
                    ),
                ),
        )

    @Test
    fun cryptoComStyle_stripsPrefix() {
        val match = PassThroughDetector(listOf(curve())).detect("Crv*Sainsburys")
        assertEquals("Sainsburys", match?.merchantName)
        assertEquals("Curve", match?.account?.conduitAccountName)
    }

    @Test
    fun monzoStyle_stripsPrefixAndTrailingLocation() {
        // The real Monzo fixture: tx_0000ArZLnhQRNHoXG3sUaH.
        val match = PassThroughDetector(listOf(curve())).detect("CRV*NATIONAL LOTTERY   London        GBR")
        assertEquals("NATIONAL LOTTERY", match?.merchantName)
    }

    @Test
    fun nonCurveDescription_doesNotMatch() {
        assertNull(PassThroughDetector(listOf(curve())).detect("Tesco Stores 6725"))
    }

    @Test
    fun disabledDefinition_isIgnored() {
        assertNull(PassThroughDetector(listOf(curve(enabled = false))).detect("Crv*Sainsburys"))
    }
}
