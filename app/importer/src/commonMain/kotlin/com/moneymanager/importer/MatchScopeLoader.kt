package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.calendar.WeekendOnlyCalendar
import com.moneymanager.domain.model.observation.ImportGroup
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.rules.meets
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.MatchFamily
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
import com.moneymanager.observationmatcher.TradeRules
import com.moneymanager.observationmatcher.TradeStages
import kotlinx.coroutines.flow.first
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
    /** Every stored observation naming one of [accountIds], with [families] supplying each family's identity types. */
    suspend fun load(
        accountIds: Collection<AccountId>,
        families: Map<String, MatchFamily>,
    ): List<MatchObservation> = toMatch(observations.getObservationsOnAccounts(accountIds), families)

    /** [stored] as the matcher sees it. */
    suspend fun toMatch(
        stored: List<Observation>,
        families: Map<String, MatchFamily>,
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
            records.getRecords(owners.filterIsInstance<ObservationOwner.Record>().map { it.recordId }).associateBy { it.id }
        val groupsById =
            records.getGroupsById(owners.filterIsInstance<ObservationOwner.Group>().map { it.groupId }).associateBy { it.id }
        val origins = mutableMapOf<ObservationOwner, Origin>()
        return stored.map { observation ->
            val origin = origins.getOrPut(observation.owner) { originOf(observation.owner, recordsById, groupsById) }
            val family = families[origin.family]
            val attributes = observation.content.attributes
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
                                .mapNotNull { type -> attributes.firstOrNull { it.typeId == type }?.let { type.toString() to it.value } }
                                .toMap(),
                        apiId = family?.apiIdAttributeType?.let { type -> attributes.firstOrNull { it.typeId == type }?.value },
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

    private val strategyOfCredential = mutableMapOf<ApiCredentialId, String>()

    private suspend fun originOf(
        owner: ObservationOwner,
        recordsById: Map<ImportRecordId, ImportRecord>,
        groupsById: Map<ImportGroupId, ImportGroup>,
    ): Origin =
        when (owner) {
            is ObservationOwner.Record -> {
                val record = checkNotNull(recordsById[owner.recordId]) { "observation owner ${owner.recordId} has no record" }
                when (val key = record.key) {
                    is ImportRecordKey.CsvRow -> Origin(csvFamily(key.csvImportId), csvContainer(key.csvImportId), record.contentHash)
                    is ImportRecordKey.ApiItem -> Origin(apiFamily(key.sessionId), apiContainer(key.sessionId), record.contentHash)
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

    private suspend fun apiFamily(id: ApiSessionId): String {
        val credential = apiSessions.getSessionById(id)?.credentialId ?: return "$UNKNOWN_FAMILY:${apiContainer(id)}"
        if (credential !in strategyOfCredential) {
            apiSessions.getCredentialsFlow().first().forEach { c ->
                c.strategyId?.let {
                    strategyOfCredential[c.id] =
                        apiFamily(it.toString())
                }
            }
        }
        return strategyOfCredential[credential] ?: "$UNKNOWN_FAMILY:${apiContainer(id)}"
    }

    companion object {
        /** The family of a person's own transactions: no strategy, so never deduplicated. */
        const val MANUAL_FAMILY = "manual"
        private const val UNKNOWN_FAMILY = "unknown"
        private const val CONDUIT_SLOT_PREFIX = "conduit/"

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
                TradeStages.forPolicies(tradePolicies, conversionWindows),
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
            val policies = families.mapValues { CrossSourcePolicy.of(it.value.dedupePolicy) }
            return ShapeMatcher { a, b ->
                val x = a.content.values as ObservedValues.Transfer
                val y = b.content.values as ObservedValues.Transfer
                val calendar = calendarFor(listOf(x.sourceAccountId, x.targetAccountId, y.sourceAccountId, y.targetAccountId).distinct())
                listOf(a, b).any { o ->
                    val policy = policies[o.family] ?: return@any false
                    listOfNotNull(policy.placeholderWindow, policy.reconcileWindow).any { window ->
                        window.meets(x.timestamp, a.crossSource.approximateUntil, y.timestamp, b.crossSource.approximateUntil, calendar)
                    }
                }
            }
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
