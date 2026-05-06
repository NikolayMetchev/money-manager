@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.AccountMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AccountRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : AccountRepository {
    private val queries = database.accountQueries
    private val transferQueries = database.transferQueries
    private val attributeQueries = database.accountAttributeQueries

    override fun getAllAccounts(): Flow<List<Account>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountMapper::mapList)

    override fun getAccountById(id: AccountId): Flow<Account?> =
        queries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(AccountMapper::map) }

    override suspend fun createAccount(account: Account): AccountId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    queries.lastInsertRowId().executeAsOne()
                }
            AccountId(id)
        }

    override suspend fun createAccountsBatch(accounts: List<Account>): List<AccountId> =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                accounts.map { account ->
                    queries.insert(
                        name = account.name,
                        opening_date = account.openingDate.toEpochMilliseconds(),
                        category_id = account.categoryId,
                    )
                    val id = queries.lastInsertRowId().executeAsOne()
                    AccountId(id)
                }
            }
        }

    override suspend fun updateAccount(account: Account): Long =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                queries.update(
                    name = account.name,
                    category_id = account.categoryId,
                    id = account.id.id,
                )
                queries.selectRevisionById(account.id.id).executeAsOne()
            }
        }

    override suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
    ): Long =
        withContext(Dispatchers.Default) {
            val hasAttributeChanges =
                deletedAttributeIds.isNotEmpty() ||
                    updatedAttributes.isNotEmpty() ||
                    newAttributes.isNotEmpty()

            val effectiveAccountId = account?.id ?: accountId

            queries.transactionWithResult {
                // Step 1: Update account fields or bump revision for attribute-only changes
                if (account != null) {
                    queries.update(
                        name = account.name,
                        category_id = account.categoryId,
                        id = account.id.id,
                    )
                } else if (hasAttributeChanges) {
                    queries.bumpRevisionOnly(effectiveAccountId.id)
                }

                // Step 2: Apply attribute changes inside creation mode so triggers record
                // audit entries at the current revision without bumping it further
                if (hasAttributeChanges) {
                    database.beginCreationMode()
                    try {
                        deletedAttributeIds.forEach { id ->
                            attributeQueries.deleteById(id)
                        }

                        updatedAttributes.forEach { (id, attr) ->
                            val current = attributeQueries.selectById(id).executeAsOneOrNull()
                            if (current != null && current.attribute_type_id != attr.typeId.id) {
                                attributeQueries.deleteById(id)
                                attributeQueries.insert(
                                    account_id = effectiveAccountId.id,
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            } else {
                                attributeQueries.updateValue(attr.value, id)
                            }
                        }

                        newAttributes.forEach { attr ->
                            attributeQueries.insert(
                                account_id = effectiveAccountId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        }
                    } finally {
                        database.endCreationMode()
                    }
                }

                queries.selectRevisionById(effectiveAccountId.id).executeAsOne()
            }
        }

    override suspend fun deleteAccount(id: AccountId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }

    override suspend fun countTransfersByAccount(accountId: AccountId): Long =
        withContext(Dispatchers.Default) {
            transferQueries.countTransfersByAccount(accountId.id).executeAsOne()
        }

    override suspend fun getTransfersBetweenAccounts(
        accountA: AccountId,
        accountB: AccountId,
    ): List<Transfer> =
        withContext(Dispatchers.Default) {
            transferQueries
                .selectTransfersBetweenAccounts(
                    accountA = accountA.id,
                    accountB = accountB.id,
                    TransferMapper::mapRaw,
                ).executeAsList()
        }

    override suspend fun deleteAccountAndMoveTransactions(
        accountToDelete: AccountId,
        targetAccount: AccountId,
    ): Unit =
        withContext(Dispatchers.Default) {
            database.transaction {
                transferQueries.moveTransfersSourceAccount(
                    targetAccount = targetAccount.id,
                    accountToDelete = accountToDelete.id,
                )
                transferQueries.moveTransfersTargetAccount(
                    targetAccount = targetAccount.id,
                    accountToDelete = accountToDelete.id,
                )
                queries.delete(accountToDelete.id)
            }
        }
}
