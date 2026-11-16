package com.moneymanager.importengineapi

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import kotlin.time.Instant

/** What a person set on one transaction: transfer fields, as the `transfer` columns store them, and attributes. */
data class TransactionOverrides(
    val fields: Map<TransferField, String> = emptyMap(),
    val attributes: List<AttributeOverride> = emptyList(),
) {
    val isEmpty: Boolean get() = fields.isEmpty() && attributes.isEmpty()
}

/** Something a person overrode whose import value changed underneath: the override still wins. */
data class MaterializationConflict(
    val transactionId: Long,
    val subject: Subject,
    /** The person's value; null when they removed the attribute. */
    val overrideValue: String?,
    /** The merged import value before and after; null when no import reported it. */
    val importBefore: String?,
    val importAfter: String?,
) {
    sealed interface Subject {
        data class Field(
            val field: TransferField,
        ) : Subject

        data class Attribute(
            val typeId: AttributeTypeId,
            val groupKey: String,
        ) : Subject
    }
}

/**
 * The materializer's pure half: the rank-merge of a transaction's observations, the person's overrides on top, and
 * the write that brings the stored row to it. It reads nothing and writes nothing; the engine does both.
 *
 * The merge depends only on what the observations say, never on their ids or the order they were made, so every
 * import order materializes the same row. For each field it takes the best-ranked observation that is good for it:
 * a precise time over an approximate one, the record's own account then a named end over a placeholder, an itemised
 * amount over a gross one. Ties go to a canonical order of the observations' contents.
 */
object Materializer {
    /** The merge of [observations] (all of one kind), or null when there are none. */
    fun merge(
        observations: Collection<ObservationContent>,
        placeholderTypeId: AttributeTypeId? = null,
    ): MaterializedTransaction? {
        if (observations.isEmpty()) return null
        val kinds = observations.mapTo(mutableSetOf()) { it.values.kind }
        require(kinds.size == 1) { "a transaction's observations must all be of one kind, not $kinds" }
        val ranked = observations.sortedWith(RANK)
        val values =
            when (kinds.single()) {
                TransactionKind.TRANSFER -> mergeTransfer(ranked)
                TransactionKind.TRADE -> ranked.first().values
            }
        return MaterializedTransaction(values, mergeAttributes(ranked, kinds.single()).withoutNamedPlaceholders(ranked, placeholderTypeId))
    }

    /** [merged] with [overrides] on top. */
    fun applyOverrides(
        merged: MaterializedTransaction,
        overrides: TransactionOverrides,
    ): MaterializedTransaction {
        if (overrides.isEmpty) return merged
        var values = merged.values
        if (values is ObservedValues.Transfer) {
            overrides.fields.forEach { (field, value) -> values = (values as ObservedValues.Transfer).with(field, value) }
        }
        val attributes = merged.attributes.associateByTo(LinkedHashMap()) { it.typeId to it.groupKey }
        overrides.attributes.forEach { override ->
            val slot = override.typeId to override.groupKey
            when (override) {
                is AttributeOverride.Set -> attributes[slot] = ObservationAttribute(override.typeId, override.groupKey, override.value)
                is AttributeOverride.Removed -> attributes.remove(slot)
            }
        }
        return MaterializedTransaction(values, attributes.values.toSet())
    }

