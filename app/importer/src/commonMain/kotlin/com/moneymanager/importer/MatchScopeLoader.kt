package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.calendar.WeekendOnlyCalendar
import com.moneymanager.domain.model.observation.ImportGroup
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.meets
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.MatchFamily
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.TradeDedupePolicy
import com.moneymanager.observationmatcher.CrossSourceFacts
import com.moneymanager.observationmatcher.CrossSourcePolicy
import com.moneymanager.observationmatcher.CrossSourceRules
import com.moneymanager.observationmatcher.LegGroupRuleRef
import com.moneymanager.observationmatcher.MatchObservation
import com.moneymanager.observationmatcher.ObservationMatcher
import com.moneymanager.observationmatcher.ObservationRef
import com.moneymanager.observationmatcher.RecordIdentity
import com.moneymanager.observationmatcher.SameFamilyRules
import com.moneymanager.observationmatcher.ShapeMatcher
import com.moneymanager.observationmatcher.ShapeReason
import com.moneymanager.observationmatcher.ShapeWindow
import com.moneymanager.observationmatcher.TradeRules
import com.moneymanager.observationmatcher.TradeStages
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Loads stored observations as the [MatchObservation]s the matcher reads: each with its family (the strategy whose
 * records it observes), its container (the staged file, API session or import group its owner belongs to), an
 * order-free record key, its record's identity and the facts the cross-source rules read.
 *
 * None of these is stored with the observation; each follows from its owner: a record's staged file or API session
 * and content hash, a group's key, and the attributes the observation reports.
 */
