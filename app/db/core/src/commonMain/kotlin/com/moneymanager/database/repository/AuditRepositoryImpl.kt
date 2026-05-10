@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.mapper.AccountAuditEntryMapper
import com.moneymanager.database.mapper.CategoryAuditEntryMapper
import com.moneymanager.database.mapper.CurrencyAuditEntryMapper
import com.moneymanager.database.mapper.OwnershipAuditHistoryForAccountMapper
import com.moneymanager.database.mapper.PersonAccountOwnershipAuditEntryMapper
import com.moneymanager.database.mapper.PersonAuditEntryMapper
import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountAttributeAuditEntry
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuditRepositoryImpl(
    database: MoneyManagerDatabase,
) : AuditRepository {
    private val queries = database.auditQueries

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries
                    .selectAuditHistoryForTransfer(transferId.id)
                    .executeAsList()
                    .map(TransferAuditEntryMapper::map)

            attachAttributeChanges(transferId, entries)
        }

    override suspend fun getAuditHistoryForAccount(accountId: AccountId): List<AccountAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries
                    .selectAuditHistoryForAccount(accountId.id)
                    .executeAsList()
                    .map(AccountAuditEntryMapper::map)

            attachAccountAttributeChanges(accountId, entries)
        }

    override suspend fun getAuditHistoryForPerson(personId: PersonId): List<PersonAuditEntry> =
        withContext(Dispatchers.Default) {
            val entries =
                queries
                    .selectAuditHistoryForPerson(personId.id)
                    .executeAsList()
                    .map(PersonAuditEntryMapper::map)
            attachPersonAttributeChanges(personId, entries)
        }

    override suspend fun getAuditHistoryForPersonAccountOwnership(ownershipId: Long): List<PersonAccountOwnershipAuditEntry> =
        withContext(Dispatchers.Default) {
            queries
                .selectAuditHistoryForPersonAccountOwnership(ownershipId)
                .executeAsList()
                .map(PersonAccountOwnershipAuditEntryMapper::map)
        }

    override suspend fun getOwnershipAuditHistoryForAccount(accountId: AccountId): List<PersonAccountOwnershipAuditEntry> =
        withContext(Dispatchers.Default) {
            queries
                .selectOwnershipAuditHistoryForAccount(accountId.id)
                .executeAsList()
                .map(OwnershipAuditHistoryForAccountMapper::map)
        }

    override suspend fun getAuditHistoryForCurrency(currencyId: CurrencyId): List<CurrencyAuditEntry> =
        withContext(Dispatchers.Default) {
            queries
                .selectAuditHistoryForCurrency(currencyId.id)
                .executeAsList()
                .map(CurrencyAuditEntryMapper::map)
        }

    override suspend fun getAuditHistoryForCategory(categoryId: Long): List<CategoryAuditEntry> =
        withContext(Dispatchers.Default) {
            queries
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

    private fun fetchAccountAttributeAudit(accountId: AccountId): List<AccountAttributeAuditEntry> =
        queries
            .selectAttributeAuditByAccount(accountId.id)
            .executeAsList()
            .map { row ->
                AccountAttributeAuditEntry(
                    id = row.id,
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
        queries
            .selectAttributeAuditByPerson(personId.id)
            .executeAsList()
            .map { row ->
                PersonAttributeAuditEntry(
                    id = row.id,
                    personId = PersonId(row.person_id),
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
            queries
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
