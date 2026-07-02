package com.moneymanager.importer

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.PassThroughDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PassThroughDetectorTest {
    // Mirrors the seeded "Curve" definition: one rule covering both Crypto.com and Monzo descriptions,
    // including Crypto.com's cancellation prefixes (Refund / Refund reversal / Cancellation).
    private fun curve(enabled: Boolean = true) =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            enabled = enabled,
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
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
    fun refundPrefix_matchesAndExtractsBareMerchant() {
        // A cancelled charge refunded onto the card must hit the same merchant account as the charge.
        val match = PassThroughDetector(listOf(curve())).detect("Refund: Crv*Navan")
        assertEquals("Navan", match?.merchantName)
        assertEquals("Curve", match?.account?.conduitAccountName)
    }

    @Test
    fun refundReversalPrefix_matchesAndExtractsBareMerchant() {
        val match = PassThroughDetector(listOf(curve())).detect("Refund reversal: Crv*Navan")
        assertEquals("Navan", match?.merchantName)
    }

    @Test
    fun cancellationPrefix_matchesAndExtractsBareMerchant() {
        val match = PassThroughDetector(listOf(curve())).detect("Cancellation: Crv*Zipcar Annual Plan")
        assertEquals("Zipcar Annual Plan", match?.merchantName)
    }

    @Test
    fun refundPrefix_withMultiWordMerchant() {
        val match = PassThroughDetector(listOf(curve())).detect("Refund: Crv*Paypal *Ubertrip 3")
        assertEquals("Paypal *Ubertrip 3", match?.merchantName)
    }

    @Test
    fun refundWithoutCurveMarker_doesNotMatch() {
        assertNull(PassThroughDetector(listOf(curve())).detect("Refund: Something else"))
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
