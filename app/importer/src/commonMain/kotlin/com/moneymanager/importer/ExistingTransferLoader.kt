package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.widen
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.ApiSessionWriteRepository
import com.moneymanager.domain.repository.write.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import com.moneymanager.domain.repository.write.HolidayCalendarWriteRepository
import com.moneymanager.domain.repository.write.ImportRecordWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.ObservationSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/** Upper bound on the legs walked along one pass-through chain; real chains are a few hops (card → Curve → PayPal → merchant). */
private const val MAX_PASS_THROUGH_CHAIN_LEGS = 16

/** Loads the existing transfers, relationships and settlement calendars a batch's rows are deduplicated and reconciled against. */
internal class ExistingTransferLoader(
    private val transactionRepository: TransactionWriteRepository,
    private val accountAttributeRepository: AccountAttributeWriteRepository,
    private val attributeTypeRepository: AttributeTypeWriteRepository,
    private val csvImportRepository: CsvImportWriteRepository,
    private val apiSessionRepository: ApiSessionWriteRepository,
    private val settingsRepository: SettingsWriteRepository,
    private val holidayCalendarRepository: HolidayCalendarWriteRepository,
    private val relationshipRepository: RelationshipWriteRepository,
    private val observationRepository: ObservationWriteRepository,
    private val importRecordRepository: ImportRecordWriteRepository,
) {
    /**
     * Existing records of the batch's own source (see [ImportDeduper]'s `ownSourceLegs`): what its CSV files created on
     * an earlier import, and the other lines of the statements it covers. An account books a movement once, on one
     * date, so every export of its statements records it at that time; a record carrying the batch's unique identifier
     * ([ExistingTransferInfo.uniqueKey]), booked from the statement of an account the batch's rows are statements of,
     * at a time and amount none of those rows records, is another line of that account's statements (an equal purchase
     * two days earlier, posted onto the next month's card statement), never another source's record of a row's
     * movement. Another export of the same statement still reconciles, as does a second account's export of a
     * transfer between the two: its lines are that account's statement.
     */
    suspend fun ownSourceLegs(
        transfers: List<ImportTransfer>,
        existing: List<ExistingTransferInfo>,
    ): Set<TransferId> {
        val ownFiles =
            transfers
                .mapNotNullTo(mutableSetOf()) { (it.source as? Source.Csv)?.importId }
                .flatMapTo(mutableSetOf()) { csvImportRepository.getTransferIdsCreatedByImport(it) }
        val statementLines =
            transfers.mapNotNullTo(mutableSetOf()) { transfer ->
                (transfer.source as? Source.Csv)?.statementAccountId?.let { StatementLine(it, transfer.timestamp, transfer.amount) }
            }
        val identified = existing.filter { it.uniqueKey.isNotEmpty() }.associateBy { it.transferId }
        if (statementLines.isEmpty() || identified.isEmpty()) return ownFiles
        val statementAccounts = statementLines.mapTo(mutableSetOf()) { it.account }
        val otherLines =
            csvImportRepository
                .getStatementAccounts(identified.keys)
                .filter { (id, account) ->
                    val transfer = identified.getValue(id).transfer
                    account in statementAccounts && StatementLine(account, transfer.timestamp, transfer.amount) !in statementLines
                }.keys
        return ownFiles + otherLines
    }

    /** One line of [account]'s statement: what it booked, and when. */
    private data class StatementLine(
        val account: AccountId,
        val timestamp: Instant?,
        val amount: Money?,
    )

    /**
     * The relationships touching [existing], loaded once for the deduper. Among them: the `reconciled`
     * links whose related end another record already reconciled against (a leg only ever stands for one movement,
     * so a second row must not be excused against it — that would drop a genuine movement from the
     * balances), and the `pass-through` links that chain a conduit's legs together.
     */
    suspend fun existingRelationships(existing: List<ExistingTransferInfo>): List<Relationship> {
        val repository = relationshipRepository
        if (existing.isEmpty()) return emptyList()
        return repository.getByTransactions(existing.map { it.transferId })
    }

    /**
     * For every existing leg that a `pass-through` relationship continues, the legs after it along its
     * chain (main -> leg 1 -> leg 2 …), in order — see [ImportDeduper]'s `passThroughOnward`. A chain's
     * onward legs start at the conduit the batch's own legs touch, so they are almost always among
     * [existing]; any that are not are fetched by id.
     */
    suspend fun passThroughOnward(
        existing: List<ExistingTransferInfo>,
        relationships: List<Relationship>,
    ): Map<TransferId, List<Transfer>> {
        val next =
            relationships
                .filter { it.relationshipType.id.id == WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID }
                .mapNotNull { it.transferPair }
                .toMap()
        if (next.isEmpty()) return emptyMap()
        val known = existing.associate { it.transferId to it.transfer }
        val missing = next.values.filterNot { it in known }
        val transfers = if (missing.isEmpty()) known else known + transactionRepository.getTransactionsByIds(missing)
        return next.keys.associateWith { start ->
            buildList {
                var id = next[start]
                // A chain is a handful of hops; the bound only guards against a malformed cycle.
                while (id != null && size < MAX_PASS_THROUGH_CHAIN_LEGS) {
                    add(transfers[id] ?: break)
                    id = next[id]
                }
            }
        }
    }

    /**
     * The locations a business-day window of [policy] counts on: every account's `location` attribute, the default
     * location and the calendars they name. A policy with clock-time windows only reads none of them.
     */
    suspend fun settlementCalendars(policy: DedupePolicy): SettlementCalendars =
        if (policy.windows().none { it is SettlementWindow.BusinessDays }) SettlementCalendars.WEEKENDS_ONLY else loadSettlementCalendars()

    suspend fun loadSettlementCalendars(): SettlementCalendars {
        val locations =
            accountAttributeRepository
                .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_LOCATION_ATTR_TYPE_ID))
                .first()
                .associate { it.accountId to it.value }
        val defaultLocation = settingsRepository.getDefaultLocation().first()
        // Only the calendars a location names: a database holds every installed one, most of which nothing uses.
        val calendars = holidayCalendarRepository.getByCodes(locations.values + listOfNotNull(defaultLocation))
        return SettlementCalendars(locations, defaultLocation, calendars)
    }

    suspend fun loadExisting(
        transfers: List<ImportTransfer>,
        batch: ImportBatch,
        calendars: SettlementCalendars,
    ): List<ExistingTransferInfo> {
        if (transfers.isEmpty()) return emptyList()

        val accountIds =
            transfers
                .flatMap {
                    // Include any funding-card reconcile account: the funding leg lives on it (and on the
                    // conduit), so load its history even when the batch never moves money to/from it.
                    listOfNotNull(requireId(it.fromAccount), requireId(it.toAccount), it.reconcileFundingAccountId) +
                        // Every conduit of a pass-through chain: an inner one (PayPal behind Curve) is never the
                        // main transfer's account, yet its own statement's legs are what the chain reconciles with.
                        it.passThrough
                            ?.conduits
                            .orEmpty()
                            .map(::requireId)
                }.toSet()

        val rawTransfers =
            when (batch.dedupePolicy) {
                is DedupePolicy.FuzzyAllFields -> {
                    val policy = batch.dedupePolicy as DedupePolicy.FuzzyAllFields
                    // Widen the load window by the dedupe slack so an existing transfer whose timestamp
                    // sits just outside the incoming batch's own span — but still within the fuzzy date
                    // tolerance or the cross-source reconcile window — is available as a candidate. This
                    // matters for date-only exports (e.g. Curve stamps a date that parses to noon) where
                    // the counterpart card leg lands hours later the same day, or a day or two later.
                    val range =
                        policy.windows().reach(
                            transfers.minOf { requireNotNull(it.timestamp) }..transfers.maxOf { requireNotNull(it.timestamp) },
                            calendars.strictest,
                            times = 1,
                        )
                    accountIds
                        .flatMap {
                            transactionRepository.getTransactionsByAccountAndDateRange(it, range.start, range.endInclusive).first()
                        }.distinctBy { it.id }
                }

                is DedupePolicy.UniqueIdentifier -> {
                    loadAroundBatch(transfers, accountIds.toList(), batch.dedupePolicy.windows(), calendars)
                }

                is DedupePolicy.ApiMultiKey -> {
                    // The own account may be the source or the target depending on direction; the app
                    // accounts bridged for internal-transfer reconciliation hold legs the batch never touches.
                    val policy = batch.dedupePolicy as DedupePolicy.ApiMultiKey
                    loadAroundBatch(
                        transfers,
                        (accountIds + policy.internalTransferBridges.map { it.appAccountId }).toList(),
                        policy.windows(),
                        calendars,
                    )
                }

                is DedupePolicy.None -> {
                    emptyList()
                }
            }
        // A leg another source could only date approximately may truly sit far outside the date range
        // above, so load those few legs on the batch's accounts whatever their date.
        val approximateLegs =
            if (batch.dedupePolicy is DedupePolicy.FuzzyAllFields) {
                transactionRepository.getTransactionsByAccountsWithAttributeType(
                    accountIds,
                    WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME,
                )
            } else {
                emptyList()
            }

        val uniqueKeyExtractor = batch.uniqueKeyExtractor
        val apiIdExtractor = batch.apiIdExtractor
        val candidates = sourceTransferSnapshots((rawTransfers + approximateLegs).distinctBy { it.id }, transfers, batch.strategyId)
        return candidates.map { transfer ->
            ExistingTransferInfo(
                transferId = transfer.id,
                transfer = transfer,
                attributes = transfer.attributes.associate { it.attributeType.id to it.value },
                uniqueKey = uniqueKeyExtractor?.extract(transfer).orEmpty(),
                apiId = apiIdExtractor?.extract(transfer),
            )
        }
    }

    /** Recognize an unchanged source row even when another observation contributed attributes to its transaction. */
    private suspend fun sourceTransferSnapshots(
        candidates: List<Transfer>,
        incoming: List<ImportTransfer>,
        strategyId: String?,
    ): List<Transfer> {
        if (candidates.isEmpty() || incoming.isEmpty()) return candidates
        val observations = observationRepository
        val records = importRecordRepository
        val loader =
            MatchScopeLoader(
                observations,
                records,
                csvImportRepository,
                apiSessionRepository,
                accountAttributeRepository,
                attributeTypeRepository,
            )
        val families =
            incoming
                .mapNotNull {
                    when {
                        strategyId == null -> loader.familyOf(it.source)
                        it.source is Source.Csv -> MatchScopeLoader.csvFamily(strategyId)
                        it.source is Source.Api -> MatchScopeLoader.apiFamily(strategyId)
                        else -> null
                    }
                }.toSet()
        if (families.isEmpty()) return candidates
        val stored = observations.getObservationsOfTransactions(candidates.map { it.id })
        if (stored.isEmpty()) return candidates
        val reservedIds = observations.getReservedIds(stored.map { it.id })
        val reserved = stored.filter { it.id in reservedIds }.mapTo(mutableSetOf()) { it.transactionId }
        val byId = candidates.associateBy { it.id }
        val types = attributeTypeRepository.getAll().first().associateBy { it.id }
        val snapshots =
            loader.toMatch(stored, emptyMap()).mapNotNull { observation ->
                // A re-run must reclaim its reserved slot through the normal update/create path.
                if (observation.current in reserved) return@mapNotNull null
                if (observation.family !in families || observation.ref.slot != ObservationSlot.MAIN) return@mapNotNull null
                val values =
                    observation.content.values as? ObservedValues.Transfer
                        ?: return@mapNotNull null
                val transfer = byId[observation.current] ?: return@mapNotNull null
                if (transfer.amount.asset.id.id != values.assetId.id) return@mapNotNull null
                transfer.copy(
                    timestamp = values.timestamp,
                    description = values.description,
                    sourceAccountId = values.sourceAccountId,
                    targetAccountId = values.targetAccountId,
                    amount = transfer.amount.copy(amount = values.amount),
                    attributes =
                        observation.content.attributes.map { attribute ->
                            TransferAttribute(
                                0,
                                transfer.id,
                                types.getValue(attribute.typeId),
                                attribute.value,
                                attribute.groupKey,
                            )
                        },
                )
            }

        data class SnapshotKey(
            val timestamp: Instant?,
            val description: String,
            val source: AccountId,
            val target: AccountId,
            val amount: Money?,
            val attributes: Map<AttributeTypeId, String>,
        )
        val incomingKeys =
            incoming.mapNotNullTo(mutableSetOf()) { row ->
                val from = (row.fromAccount as? AccountRef.Existing)?.id ?: return@mapNotNullTo null
                val to = (row.toAccount as? AccountRef.Existing)?.id ?: return@mapNotNullTo null
                SnapshotKey(
                    row.timestamp,
                    row.description,
                    from,
                    to,
                    row.amount,
                    row.attributes.associate { it.typeId to it.value },
                )
            }
        val identical =
            snapshots
                .filter { snapshot ->
                    SnapshotKey(
                        snapshot.timestamp,
                        snapshot.description,
                        snapshot.sourceAccountId,
                        snapshot.targetAccountId,
                        snapshot.amount,
                        snapshot.attributes.associate { it.attributeType.id to it.value },
                    ) in incomingKeys
                }.associateBy { it.id }
        return candidates.map { identical[it.id] ?: it }
    }

    /**
     * The existing transfers on [accountIds] a unique-id policy can match the batch against, instead of each
     * account's whole history: everything near the batch in time, plus the few records that may match from
     * any distance — those carrying one of the batch's own ids, and those dated only approximately.
     *
     * Every other rule pairs an existing leg with a batch row inside one of the policy's [windows], or (the
     * split-conduit pass) with an existing placeholder that is itself inside the window of a batch row, so
     * widening by every window twice covers each one. A leg outside that cannot match anything; loading it changed
     * nothing but the cost, which on a large database was most of a re-import.
     *
     * The result keeps the order the full-history load gave (account by account, newest first), since the
     * deduper's indexes keep the first or last of several candidates.
     */
    private suspend fun loadAroundBatch(
        transfers: List<ImportTransfer>,
        accountIds: List<AccountId>,
        windows: List<SettlementWindow>,
        calendars: SettlementCalendars,
    ): List<Transfer> {
        val timestamps = transfers.map { it.timestamp ?: return loadFullHistory(accountIds) }
        // An approximately-dated row may have happened as late as its approximateUntil.
        val latest = transfers.maxOf { maxOf(requireNotNull(it.timestamp), it.approximateUntil ?: Instant.DISTANT_PAST) }
        val range = windows.reach(timestamps.min()..latest, calendars.strictest, times = 2)
        val from = range.start
        val until = range.endInclusive
        val ids = transfers.flatMap { it.uniqueKey?.values.orEmpty() + listOfNotNull(it.apiId) }
        val loaded =
            accountIds.flatMap { transactionRepository.getTransactionsByAccountAndDateRange(it, from, until).first() } +
                transactionRepository.getTransactionsByAccountsWithAttributeValues(accountIds, ids) +
                transactionRepository.getTransactionsByAccountsWithAttributeType(
                    accountIds,
                    WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME,
                )
        val byAccount = mutableMapOf<AccountId, MutableList<Transfer>>()
        for (transfer in loaded.distinctBy { it.id }.sortedByDescending { it.timestamp }) {
            byAccount.getOrPut(transfer.sourceAccountId) { mutableListOf() } += transfer
            if (transfer.targetAccountId != transfer.sourceAccountId) {
                byAccount.getOrPut(transfer.targetAccountId) { mutableListOf() } += transfer
            }
        }
        return accountIds.flatMap { byAccount[it].orEmpty() }.distinctBy { it.id }
    }

    private suspend fun loadFullHistory(accountIds: List<AccountId>): List<Transfer> =
        accountIds.flatMap { transactionRepository.getTransactionsByAccount(it).first() }.distinctBy { it.id }

    /** [range] widened [times] over by each of these windows: every instant a match could carry. */
    private fun List<SettlementWindow>.reach(
        range: ClosedRange<Instant>,
        calendar: BusinessDayCalendar,
        times: Int,
    ): ClosedRange<Instant> =
        fold(range) { reached, window ->
            val widened = (1..times).fold(range) { r, _ -> window.widen(r.start, r.endInclusive, calendar) }
            minOf(reached.start, widened.start)..maxOf(reached.endInclusive, widened.endInclusive)
        }
}