    /**
     * The overridden fields and attributes of [transactionId] whose merged import value went from [before] to
     * [after]. A transaction that had no merge before (new) or has none after (garbage) has nothing to conflict with.
     */
    fun conflicts(
        transactionId: Long,
        before: MaterializedTransaction?,
        after: MaterializedTransaction?,
        overrides: TransactionOverrides,
    ): List<MaterializationConflict> {
        if (before == null || after == null || overrides.isEmpty) return emptyList()
        val fieldConflicts =
            overrides.fields.mapNotNull { (field, value) ->
                val was = before.values.column(field)
                val now = after.values.column(field)
                if (was ==
                    now
                ) {
                    null
                } else {
                    MaterializationConflict(transactionId, MaterializationConflict.Subject.Field(field), value, was, now)
                }
            }
        val attributeConflicts =
            overrides.attributes.mapNotNull { override ->
                val was = before.attributeValue(override.typeId, override.groupKey)
                val now = after.attributeValue(override.typeId, override.groupKey)
                if (was == now) {
                    null
                } else {
                    MaterializationConflict(
                        transactionId = transactionId,
                        subject = MaterializationConflict.Subject.Attribute(override.typeId, override.groupKey),
                        overrideValue = (override as? AttributeOverride.Set)?.value,
                        importBefore = was,
                        importAfter = now,
                    )
                }
            }
        return fieldConflicts + attributeConflicts
    }

    /**
     * The write that brings [stored] (the row [transactionId] has, null when none) to [effective] (null when no
     * observation is left), or null when they already agree.
     */
    fun write(
        transactionId: Long,
        kind: TransactionKind,
        stored: MaterializedTransaction?,
        effective: MaterializedTransaction?,
        source: Source,
    ): MaterializedWrite? =
        when {
            effective == null -> stored?.let { MaterializedWrite.Delete(transactionId, kind) }
            stored == null -> MaterializedWrite.Insert(transactionId, effective, source)
            stored == effective -> null
            else -> MaterializedWrite.Update(transactionId, stored, effective, source)
        }

    private fun mergeTransfer(ranked: List<ObservationContent>): ObservedValues.Transfer {
        val best = ranked.first().values as ObservedValues.Transfer
        val transfers = ranked.map { it to it.values as ObservedValues.Transfer }

        fun firstWhere(good: (ObservationContent) -> Boolean): ObservedValues.Transfer =
            transfers.firstOrNull { good(it.first) }?.second ?: best

        val time = firstWhere { !it.facts.approximateTime }

        // An end is surest from the record whose own statement account it is (a bank's "Monzo -> Freetrade Limited"
        // and a broker's "Freetrade Top Ups -> ISA" are `Monzo -> ISA`), then from one that names it.
        fun endFrom(end: ObservationEnd): ObservedValues.Transfer =
            transfers.firstOrNull { it.first.facts.ownEnd == end }?.second
                ?: firstWhere { end !in it.facts.placeholderEnds }

        val source = endFrom(ObservationEnd.SOURCE)
        val target = endFrom(ObservationEnd.TARGET)
        val amount = firstWhere { !it.facts.grossAmount }
        val description = transfers.firstOrNull { it.second.description.isNotBlank() }?.second?.description ?: best.description
        // Ends taken from two observations can name one account (each one's placeholder is the other's real end);
        // a transfer can't, so fall back to the best observation's own pair.
        val (sourceAccount, targetAccount) =
            if (source.sourceAccountId != target.targetAccountId) {
                source.sourceAccountId to target.targetAccountId
            } else {
                best.sourceAccountId to best.targetAccountId
            }
        return ObservedValues.Transfer(
            timestamp = time.timestamp,
            description = description,
            sourceAccountId = sourceAccount,
            targetAccountId = targetAccount,
            assetId = amount.assetId,
            amount = amount.amount,
        )
    }

    /** The union of every observation's attributes; where two report one slot, the better-ranked one's value. */
    private fun mergeAttributes(
        ranked: List<ObservationContent>,
        kind: TransactionKind,
    ): Set<ObservationAttribute> {
        val bySlot = LinkedHashMap<Pair<AttributeTypeId, String>, ObservationAttribute>()
        ranked.forEach { observation ->
            observation.attributes.sortedWith(ATTRIBUTE_ORDER).forEach { attribute ->
                // A trade's attributes have no group: one value per type.
                val slot = attribute.typeId to if (kind == TransactionKind.TRADE) "" else attribute.groupKey
                bySlot.getOrPut(slot) { attribute.copy(groupKey = slot.second) }
            }
        }
        return bySlot.values.toSet()
    }

