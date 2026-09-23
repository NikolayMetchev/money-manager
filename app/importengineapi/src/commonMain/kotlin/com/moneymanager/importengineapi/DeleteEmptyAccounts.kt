package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import kotlinx.coroutines.flow.first

/**
 * Deletes the accounts in [createdAccountIds] — the ones an import or session created — that hold no
 * transfers, so a re-import does not leave behind duplicates it emptied without merging away. Returns
 * the deleted names.
 *
 * Trades count as activity: deleting an account cascades to its trades (`trade.from_account_id` /
 * `to_account_id` are `ON DELETE CASCADE`), so an account whose only movements are trades (e.g. the
 * wallet a transfer→trade conversion just re-imported into) must survive. A caller whose flow cannot
 * produce trades at all passes a null [tradeRepository] and skips that query.
 *
 * [source] and [keyPrefix] identify the deletions as this import's/session's own work.
 */
suspend fun ImportEngine.deleteEmptyImportCreatedAccounts(
    createdAccountIds: Collection<AccountId>,
    source: Source,
    accountRepository: AccountReadRepository,
    tradeRepository: TradeReadRepository?,
    keyPrefix: String = "reimport-delete",
): List<String> {
    if (createdAccountIds.isEmpty()) return emptyList()
    val remainingById = accountRepository.getAllAccounts().first().associateBy { it.id }
    val candidates = createdAccountIds.mapNotNull { remainingById[it] }
    if (candidates.isEmpty()) return emptyList()
    // Batched membership checks instead of a COUNT query per candidate account.
    val withTransfers = accountRepository.accountsWithTransfers(candidates.map { it.id })
    val withTrades = tradeRepository?.accountsWithTrades(candidates.map { it.id }).orEmpty()
    val emptyAccounts = candidates.filter { it.id !in withTransfers && it.id !in withTrades }
    if (emptyAccounts.isEmpty()) return emptyList()

    import(
        ImportBatch.manualEdits(
            accounts =
                emptyAccounts.map { account ->
                    ImportAccountIntent(
                        key = LocalAccountKey("$keyPrefix-${account.id.id}"),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = account.id,
                    )
                },
        ),
    )
    return emptyAccounts.map { it.name }
}
