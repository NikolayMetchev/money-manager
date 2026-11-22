package com.moneymanager.builtin

import com.moneymanager.domain.model.rules.SettlementWindow

/** Reconciliation windows shared by the statement (QIF, PDF, OFX and CSV bank) strategies. */
object StatementDefaults {
    /**
     * Cross-source window for the bank and card statement strategies (Santander, American Express). For an exact
     * same-accounts match it allows two days, like the other statement exports (Curve, PayPal).
     */
    internal const val QIF_STATEMENT_RECONCILE_WINDOW_SECONDS = 172_800L

    /**
     * How far a card's "PAYMENT RECEIVED" placeholder may sit from the bank's direct debit it records. Statement
     * dates are date-only and a card books a payment a few days after the paying bank, counting only the days banks
     * settle on: the Easter 2021 payment left Santander on Maundy Thursday and reached American Express on Easter
     * Monday, four calendar days but no business days apart. Clock time wide enough for that pairs unrelated
     * payments of the same amount.
     */
    internal val STATEMENT_PLACEHOLDER_WINDOW = SettlementWindow.BusinessDays(3)
}