class MatchScopeLoader(
    private val observations: ObservationReadRepository,
    private val records: ImportRecordReadRepository,
    private val csvImports: CsvImportReadRepository,
    private val apiSessions: ApiSessionReadRepository,
    private val accountAttributes: AccountAttributeReadRepository,
    private val attributeTypes: AttributeTypeReadRepository,
) {
    /** The strategy family of an incoming record, using the same provenance as stored observations. */
    suspend fun familyOf(source: Source): String? =
        when (source) {
            is Source.Csv -> csvFamily(source.importId)
            is Source.Api -> apiFamily(source.sessionId)
            else -> null
        }

    /** Every stored observation naming one of [accountIds], with [families] supplying each family's identity types. */
    suspend fun load(
        accountIds: Collection<AccountId>,
        families: Map<String, MatchFamily>,
        changes: List<ObservationChange> = emptyList(),
        newRecords: List<ImportRecord> = emptyList(),
        recordHashes: Map<ImportRecordId, String> = emptyMap(),
        csvFamilies: Map<CsvImportId, String> = emptyMap(),
    ): List<MatchObservation> {
        val stored = observations.getObservationsOnAccounts(accountIds)
        if (changes.isEmpty() && recordHashes.isEmpty() && csvFamilies.isEmpty()) return toMatch(stored, families)
        val replaced = changes.mapNotNullTo(mutableSetOf()) { it.before?.id }
        val proposed =
            changes.mapIndexedNotNull { index, change ->
                change.after?.let {
                    Observation(it.storedId ?: ObservationId(-index.toLong() - 1), change.owner, change.slot, it.transactionId, it.content)
                }
            }
        return toMatch(stored.filterNot { it.id in replaced } + proposed, families, newRecords, recordHashes, csvFamilies)
    }

    /** [stored] as the matcher sees it. */
    suspend fun toMatch(
        stored: List<Observation>,
        families: Map<String, MatchFamily>,
        newRecords: List<ImportRecord> = emptyList(),
        recordHashes: Map<ImportRecordId, String> = emptyMap(),
        csvFamilies: Map<CsvImportId, String> = emptyMap(),
    ): List<MatchObservation> {
        val shadow =
            accountAttributes
                .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID))
                .first()
                .mapTo(mutableSetOf()) { it.accountId }
        val approximateType = attributeTypes.getByName(WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME).first()?.id
        val chainOwners = stored.filter { it.slot.startsWith(CONDUIT_SLOT_PREFIX) }.mapTo(mutableSetOf()) { it.owner }
        val owners = stored.map { it.owner }
        val recordsById =
            (records.getRecords(owners.filterIsInstance<ObservationOwner.Record>().map { it.recordId }) + newRecords).associateBy { it.id }
        val groupsById =
            records.getGroupsById(owners.filterIsInstance<ObservationOwner.Group>().map { it.groupId }).associateBy { it.id }
        val feeOwners = stored.filter { it.slot == ObservationSlot.FEE }.mapTo(mutableSetOf()) { it.owner }
        val mainAttributesByFeeOwner =
            stored
                .asSequence()
                .filter { it.slot == ObservationSlot.MAIN && it.owner in feeOwners }
                .associate { it.owner to it.content.attributes }
        val origins = mutableMapOf<ObservationOwner, Origin>()
        return stored.map { observation ->
            // A copy (#1475) is a person's booking that its record keeps up to date: matched as a person's own transaction, never
            // by the source's rules, which describe its shadow rows.
            val origin =
                if (observation.slot.startsWith(ObservationSlot.COPY_PREFIX)) {
                    Origin(MANUAL_FAMILY, "$COPY_CONTAINER:${observation.owner}", observation.slot)
                } else {
                    origins.getOrPut(observation.owner) { originOf(observation.owner, recordsById, groupsById, recordHashes, csvFamilies) }
                }
            val family = families[origin.family]
            val attributes = observation.content.attributes
            // A derived fee carries no provider ID of its own. Match it using the identity of the row that charged it.
            val identityAttributes =
                if (observation.slot == ObservationSlot.FEE) mainAttributesByFeeOwner[observation.owner] ?: attributes else attributes
            MatchObservation(
                ref = ObservationRef(observation.owner, observation.slot),
                storedId = observation.id,
                current = observation.transactionId,
                family = origin.family,
                container = origin.container,
                recordKey = origin.recordKey,
                content = observation.content,
                identity =
                    RecordIdentity(
                        uniqueKey =
                            family
                                ?.uniqueIdAttributeTypes
                                .orEmpty()
                                .mapNotNull { type ->
                                    identityAttributes.firstOrNull { it.typeId == type }?.let { type.toString() to it.value }
                                }.toMap(),
                        apiId = family?.apiIdAttributeType?.let { type -> identityAttributes.firstOrNull { it.typeId == type }?.value },
                    ),
                crossSource =
                    CrossSourceFacts(
                        approximateUntil =
                            attributes
                                .firstOrNull { it.typeId == approximateType }
                                ?.value
                                ?.toLongOrNull()
                                ?.let { Instant.fromEpochMilliseconds(it) },
                        shadow =
                            observation.content.values
                                .accounts()
                                .any { it in shadow },
                        passThrough = observation.owner in chainOwners,
                    ),
            )
        }
    }

    private class Origin(
        val family: String,
        val container: String,
        val recordKey: String,
    )

    private var strategyOfCredential: Map<ApiCredentialId, String>? = null
    private val familyOfApiSession = mutableMapOf<ApiSessionId, String>()

    private suspend fun originOf(
        owner: ObservationOwner,
        recordsById: Map<ImportRecordId, ImportRecord>,
        groupsById: Map<ImportGroupId, ImportGroup>,
        recordHashes: Map<ImportRecordId, String>,
        csvFamilies: Map<CsvImportId, String>,
    ): Origin =
        when (owner) {
            is ObservationOwner.Record -> {
                val record = checkNotNull(recordsById[owner.recordId]) { "observation owner ${owner.recordId} has no record" }
                val hash = recordHashes[record.id] ?: record.contentHash
                when (val key = record.key) {
                    is ImportRecordKey.CsvRow -> {
                        Origin(
                            csvFamilies[key.csvImportId] ?: csvFamily(key.csvImportId),
                            csvContainer(key.csvImportId),
                            hash,
                        )
                    }

                    is ImportRecordKey.ApiItem -> {
                        Origin(apiFamily(key.sessionId), apiContainer(key.sessionId), hash)
                    }
                }
            }

            is ObservationOwner.Group -> {
                val group = checkNotNull(groupsById[owner.groupId]) { "observation owner ${owner.groupId} has no group" }
                Origin(csvFamily(group.key.strategyId), groupContainer(owner.groupId), "${group.key.groupName}|${group.key.keyValue}")
            }

            is ObservationOwner.Manual -> {
                Origin(MANUAL_FAMILY, "$MANUAL_FAMILY:${owner.entitySourceId}", owner.entitySourceId.toString())
            }
        }

    private val familyOfCsvImport = mutableMapOf<CsvImportId, String>()

    private suspend fun csvFamily(id: CsvImportId): String = familyOfCsvImport.getOrPut(id) { csvFamilyUncached(id) }

    private suspend fun csvFamilyUncached(id: CsvImportId): String =
        csvImports
            .getImport(id)
            .first()
            ?.lastAppliedStrategyId
            ?.let { csvFamily(it.toString()) } ?: "$UNKNOWN_FAMILY:${csvContainer(id)}"

    private suspend fun apiFamily(id: ApiSessionId): String = familyOfApiSession.getOrPut(id) { apiFamilyUncached(id) }

    private suspend fun apiFamilyUncached(id: ApiSessionId): String {
        val credential = apiSessions.getSessionById(id)?.credentialId ?: return "$UNKNOWN_FAMILY:${apiContainer(id)}"
        val strategies =
            strategyOfCredential ?: apiSessions
                .getAllCredentials()
                .mapNotNull { c -> c.strategyId?.let { c.id to apiFamily(it.toString()) } }
                .toMap()
                .also { strategyOfCredential = it }
        return strategies[credential] ?: "$UNKNOWN_FAMILY:${apiContainer(id)}"
    }

    companion object {
        /** The family of a person's own transactions: no strategy, so never deduplicated. */
        const val MANUAL_FAMILY = "manual"
        private const val UNKNOWN_FAMILY = "unknown"
        private const val CONDUIT_SLOT_PREFIX = "conduit/"
        private const val COPY_CONTAINER = "copy"

        /**
         * A CSV strategy's family, by its id. Built-in CSV and API strategies share ids (Crypto.com's crypto export and
         * its Exchange API are both 9), so a family names the kind of strategy as well.
         */
        fun csvFamily(strategyId: String): String = "csv-strategy:$strategyId"

        /** An API strategy's family, by its id (see [csvFamily]). */
        fun apiFamily(strategyId: String): String = "api-strategy:$strategyId"

        fun csvContainer(id: CsvImportId): String = "csv:$id"

        fun apiContainer(id: ApiSessionId): String = "api:$id"

        fun groupContainer(id: ImportGroupId): String = "group:$id"

        /**
         * The matcher every family's records are matched with: same-family dedupe, then the cross-source rules, then the
         * trade rules, and the trade stages (aggregation and conversion groups) after them.
         */
        fun matcherFor(
            families: Map<String, MatchFamily>,
            containerAccounts: Map<String, Set<AccountId>> = emptyMap(),
            calendarFor: (List<AccountId>) -> BusinessDayCalendar = { WeekendOnlyCalendar },
            stageReasons: Set<ShapeReason>? = null,
        ): ObservationMatcher {
            val policies = families.mapValues { it.value.dedupePolicy }
            val tradePolicies =
                families
                    .mapNotNull { (family, f) -> (f.tradePolicy as? TradeDedupePolicy.Fuzzy)?.let { family to it } }
                    .toMap()
            val conversionWindows =
                families
                    .flatMap { (family, f) -> f.conversionWindows.map { (rule, window) -> LegGroupRuleRef(family, rule) to window } }
                    .toMap()
            return ObservationMatcher(
                SameFamilyRules.forPolicies(policies) +
                    // A hand-made transfer is no source of its own: it is never the incoming record, but another source's record of the
                    // same movement finds it, as the deduper found every transfer on its accounts.
                    CrossSourceRules.forPolicies(mapOf(MANUAL_FAMILY to DedupePolicy.None) + policies, containerAccounts, calendarFor) +
                    TradeRules.forPolicies(tradePolicies),
                TradeStages.forPolicies(tradePolicies, conversionWindows).filter { stageReasons == null || it.reason in stageReasons },
            )
        }

        /**
         * The shape matcher over [families]: two records of one movement are close enough when either family's
         * placeholder or reconcile window has them meet, on the business days of every account they touch.
         */
        fun shapeMatcherFor(
            families: Map<String, MatchFamily>,
            calendarFor: (List<AccountId>) -> BusinessDayCalendar = { WeekendOnlyCalendar },
        ): ShapeMatcher {
            val windowsByFamily =
                families.mapValues { (_, family) ->
                    val policy = CrossSourcePolicy.of(family.dedupePolicy)
                    listOfNotNull(policy.placeholderWindow, policy.reconcileWindow).distinct()
                }
            val businessDayFamilies = windowsByFamily.filterValues { windows -> windows.any { it is SettlementWindow.BusinessDays } }.keys
            val elapsedByFamily =
                windowsByFamily.mapValues { (_, windows) ->
                    windows.filterIsInstance<SettlementWindow.Elapsed>().maxOfOrNull { it.duration } ?: Duration.ZERO
                }
            val window =
                object : ShapeWindow {
                    override fun candidateBounds(
                        observation: MatchObservation,
                        otherFamily: String,
                    ): ClosedRange<Instant>? {
                        if (observation.family in businessDayFamilies || otherFamily in businessDayFamilies) return null
                        val reach =
                            maxOf(
                                Duration.ZERO,
                                elapsedByFamily[observation.family] ?: Duration.ZERO,
                                elapsedByFamily[otherFamily] ?: Duration.ZERO,
                            )
                        val start = (observation.content.values as ObservedValues.Transfer).timestamp
                        val end = maxOf(start, observation.crossSource.approximateUntil ?: start)
                        return (start - reach)..(end + reach)
                    }

                    override fun holds(
                        a: MatchObservation,
                        b: MatchObservation,
                    ): Boolean {
                        val first = windowsByFamily[a.family].orEmpty()
                        val second = windowsByFamily[b.family].orEmpty()
                        if (first.isEmpty() && second.isEmpty()) return false
                        val x = a.content.values as ObservedValues.Transfer
                        val y = b.content.values as ObservedValues.Transfer
                        var businessCalendar: BusinessDayCalendar? = null

                        fun meets(window: SettlementWindow): Boolean {
                            val calendar =
                                when (window) {
                                    is SettlementWindow.Elapsed -> {
                                        WeekendOnlyCalendar
                                    }

                                    is SettlementWindow.BusinessDays -> {
                                        businessCalendar ?: calendarFor(
                                            listOf(
                                                x.sourceAccountId,
                                                x.targetAccountId,
                                                y.sourceAccountId,
                                                y.targetAccountId,
                                            ).distinct(),
                                        ).also { businessCalendar = it }
                                    }
                                }
                            return window.meets(
                                x.timestamp,
                                a.crossSource.approximateUntil,
                                y.timestamp,
                                b.crossSource.approximateUntil,
                                calendar,
                            )
                        }
                        return first.any { meets(it) } || (first !== second && second.any { meets(it) })
                    }
                }
            return ShapeMatcher(window)
        }

        /**
         * The accounts each container's records book as their own (a statement's own account), the cross-source rules'
         * `containerAccounts`: a record whose counterparty is one of them moved money between two of that source's
         * accounts.
         */
        fun containerAccounts(observations: Collection<MatchObservation>): Map<String, Set<AccountId>> =
            observations
                .mapNotNull { o ->
                    val values = o.content.values as? ObservedValues.Transfer ?: return@mapNotNull null
                    when (o.content.facts.ownEnd) {
                        ObservationEnd.SOURCE -> o.container to values.sourceAccountId
                        ObservationEnd.TARGET -> o.container to values.targetAccountId
                        null -> null
                    }
                }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
    }
}

private fun ObservedValues.accounts(): List<AccountId> =
    when (this) {
        is ObservedValues.Transfer -> listOf(sourceAccountId, targetAccountId)
        is ObservedValues.Trade -> listOf(fromAccountId, toAccountId)
    }
