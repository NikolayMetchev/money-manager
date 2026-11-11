package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationFocus
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.model.reconciliation.reconciliationMonth
import com.moneymanager.reconciliation.FeeSuggestion
import com.moneymanager.reconciliation.OtherSideSuggestion

/**
 * Where a suggestion comes from: [sourceName] (e.g. Koinly) and the [walletName] its record is on, the [leg] of that record
 * (what the source's file row is found from), and the [focus] that opens the Reconciliation tab at that wallet and month.
 */
data class SuggestionOrigin(
    val sourceName: String,
    val walletName: String,
    val leg: ReconciliationLeg,
    val focus: ReconciliationFocus,
) {
    /** Names the source exactly: `Koinly (Koinly · Revolut)`. */
    val label: String get() = "$sourceName ($walletName)"
}

/** The record the source made of the other side of [this] unnamed exchange. */
fun OtherSideSuggestion.origin(shadowAccounts: Map<AccountId, ShadowAccount>): SuggestionOrigin = shadowTrade.origin(shadowAccounts)

private fun Trade.origin(shadowAccounts: Map<AccountId, ShadowAccount>): SuggestionOrigin {
    val wallet = shadowAccounts[fromAccountId]
    return originOf(
        wallet = wallet,
        walletId = fromAccountId,
        leg =
            ReconciliationLeg(
                transactionId = id.id,
                kind = LegTransactionKind.TRADE,
                timestamp = timestamp,
                description = description,
                accountId = fromAccountId,
                counterpartyAccountId = null,
                amount = from,
            ),
    )
}

/** The movement the source recorded the withdrawal's fee on: its net transfer, which is the file row the fee came with. */
fun FeeSuggestion.origin(shadowAccounts: Map<AccountId, ShadowAccount>): SuggestionOrigin = netTransfer.origin(shadowAccounts)

private fun Transfer.origin(shadowAccounts: Map<AccountId, ShadowAccount>): SuggestionOrigin =
    originOf(
        wallet = shadowAccounts[sourceAccountId],
        walletId = sourceAccountId,
        leg =
            ReconciliationLeg(
                transactionId = id.id,
                kind = LegTransactionKind.TRANSFER,
                timestamp = timestamp,
                description = description,
                accountId = sourceAccountId,
                counterpartyAccountId = targetAccountId,
                amount = amount,
            ),
    )

private fun originOf(
    wallet: ShadowAccount?,
    walletId: AccountId,
    leg: ReconciliationLeg,
): SuggestionOrigin {
    val sourceName = wallet?.sourceName ?: "A reconciliation source"
    return SuggestionOrigin(
        sourceName = sourceName,
        walletName = wallet?.name ?: walletId.toString(),
        leg = leg,
        focus = ReconciliationFocus(sourceName, walletId, reconciliationMonth(leg.timestamp), leg.transactionId),
    )
}

/** Links from a suggestion to the source's own file row, and to its place in the Reconciliation tab. */
@Composable
internal fun SuggestionLinks(
    origin: SuggestionOrigin,
    onOpenSource: (ReconciliationLeg) -> Unit,
    onOpenReconciliation: (ReconciliationFocus) -> Unit,
) {
    Row {
        TextButton(onClick = { onOpenSource(origin.leg) }) { Text("Open the ${origin.sourceName} file row") }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = { onOpenReconciliation(origin.focus) }) { Text("Show in Reconciliation") }
    }
}
