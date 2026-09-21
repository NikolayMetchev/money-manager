package com.moneymanager.database.repository

import com.moneymanager.database.mapper.AccountAuditEntryMapper
import com.moneymanager.database.mapper.ApiImportStrategyAuditEntryMapper
import com.moneymanager.database.mapper.CategoryAuditEntryMapper
import com.moneymanager.database.mapper.CryptoAuditEntryMapper
import com.moneymanager.database.mapper.CsvImportStrategyAuditEntryMapper
import com.moneymanager.database.mapper.CurrencyAuditEntryMapper
import com.moneymanager.database.mapper.ExchangeOrderAuditEntryMapper
import com.moneymanager.database.mapper.ImportDirectoryAuditEntryMapper
import com.moneymanager.database.mapper.OwnershipAuditHistoryForAccountMapper
import com.moneymanager.database.mapper.PersonAccountOwnershipAuditEntryMapper
import com.moneymanager.database.mapper.PersonAttributeAuditEntryMapper
import com.moneymanager.database.mapper.PersonAuditEntryMapper
import com.moneymanager.database.mapper.TradeAuditEntryMapper
import com.moneymanager.database.mapper.TransferAuditEntryMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountAttributeAuditEntry
import com.moneymanager.domain.model.AccountAuditEntry
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.CategoryAuditEntry
import com.moneymanager.domain.model.CryptoAuditEntry
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyAuditEntry
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.ExchangeOrderAuditEntry
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.ImportDirectoryAuditEntry
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.PersonAccountOwnershipAuditEntry
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonAuditEntry
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeAuditEntry
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferAttributeAuditEntry
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyAuditEntry
import com.moneymanager.domain.repository.AuditReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class AuditReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : AuditReadRepository {
    private val auditSelectQueries = database.auditSelectQueries
    private val entitySources = EntitySourceReader(database)

    override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> =
        withContext(Dispatchers.Default) {
            attachAttributeChanges(
                transferId,
                auditHistory(
                    EntityType.TRANSFER,
                    { auditSelectQueries.selectAuditHistoryForTransfer(transferId.id).executeAsList() },
                    TransferAuditEntryMapper::map,
                    { EntityRevision(it.transferId.id, it.revisionId) },
                ) { entry, source -> entry.copy(source = source) },
            )
        }

    override suspend fun getAuditHistoryForAccount(accountId: AccountId): List<AccountAuditEntry> =
        withContext(Dispatchers.Default) {
            attachAccountAttributeChanges(
                accountId,
                auditHistory(
                    EntityType.ACCOUNT,
                    { auditSelectQueries.selectAuditHistoryForAccount(accountId.id).executeAsList() },
                    AccountAuditEntryMapper::map,
                    { EntityRevision(it.accountId.id, it.revisionId) },
                ) { entry, source -> entry.copy(source = source) },
            )
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
            attachPersonAttributeChanges(
                personId,
                auditHistory(
                    EntityType.PERSON,
                    { auditSelectQueries.selectAuditHistoryForPerson(personId.id).executeAsList() },
                    PersonAuditEntryMapper::map,
                    { EntityRevision(it.personId.id, it.revisionId) },
                ) { entry, source -> entry.copy(source = source) },
            )
        }

    override suspend fun getAuditHistoryForPersonAccountOwnership(ownershipId: Long): List<PersonAccountOwnershipAuditEntry> =
        ownershipAuditHistory(
            { auditSelectQueries.selectAuditHistoryForPersonAccountOwnership(ownershipId).executeAsList() },
            PersonAccountOwnershipAuditEntryMapper::map,
        )

    override suspend fun getOwnershipAuditHistoryForAccount(accountId: AccountId): List<PersonAccountOwnershipAuditEntry> =
        ownershipAuditHistory(
            { auditSelectQueries.selectOwnershipAuditHistoryForAccount(accountId.id).executeAsList() },
            OwnershipAuditHistoryForAccountMapper::map,
        )

    override suspend fun getAuditHistoryForCurrency(currencyId: CurrencyId): List<CurrencyAuditEntry> =
        auditHistory(
            EntityType.CURRENCY,
            { auditSelectQueries.selectAuditHistoryForCurrency(currencyId.id).executeAsList() },
            CurrencyAuditEntryMapper::map,
            { EntityRevision(it.currencyId.id, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    override suspend fun getAuditHistoryForCrypto(cryptoId: CryptoId): List<CryptoAuditEntry> =
        auditHistory(
            EntityType.CRYPTO,
            { auditSelectQueries.selectAuditHistoryForCrypto(cryptoId.id).executeAsList() },
            CryptoAuditEntryMapper::map,
            { EntityRevision(it.cryptoId.id, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    override suspend fun getAuditHistoryForTrade(tradeId: TradeId): List<TradeAuditEntry> =
        auditHistory(
            EntityType.TRADE,
            { auditSelectQueries.selectAuditHistoryForTrade(tradeId.id).executeAsList() },
            TradeAuditEntryMapper::map,
            { EntityRevision(it.tradeId.id, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    override suspend fun getAuditHistoryForExchangeOrder(orderId: ExchangeOrderId): List<ExchangeOrderAuditEntry> =
        auditHistory(
            EntityType.EXCHANGE_ORDER,
            { auditSelectQueries.selectAuditHistoryForExchangeOrder(orderId.id).executeAsList() },
            ExchangeOrderAuditEntryMapper::map,
            { EntityRevision(it.orderId.id, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    override suspend fun getAuditHistoryForCategory(categoryId: Long): List<CategoryAuditEntry> =
        auditHistory(
            EntityType.CATEGORY,
            { auditSelectQueries.selectAuditHistoryForCategory(categoryId).executeAsList() },
            CategoryAuditEntryMapper::map,
            { EntityRevision(it.categoryId, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    override suspend fun getAttributeAuditByAccount(accountId: AccountId): List<AccountAttributeAuditEntry> =
        withContext(Dispatchers.Default) {
            fetchAccountAttributeAudit(accountId)
        }

    override suspend fun getAttributeAuditByPerson(personId: PersonId): List<PersonAttributeAuditEntry> =
        withContext(Dispatchers.Default) {
            fetchPersonAttributeAudit(personId)
        }

    override suspend fun getAuditHistoryForApiImportStrategy(strategyId: ApiImportStrategyId): List<ApiImportStrategyAuditEntry> =
        auditEntries(
            { auditSelectQueries.selectAuditHistoryForApiImportStrategy(strategyId.id.toString()).executeAsList() },
            ApiImportStrategyAuditEntryMapper::map,
        )

    override suspend fun getAuditHistoryForCsvImportStrategy(strategyId: CsvImportStrategyId): List<CsvImportStrategyAuditEntry> =
        auditEntries(
            { auditSelectQueries.selectAuditHistoryForCsvImportStrategy(strategyId.id.toString()).executeAsList() },
            CsvImportStrategyAuditEntryMapper::map,
        )

    override suspend fun getAuditHistoryForImportDirectory(directoryId: ImportDirectoryId): List<ImportDirectoryAuditEntry> =
        auditEntries(
            { auditSelectQueries.selectAuditHistoryForImportDirectory(directoryId.id.toString()).executeAsList() },
            ImportDirectoryAuditEntryMapper::map,
        )

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
                    groupKey = row.group_key,
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
                        groupKey = row.group_key,
                    )
                }

        // Group attribute changes by revisionId
        val changesByRevision = allAttributeChanges.groupBy { it.revisionId }

        // Attach attribute changes to each audit entry based on revisionId
        return entries.map { entry ->
            entry.copy(attributeChanges = changesByRevision[entry.revisionId].orEmpty())
        }
    }

    /**
     * One entity's audit history: read its rows off the caller's thread, map them, attach
     * provenance. Every per-entity audit table has its own payload columns — and so its own query
     * and mapper — but from `executeAsList()` onwards they are the same three steps, so the query
     * arrives as a lambda rather than a `Query<Row>` and the two per-entity details that remain
     * (which revision a row belongs to, how to carry a source on it) arrive as functions.
     */
    private suspend fun <Row : Any, Entry : Any> auditHistory(
        entityType: EntityType,
        rows: () -> List<Row>,
        mapper: (Row) -> Entry,
        revisionOf: (Entry) -> EntityRevision,
        withSource: (Entry, SourceRecord?) -> Entry,
    ): List<Entry> =
        withContext(Dispatchers.Default) {
            rows().map(mapper).withSources(entityType, revisionOf, withSource)
        }

    /** The audit tables that record no `entity_source` provenance of their own. */
    private suspend fun <Row : Any, Entry : Any> auditEntries(
        rows: () -> List<Row>,
        mapper: (Row) -> Entry,
    ): List<Entry> = withContext(Dispatchers.Default) { rows().map(mapper) }

    /**
     * Ownership audit rows are read two ways — for one ownership, and for every ownership of an
     * account — so both queries and mappers land here.
     */
    private suspend fun <Row : Any> ownershipAuditHistory(
        rows: () -> List<Row>,
        mapper: (Row) -> PersonAccountOwnershipAuditEntry,
    ): List<PersonAccountOwnershipAuditEntry> =
        auditHistory(
            EntityType.PERSON_ACCOUNT_OWNERSHIP,
            rows,
            mapper,
            { EntityRevision(it.personAccountOwnershipId, it.revisionId) },
        ) { entry, source -> entry.copy(source = source) }

    /**
     * Attaches provenance to freshly mapped audit rows. The audit queries select the audited entity
     * only; sources come from the one shared `entity_source` read and are paired back by
     * (entity id, revision id) — the same pairing the per-query LEFT JOIN used to do in SQL.
     */
    private fun <T : Any> List<T>.withSources(
        entityType: EntityType,
        key: (T) -> EntityRevision,
        attach: (T, SourceRecord?) -> T,
    ): List<T> {
        if (isEmpty()) return this
        val sources = entitySources.sourcesByRevision(entityType, map { key(it).entityId }.toSet())
        return map { attach(it, sources[key(it)]) }
    }

    private fun mapAuditType(name: String): AuditType =
        when (name) {
            "INSERT" -> AuditType.INSERT
            "UPDATE" -> AuditType.UPDATE
            "DELETE" -> AuditType.DELETE
            else -> error("Unknown audit type: $name")
        }
}