    /**
     * Without the `unidentified-counterparty` marker ([placeholderTypeId]) of an end another observation names: the
     * merged transfer takes that end from it, so it is no placeholder any more.
     */
    private fun Set<ObservationAttribute>.withoutNamedPlaceholders(
        ranked: List<ObservationContent>,
        placeholderTypeId: AttributeTypeId?,
    ): Set<ObservationAttribute> {
        if (placeholderTypeId == null) return this
        val named = ObservationEnd.entries.filterTo(mutableSetOf()) { end -> ranked.any { end !in it.facts.placeholderEnds } }
        return filterNotTo(LinkedHashSet()) { it.typeId == placeholderTypeId && it.value.placeholderEnd() in named }
    }

    private fun ObservedValues.Transfer.with(
        field: TransferField,
        value: String,
    ): ObservedValues.Transfer =
        when (field) {
            TransferField.TIMESTAMP -> copy(timestamp = Instant.fromEpochMilliseconds(value.toLong()))
            TransferField.DESCRIPTION -> copy(description = value)
            TransferField.SOURCE_ACCOUNT -> copy(sourceAccountId = AccountId(value.toLong()))
            TransferField.TARGET_ACCOUNT -> copy(targetAccountId = AccountId(value.toLong()))
            TransferField.ASSET -> copy(assetId = ObservedAssetId(value.toLong()))
            TransferField.AMOUNT -> copy(amount = BigInteger(value))
        }

    /** [field] as its `transfer` column stores it; null on a trade, which has no field overrides. */
    fun ObservedValues.column(field: TransferField): String? =
        (this as? ObservedValues.Transfer)?.let {
            when (field) {
                TransferField.TIMESTAMP -> it.timestamp.toEpochMilliseconds().toString()
                TransferField.DESCRIPTION -> it.description
                TransferField.SOURCE_ACCOUNT -> it.sourceAccountId.id.toString()
                TransferField.TARGET_ACCOUNT -> it.targetAccountId.id.toString()
                TransferField.ASSET -> it.assetId.id.toString()
                TransferField.AMOUNT -> it.amount.toString()
            }
        }

    private fun MaterializedTransaction.attributeValue(
        typeId: AttributeTypeId,
        groupKey: String,
    ): String? = attributes.firstOrNull { it.typeId == typeId && it.groupKey == groupKey }?.value

    private val ATTRIBUTE_ORDER =
        compareBy<ObservationAttribute>({ it.typeId.id }, { it.groupKey }, { it.value })

    /**
     * Best first: more facts that make an observation precise (named ends, an exact time, an itemised amount), then
     * a canonical order of what it says, so the ranking never depends on which observation came first.
     */
    private val RANK: Comparator<ObservationContent> =
        compareByDescending<ObservationContent> { precision(it) }
            .thenComparator { a, b -> canonical(a).compareTo(canonical(b)) }

    private fun precision(content: ObservationContent): Int =
        (if (content.facts.approximateTime) 0 else 1) +
            (if (content.facts.grossAmount) 0 else 1) +
            (ObservationEnd.entries.size - content.facts.placeholderEnds.size)

    private fun canonical(content: ObservationContent): String {
        val values =
            when (val v = content.values) {
                is ObservedValues.Transfer -> {
                    listOf(
                        v.timestamp.toEpochMilliseconds(),
                        v.sourceAccountId.id,
                        v.targetAccountId.id,
                        v.assetId.id,
                        v.amount,
                        v.description,
                    )
                }

                is ObservedValues.Trade -> {
                    listOf(
                        v.timestamp.toEpochMilliseconds(),
                        v.fromAccountId.id,
                        v.fromAssetId.id,
                        v.fromAmount,
                        v.toAccountId.id,
                        v.toAssetId.id,
                        v.toAmount,
                        v.description,
                    )
                }
            }
        val attributes = content.attributes.sortedWith(ATTRIBUTE_ORDER).map { "${it.typeId.id}/${it.groupKey}=${it.value}" }
        return (values + attributes).joinToString("\u0000")
    }
}
