@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.AccountAuditEntryMapper
import com.moneymanager.database.mapper.ApiImportStrategyAuditEntryMapper
import com.moneymanager.database.mapper.CategoryAuditEntryMapper
import com.moneymanager.database.mapper.CsvImportStrategyAuditEntryMapper
import com.moneymanager.database.mapper.CurrencyAuditEntryMapper
import com.moneymanager.database.mapper.OwnershipAuditHistoryForAccountMapper
import com.moneymanager.database.mapper.PersonAccountOwnershipAuditEntryMapper
import com.moneymanager.database.mapper.PersonAttributeAuditEntryMapper
import com.moneymanager.database.mapper.PersonAuditEntryMapper
import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountAttributeAuditEntry
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.AuditReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class AuditReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : AuditReadRepository {
    private val auditSelectQueries = database.auditSelectQueries

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                auditSelectQueries
                    .selectAuditHistoryForTransfer(transferId.id)
                    .executeAsList()
                    .map(TransferAuditEntryMapper::map)

            attachAttributeChanges(transferId, entries)
        }

    override suspend fun getAuditHistoryForAccount(accountId: AccountId): List<AccountAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                auditSelectQueries
                    .selectAuditHistoryForAccount(accountId.id)
                    .executeAsList()
                    .map(AccountAuditEntryMapper::map)

            attachAccountAttributeChanges(accountId, entries)
        }

    override suspend fun getLatestAuditedAccountNames(): Map<Long, String> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectLatestAuditedAccountNames()
                .executeAsList()
                .associate { it.account_id to it.name }
        }

    override suspend fun getAuditHistoryForPerson(personId: PersonId): List<PersonAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                auditSelectQueries
                    .selectAuditHistoryForPerson(personId.id)
                    .executeAsList()
                    .map(PersonAuditEntryMapper::map)
            attachPersonAttributeChanges(personId, entries)
        }

    override suspend fun getAuditHistoryForPersonAccountOwnership(ownershipId: Long): List<PersonAccountOwnershipAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectAuditHistoryForPersonAccountOwnership(ownershipId)
                .executeAsList()
                .map(PersonAccountOwnershipAuditEntryMapper::map)
        }

    override suspend fun getOwnershipAuditHistoryForAccount(accountId: AccountId): List<PersonAccountOwnershipAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectOwnershipAuditHistoryForAccount(accountId.id)
                .executeAsList()
                .map(OwnershipAuditHistoryForAccountMapper::map)
        }

    override suspend fun getAuditHistoryForCurrency(currencyId: CurrencyId): List<CurrencyAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectAuditHistoryForCurrency(currencyId.id)
                .executeAsList()
                .map(CurrencyAuditEntryMapper::map)
        }

    override suspend fun getAuditHistoryForCategory(categoryId: Long): List<CategoryAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectAuditHistoryForCategory(categoryId)
                .executeAsList()
                .map(CategoryAuditEntryMapper::map)
        }

    override suspend fun getAttributeAuditByAccount(accountId: AccountId): List<AccountAttributeAuditEntry> =
        withContext(Dispatchers.Default) {
            fetchAccountAttributeAudit(accountId)
        }

    override suspend fun getAttributeAuditByPerson(personId: PersonId): List<PersonAttributeAuditEntry> =
        withContext(Dispatchers.Default) {
            fetchPersonAttributeAudit(personId)
        }

    override suspend fun getAuditHistoryForApiImportStrategy(strategyId: ApiImportStrategyId): List<ApiImportStrategyAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectAuditHistoryForApiImportStrategy(strategyId.id.toString())
                .executeAsList()
                .map(ApiImportStrategyAuditEntryMapper::map)
        }

    override suspend fun getAuditHistoryForCsvImportStrategy(strategyId: CsvImportStrategyId): List<CsvImportStrategyAuditEntry> =
        withContext(Dispatchers.Default) {
            auditSelectQueries
                .selectAuditHistoryForCsvImportStrategy(strategyId.id.toString())
                .executeAsList()
                .map(CsvImportStrategyAuditEntryMapper::map)
        }

    private fun fetchAccountAttributeAudit(accountId: AccountId): List<AccountAttributeAuditEntry> =
        auditSelectQueries
            .selectAttributeAuditByAccount(accountId.id)
            .executeAsList()
            .map { row ->
                AccountAttributeAuditEntry(
                    id = row.id,
                    auditTimestamp = Instant.fromEpochMilliseconds(row.audit_timestamp),
                    accountId = AccountId(row.account_id),
                    revisionId = row.revision_id,
                    attributeType =
                        AttributeType(
                            id = AttributeTypeId(row.attribute_type_id),
                            name = row.attribute_type_name,
                        ),
                    auditType = mapAuditType(row.audit_type),
                    value = row.attribute_value,
                )
            }

    private fun attachAccountAttributeChanges(
        accountId: AccountId,
        entries: List<AccountAuditEntry>,
    ): List<AccountAuditEntry> {
        val allAttributeChanges = fetchAccountAttributeAudit(accountId)

        // Group attribute changes by revisionId
        val changesByRevision = allAttributeChanges.groupBy { it.revisionId }

        // Attach attribute changes to each audit entry based on revisionId
        return entries.map { entry ->
            entry.copy(attributeChanges = changesByRevision[entry.revisionId].orEmpty())
        }
    }

    private fun fetchPersonAttributeAudit(personId: PersonId): List<PersonAttributeAuditEntry> =
        auditSelectQueries
            .selectAttributeAuditByPerson(personId.id)
            .executeAsList()
            .map(PersonAttributeAuditEntryMapper::map)

    private fun attachPersonAttributeChanges(
        personId: PersonId,
        entries: List<PersonAuditEntry>,
    ): List<PersonAuditEntry> {
        val allAttributeChanges = fetchPersonAttributeAudit(personId)
        val changesByRevision = allAttributeChanges.groupBy { it.revisionId }
        return entries.map { entry ->
            entry.copy(attributeChanges = changesByRevision[entry.revisionId].orEmpty())
        }
    }

    private fun attachAttributeChanges(
        transferId: TransferId,
        entries: List<TransferAuditEntry>,
    ): List<TransferAuditEntry> {
        // Fetch all attribute audit entries for this transfer
        val allAttributeChanges =
            auditSelectQueries
                .selectAttributeAuditByTransfer(transferId.id)
                .executeAsList()
                .map { row ->
                    TransferAttributeAuditEntry(
                        id = row.id,
                        transactionId = TransferId(row.transfer_id),
                        revisionId = row.revision_id,
                        attributeType =
                            AttributeType(
                                id = AttributeTypeId(row.attribute_type_id),
                                name = row.attribute_type_name,
                            ),
                        auditType = mapAuditType(row.audit_type),
                        value = row.attribute_value,
                    )
                }

        // Group attribute changes by revisionId
        val changesByRevision = allAttributeChanges.groupBy { it.revisionId }

        // Attach attribute changes to each audit entry based on revisionId
        return entries.map { entry ->
            entry.copy(attributeChanges = changesByRevision[entry.revisionId].orEmpty())
        }
    }

    private fun mapAuditType(name: String): AuditType =
        when (name) {
            "INSERT" -> AuditType.INSERT
            "UPDATE" -> AuditType.UPDATE
            "DELETE" -> AuditType.DELETE
            else -> error("Unknown audit type: $name")
        }
}
