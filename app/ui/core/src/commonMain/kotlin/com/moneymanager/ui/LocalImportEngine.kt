package com.moneymanager.ui

import androidx.compose.runtime.staticCompositionLocalOf
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
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult

/**
 * The [ImportEngine] for the active database, provided once at the app root
 * ([com.moneymanager.ui.MoneyManagerApp]). It is the single entry point for every manual edit
 * (create/update/delete of transfers, accounts, categories, people, ownerships), so the write can be
 * blocked centrally when editing is locked (e.g. a cloud-backed database whose remote copy is ahead).
 *
 * Exposed as a composition local rather than threaded through every screen because the editing dialogs
 * are deeply nested (e.g. an account picker inside a transaction dialog). The default is a stub that
 * throws only if a write is *invoked* without a provider — so render-only previews/tests work, while a
 * test that exercises an edit must provide a real or fake engine via
 * `CompositionLocalProvider(LocalImportEngine provides …)`.
 */
val LocalImportEngine =
    staticCompositionLocalOf<ImportEngine> { UnprovidedImportEngine }

/** Stub used only when no engine is provided; every write fails loudly so missing wiring is obvious. */
private object UnprovidedImportEngine : ImportEngine {
    private fun fail(): Nothing = error("LocalImportEngine not provided — host this screen under MoneyManagerApp or provide it in tests")

    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)?,
        batchSize: Int,
    ): ImportResult = fail()

    override suspend fun createTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>>,
        sources: List<Source>,
    ): List<TransferId> = fail()

    override suspend fun updateTransfer(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        transactionId: TransferId,
        source: Source,
    ) = fail()

    override suspend fun deleteTransaction(id: Long) = fail()

    override suspend fun createAccount(
        account: Account,
        source: Source,
    ): AccountId = fail()

    override suspend fun updateAccountWithAttributes(
        account: Account?,
        accountId: AccountId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long = fail()

    override suspend fun deleteAccount(id: AccountId) = fail()

    override suspend fun mergeAccounts(
        deletedAccount: AccountId,
        survivingAccount: AccountId,
    ): MergeId = fail()

    override suspend fun unmergeAccount(mergeId: MergeId) = fail()

    override suspend fun createCategory(
        category: Category,
        source: Source,
    ): Long = fail()

    override suspend fun updateCategory(
        category: Category,
        source: Source,
    ) = fail()

    override suspend fun deleteCategory(id: Long) = fail()

    override suspend fun createPerson(
        person: Person,
        source: Source,
    ): PersonId = fail()

    override suspend fun updatePersonWithAttributes(
        person: Person?,
        personId: PersonId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long = fail()

    override suspend fun deletePerson(id: PersonId) = fail()

    override suspend fun createOwnership(
        personId: PersonId,
        accountId: AccountId,
        source: Source,
    ): Long = fail()

    override suspend fun deleteOwnership(id: Long) = fail()
}
