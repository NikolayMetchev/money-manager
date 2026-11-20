package com.moneymanager.importer

import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.CategoryWriteRepository
import com.moneymanager.domain.repository.write.CryptoWriteRepository
import com.moneymanager.domain.repository.write.CurrencyWriteRepository
import com.moneymanager.domain.repository.write.NewCryptoAsset
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalCurrencyKey
import kotlinx.coroutines.flow.first

/**
 * What a batch writes beside its transactions: currencies, crypto assets and their aliases, categories, and the account,
 * person and ownership edits, merges and deletes.
 */
internal class ReferenceDataWriter(
    private val currencyRepository: CurrencyWriteRepository,
    private val cryptoRepository: CryptoWriteRepository,
    private val categoryRepository: CategoryWriteRepository,
    private val accountRepository: AccountWriteRepository,
    private val personRepository: PersonWriteRepository,
    private val ownershipRepository: PersonAccountOwnershipWriteRepository,
    private val observationRepository: ObservationWriteRepository,
) {
    /** The ids of the assets [createAssets] made, by the batch's keys. */
    class CreatedAssets(
        val currencyIds: Map<LocalCurrencyKey, CurrencyId>,
        val cryptoIds: Map<LocalCryptoKey, CryptoId>,
    )

    /** Creates the batch's currencies and crypto assets, then applies its alias mutations. */
    suspend fun createAssets(batch: ImportBatch): CreatedAssets {
        val createdCurrencyIds = mutableMapOf<LocalCurrencyKey, CurrencyId>()
        for (intent in batch.currencies.creates()) {
            createdCurrencyIds[intent.key] =
                currencyRepository.upsertCurrencyByCode(
                    requireNotNull(intent.code),
                    requireNotNull(intent.name),
                    intent.fractionDigits,
                    intent.source,
                )
        }
        val cryptoCreates = batch.cryptoAssets.creates()
        val createdCryptoIds: Map<LocalCryptoKey, CryptoId> =
            if (cryptoCreates.isEmpty()) {
                emptyMap()
            } else {
                cryptoCreates
                    .map { it.key }
                    .zip(
                        cryptoRepository.upsertCryptosByCode(
                            cryptoCreates.map { NewCryptoAsset(requireNotNull(it.code), it.name, it.source) },
                        ),
                    ).toMap()
            }
        // After the batch's currencies and crypto assets exist, as an alias names its asset by code.
        for (m in batch.assetAliasMutations) {
            when (m) {
                is AssetAliasMutation.Set -> cryptoRepository.setAssetAlias(m.alias, m.assetCode, m.publisher)
                is AssetAliasMutation.Remove -> cryptoRepository.removeAssetAlias(m.alias)
            }
        }
        return CreatedAssets(createdCurrencyIds, createdCryptoIds)
    }

    /** Creates the batch's categories. */
    suspend fun createCategories(batch: ImportBatch): Map<LocalCategoryKey, Long> {
        val createdCategoryIds = mutableMapOf<LocalCategoryKey, Long>()
        for (intent in batch.categories.creates()) {
            createdCategoryIds[intent.key] =
                categoryRepository.createCategory(
                    Category(name = requireNotNull(intent.name), parentId = intent.parentId),
                    intent.source,
                )
        }
        return createdCategoryIds
    }

    /** Applies the batch's account, category, currency, crypto and person updates. */
    suspend fun applyUpdates(batch: ImportBatch) {
        for (intent in batch.accountsToCreate.updates()) {
            accountRepository.updateAccountWithAttributes(
                account = intent.account,
                accountId = requireNotNull(intent.existingId),
                deletedAttributeIds = intent.deletedAttributeIds,
                updatedAttributes = intent.updatedAttributes,
                newAttributes = intent.attributes,
                source = intent.source,
            )
        }
        for (intent in batch.categories.updates()) {
            categoryRepository.updateCategory(requireNotNull(intent.category), intent.source)
        }
        for (intent in batch.currencies.updates()) {
            currencyRepository.updateCurrency(requireNotNull(intent.currency), intent.source)
        }
        for (intent in batch.cryptoAssets.updates()) {
            cryptoRepository.updateCryptoAsset(requireNotNull(intent.crypto), intent.source)
        }
        for (intent in batch.peopleToCreate.updates()) {
            personRepository.updatePersonWithAttributes(
                person = intent.person,
                personId = requireNotNull(intent.existingId),
                deletedAttributeIds = intent.deletedAttributeIds,
                updatedAttributes = intent.updatedAttributes,
                newAttributes = intent.attributes,
                source = intent.source,
            )
        }
    }

    /** Merges and unmerges accounts. Before deletes: a merge already removes the absorbed account. */
    suspend fun applyMerges(batch: ImportBatch) {
        batch.accountMerges.forEach { accountRepository.mergeAccounts(it.deletedId, it.survivingId, it.mapDeletedName) }
        batch.accountUnmerges.forEach { accountRepository.unmergeAccount(it) }
    }

    /** Deletes the batch's ownerships: dependents go first. */
    suspend fun deleteOwnerships(batch: ImportBatch) {
        batch.ownerships.deletes().forEach { ownershipRepository.deleteOwnership(requireNotNull(it.existingId)) }
    }

    /** Deletes the batch's accounts, categories, currencies, crypto assets and people, once the transactions are gone. */
    suspend fun applyDeletes(batch: ImportBatch) {
        // An account an observation still names isn't empty, even when no transaction shows it: a joined twin's
        // other record keeps its own account (a placeholder) while the merge books the real one.
        val accountDeletes = batch.accountsToCreate.deletes().map { requireNotNull(it.existingId) }
        val observed = observationRepository.getAccountsWithObservations(accountDeletes)
        accountRepository.deleteAccounts(accountDeletes.filter { it !in observed })
        batch.categories.deletes().forEach { categoryRepository.deleteCategory(requireNotNull(it.existingId)) }
        batch.currencies.deletes().forEach { currencyRepository.deleteCurrency(requireNotNull(it.existingId)) }
        batch.cryptoAssets.deletes().forEach { cryptoRepository.deleteCryptoAsset(requireNotNull(it.existingId)) }
        batch.peopleToCreate.deletes().forEach { personRepository.deletePerson(requireNotNull(it.existingId)) }
    }
}
