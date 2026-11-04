package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant

/** The account a hand-booked fee is paid to. */
const val MANUAL_FEE_ACCOUNT_NAME: String = "Fees"

/**
 * Books a fee the person never entered: a [Source.Manual] transfer of [fee] (positive) from [from] to the
 * [MANUAL_FEE_ACCOUNT_NAME] account (created by name if missing), at [timestamp]. Returns the new transfer, so the
 * caller can add it to a reconciliation match.
 */
suspend fun ImportEngine.createManualFee(
    from: AccountId,
    fee: Money,
    timestamp: Instant,
    description: String,
): TransferId {
    require(fee.isPositive()) { "A fee must be positive" }
    val feeKey = LocalAccountKey("manual-fee-account")
    val rowKey = ImportRowKey.Manual(0)
    val created =
        import(
            ImportBatch(
                transfers =
                    listOf(
                        ImportTransfer(
                            source = Source.Manual,
                            rowKey = rowKey,
                            fromAccount = AccountRef.Existing(from),
                            toAccount = AccountRef.Local(feeKey),
                            timestamp = timestamp,
                            description = description,
                            amount = fee,
                        ),
                    ),
                dedupePolicy = DedupePolicy.None,
                accountsToCreate =
                    listOf(
                        ImportAccountIntent(
                            key = feeKey,
                            source = Source.Manual,
                            match = AccountMatchKey.ByName(MANUAL_FEE_ACCOUNT_NAME),
                            name = MANUAL_FEE_ACCOUNT_NAME,
                            openingDate = timestamp,
                        ),
                    ),
            ),
        )
    return requireNotNull(created.createdTransferIds[rowKey]) { "The fee was not created" }
}
