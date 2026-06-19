package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId

/**
 * The central write engine. It performs bulk [import]s and is also the **single entry point for every
 * manual edit** (create/update/delete of transfers, accounts, categories, people and ownerships) — the
 * UI never writes through repositories directly. Every write first consults an [EditGate], so the app
 * can block all edits at one place when they would be unsafe (e.g. a cloud-backed database whose remote
 * copy is ahead).
 *
 * Import logic and the source/provenance recording for imported entities live in the database-backed
 * implementation ([com.moneymanager.importer.ImportEngineImpl]); CSV/QIF/API importers depend only on
 * this interface and build an [ImportBatch]. The binding to the implementation happens where services
 * are assembled.
 */
interface ImportEngine {
    /**
     * @param batchSize How many transfers to write per database transaction. The default writes the
     *   whole batch in a single transaction (the behaviour all importers relied on historically). Large
     *   producers (e.g. the sample-data generator, which creates hundreds of thousands of transfers) pass
     *   a smaller value so the write is chunked into several transactions and [onProgress] reports
     *   fine-grained progress instead of freezing on one giant transaction.
     */
    suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)? = null,
        batchSize: Int = Int.MAX_VALUE,
    ): ImportResult

    // region Manual edits — the UI routes every create/update/delete through these gated methods.

    suspend fun createTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>> = emptyMap(),
        sources: List<Source>,
    ): List<TransferId>

    suspend fun updateTransfer(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        transactionId: TransferId,
        source: Source,
    )

    suspend fun deleteTransaction(id: Long)

    suspend fun createAccount(
        account: Account,
        source: Source,
    ): AccountId

    suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long

    suspend fun deleteAccount(id: AccountId)

    suspend fun mergeAccounts(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
    ): MergeId

    suspend fun unmergeAccount(mergeId: MergeId)

    suspend fun createCategory(
        category: Category,
        source: Source,
    ): Long

    suspend fun updateCategory(
        category: Category,
        source: Source,
    )

    suspend fun deleteCategory(id: Long)

    suspend fun createPerson(
        person: Person,
        source: Source,
    ): PersonId

    suspend fun updatePersonWithAttributes(
        person: Person?,
        personId: PersonId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long

    suspend fun deletePerson(id: PersonId)

    suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
        source: Source,
    ): Long

    suspend fun deleteOwnership(id: Long)

    // endregion
}
