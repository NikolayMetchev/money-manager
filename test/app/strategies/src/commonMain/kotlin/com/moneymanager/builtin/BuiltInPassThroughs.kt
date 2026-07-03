package com.moneymanager.builtin

import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule

/**
 * Built-in pass-through (conduit) definitions, formerly seeded by StaticSeed.sq. The shared
 * Refund/Cancellation prefix group covers the cancellation rows Crypto.com emits with a prefixed
 * description ("Refund: Crv*Navan", "Refund reversal: Crv*Navan", "Cancellation: Crv*Zipcar Annual
 * Plan") — these are routed back through the conduit in the opposite direction and the merchant
 * pattern still extracts the bare merchant so refunds hit the same merchant account as the charge.
 */
object BuiltInPassThroughs {
    /**
     * "Curve": a wrapper card whose charges are forwarded to an underlying funding card; the
     * underlying statement carries the real merchant behind a "CRV*" marker. The single rule covers
     * both Crypto.com ("Crv*Sainsburys") and Monzo ("CRV*NATIONAL LOTTERY   London   GBR").
     */
    fun curve(): PassThroughAccount =
        PassThroughAccount(
            id = PassThroughAccountId(0),
            name = "Curve",
            conduitAccountName = "Curve",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        merchantTemplate = "$1",
                    ),
                ),
        )

    /**
     * "PayPal": statement rows carry the real merchant behind a "PAYPAL *" / "Paypal *" marker.
     * Works as the outermost hop of a chain too (the prefix only ever appears at the very start of a
     * raw description; when PayPal is an inner hop the outer rule has already stripped it).
     */
    fun payPal(): PassThroughAccount =
        PassThroughAccount(
            id = PassThroughAccountId(0),
            name = "PayPal",
            conduitAccountName = "PayPal",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?PAYPAL\\s*\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?PAYPAL\\s*\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        merchantTemplate = "$1",
                    ),
                ),
        )

    /** All built-in pass-through definitions. */
    fun builtInPassThroughs(): List<PassThroughAccount> = listOf(curve(), payPal())
}
