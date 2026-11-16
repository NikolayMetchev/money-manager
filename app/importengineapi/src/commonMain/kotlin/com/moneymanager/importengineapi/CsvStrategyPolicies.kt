package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.rules.SettlementWindow
import kotlin.time.Duration.Companion.seconds

/**
 * The fuzzy dedupe policy for this strategy's rows, which CSV and QIF imports share. Cross-source
 * reconciliation is opt-in per strategy (`CsvStrategyConfig.crossSourceReconcileWindow`):
 * a row recording a movement another export already imported (same accounts + amount within that window,
 * or, when one side's counterparty is only a placeholder marked by [unidentifiedCounterpartyTypeId], the
 * same owned account + direction + amount within the placeholder window) is kept but excluded and
 * linked instead of double-counting. Mirrors the API importer.
 */
fun CsvImportStrategy.fuzzyDedupePolicy(unidentifiedCounterpartyTypeId: AttributeTypeId?): DedupePolicy.FuzzyAllFields {
    val reconcileWindow = config.crossSourceReconcileWindow
    return DedupePolicy.FuzzyAllFields(
        dateTolerance = config.duplicateDateTolerance?.duration ?: DedupePolicy.FuzzyAllFields().dateTolerance,
        reconcileWindow = reconcileWindow,
        reconciledExclusionAttributeTypeId = reconcileWindow?.let { AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID) },
        reconciledRelationshipTypeId = reconcileWindow?.let { RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID) },
        unidentifiedCounterpartyAttributeTypeId = reconcileWindow?.let { unidentifiedCounterpartyTypeId },
        unidentifiedCounterpartyWindow = reconcileWindow?.let { placeholderWindow() },
    )
}

/**
 * The dedupe policy for this strategy's rows: by their unique-identifier columns when it declares any, else fuzzy
 * ([fuzzyDedupePolicy]).
 */
fun CsvImportStrategy.dedupePolicy(unidentifiedCounterpartyTypeId: AttributeTypeId?): DedupePolicy {
    if (config.uniqueIdentifierAttributeTypes().isEmpty()) return fuzzyDedupePolicy(unidentifiedCounterpartyTypeId)
    // Cross-source reconciliation for unique-id strategies whose sources issue a different id per side of the same
    // movement (e.g. Monzo's own- and joint-account exports each carry their own Transaction ID for one transfer).
    val reconcileWindow = config.crossSourceReconcileWindow
    return DedupePolicy.UniqueIdentifier(
        reconcileWindow = reconcileWindow,
        reconciledExclusionAttributeTypeId = reconcileWindow?.let { AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID) },
        reconciledRelationshipTypeId = reconcileWindow?.let { RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID) },
        unidentifiedCounterpartyAttributeTypeId = reconcileWindow?.let { unidentifiedCounterpartyTypeId },
        // A placeholder match has only account, direction and amount to go on, so it has its own, wider window rather
        // than this strategy's exact-pair one.
        unidentifiedCounterpartyWindow = reconcileWindow?.let { placeholderWindow() },
    )
}

/**
 * The trade dedupe policy for this strategy's assembled trades. Trades get their own, much tighter window than the
 * transfer reconcile: aggregation matches a group against the WHOLE in-window candidate set, so a wide window would
 * drag a later order's fills in and stop the sums matching at all.
 */
fun CsvImportStrategy.tradeDedupePolicy(): TradeDedupePolicy =
    config.legGroups
        .filter { it.assembly is LegAssembly.Trade }
        .mapNotNull { it.reconcileWindowSeconds }
        .maxOrNull()
        ?.let { TradeDedupePolicy.Fuzzy(window = it.seconds) }
        ?: TradeDedupePolicy.ExactTupleOnly

/**
 * What the observation matcher needs to know about this strategy's records ([MatchFamily]), with attribute types
 * resolved through [attributeTypeIdByName] (a type no record carries yet has no id, and so identifies nothing).
 */
fun CsvImportStrategy.matchFamily(attributeTypeIdByName: Map<String, AttributeTypeId>): MatchFamily =
    MatchFamily(
        dedupePolicy = dedupePolicy(attributeTypeIdByName[WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME]),
        tradePolicy = tradeDedupePolicy(),
        uniqueIdAttributeTypes = config.uniqueIdentifierAttributeTypes().mapNotNullTo(mutableSetOf()) { attributeTypeIdByName[it] },
        conversionWindows =
            config.legGroups
                .filter { it.assembly is LegAssembly.ThroughAccount }
                .mapNotNull { rule -> rule.reconcileWindowSeconds?.let { rule.name to it.seconds } }
                .toMap(),
    )

/** How far a placeholder leg may sit from the record naming its far end: three days unless the strategy says. */
private fun CsvImportStrategy.placeholderWindow(): SettlementWindow =
    config.placeholderReconcileWindow ?: SettlementWindow.Elapsed(DedupePolicy.FuzzyAllFields().dateTolerance.inWholeSeconds)
