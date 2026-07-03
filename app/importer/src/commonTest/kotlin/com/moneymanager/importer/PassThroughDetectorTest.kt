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
    private fun curve() =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                    ),
                ),
        )

    // Mirrors the seeded "PayPal" definition.
    private fun paypal() =
        PassThroughAccount(
            id = PassThroughAccountId(2),
            name = "PayPal",
            conduitAccountName = "PayPal",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?PAYPAL\\s*\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?PAYPAL\\s*\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                    ),
                ),
        )

    @Test
    fun cryptoComStyle_stripsPrefix() {
        val match = PassThroughDetector(listOf(curve())).detect("Crv*Sainsburys")
        assertEquals("Sainsburys", match?.merchantName)
        assertEquals(listOf("Curve"), match?.accounts?.map { it.conduitAccountName })
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
        assertEquals(listOf("Curve"), match?.accounts?.map { it.conduitAccountName })
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
    fun innerPaypalMarker_withoutPaypalDefinition_staysInMerchant() {
        // With no PayPal definition configured, the chain stops after Curve — single-hop behaviour.
        val match = PassThroughDetector(listOf(curve())).detect("Refund: Crv*Paypal *Ubertrip 3")
        assertEquals("Paypal *Ubertrip 3", match?.merchantName)
        assertEquals(1, match?.hops?.size)
    }

    @Test
    fun chainedConduits_peelInSequence() {
        // The real Crypto.com fixture: card -> Curve -> PayPal -> The Pi Hut.
        val match = PassThroughDetector(listOf(curve(), paypal())).detect("CRV*PAYPAL *THEPIHUT 0")
        assertEquals(listOf("Curve", "PayPal"), match?.accounts?.map { it.conduitAccountName })
        assertEquals("THEPIHUT 0", match?.merchantName)
        // Each hop keeps its remainder so every spend leg gets a natural description.
        assertEquals(listOf("PAYPAL *THEPIHUT 0", "THEPIHUT 0"), match?.hops?.map { it.merchantText })
    }

    @Test
    fun chainedConduits_cancellationPrefix_peelsBothHops() {
        val match = PassThroughDetector(listOf(curve(), paypal())).detect("Cancellation: Crv*Paypal *Thepihut 0")
        assertEquals(listOf("Curve", "PayPal"), match?.accounts?.map { it.conduitAccountName })
        assertEquals("Thepihut 0", match?.merchantName)
    }

    @Test
    fun chainedConduits_reverseOrder_peelsInAppearanceOrder() {
        // Conduits chain in whichever order the markers appear, regardless of definition order.
        val match = PassThroughDetector(listOf(curve(), paypal())).detect("PAYPAL *CRV*Sainsburys")
        assertEquals(listOf("PayPal", "Curve"), match?.accounts?.map { it.conduitAccountName })
        assertEquals("Sainsburys", match?.merchantName)
    }

    @Test
    fun doubledMarker_doesNotChainAConduitOntoItself() {
        // A malformed doubled marker must not produce a Curve→Curve leg; peeling stops after one hop.
        val match = PassThroughDetector(listOf(curve())).detect("CRV*CRV*Sainsburys")
        assertEquals(1, match?.hops?.size)
        assertEquals("CRV*Sainsburys", match?.merchantName)
    }

    @Test
    fun selfMatchingRule_terminates() {
        // A rule whose merchant equals its input must not loop: detection matches but nothing is peeled.
        val identity =
            PassThroughAccount(
                id = PassThroughAccountId(9),
                name = "Identity",
                conduitAccountName = "Identity",
                rules =
                    listOf(
                        PassThroughRule(
                            detectionPattern = "(?i)^X",
                            merchantPattern = "(?i)^(.*)$",
                        ),
                    ),
            )
        assertNull(PassThroughDetector(listOf(identity)).detect("X something"))
    }

    @Test
    fun refundWithoutCurveMarker_doesNotMatch() {
        assertNull(PassThroughDetector(listOf(curve())).detect("Refund: Something else"))
    }

    @Test
    fun nonCurveDescription_doesNotMatch() {
        assertNull(PassThroughDetector(listOf(curve())).detect("Tesco Stores 6725"))
    }
}
