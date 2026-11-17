package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * What a sync of some sources does to the transactions they make: those it creates, those it changes (field by field),
 * those it removes, where its new values disagree with what a person set, and the overrides it releases because no record
 * makes their transaction any more. A re-import reports it after it ran; a preview reports it before anything is written.
 */
data class ImportPlan(
    val created: List<PlannedTransaction> = emptyList(),
    val updated: List<PlannedUpdate> = emptyList(),
    val removed: List<PlannedTransaction> = emptyList(),
    /** Overridden values whose import value changed underneath: the person's value still wins. */
    val conflicts: List<MaterializationConflict> = emptyList(),
    /** Transactions a person overrode that no record makes any more: their overrides no longer apply. */
    val releasedOverrides: Set<TransactionId> = emptySet(),
) {
    val isEmpty: Boolean
        get() = created.isEmpty() && updated.isEmpty() && removed.isEmpty() && conflicts.isEmpty() && releasedOverrides.isEmpty()

    operator fun plus(other: ImportPlan): ImportPlan =
        ImportPlan(
            created = created + other.created,
            updated = updated + other.updated,
            removed = removed + other.removed,
            conflicts = conflicts + other.conflicts,
            releasedOverrides = releasedOverrides + other.releasedOverrides,
        )

    /** "3 created · 2 updated · 1 removed · 1 hand edit kept against a new value", leaving out what is zero. */
    fun summary(separator: String = " · "): String =
        listOfNotNull(
            created.size.takeIf { it > 0 }?.let { "$it created" },
            updated.size.takeIf { it > 0 }?.let { "$it updated" },
            removed.size.takeIf { it > 0 }?.let { "$it removed" },
            conflicts.size.takeIf { it > 0 }?.let { "$it hand edit${if (it == 1) "" else "s"} kept against a new value" },
            releasedOverrides.size.takeIf { it > 0 }?.let { "$it hand-edited transaction${if (it == 1) "" else "s"} no longer imported" },
        ).joinToString(separator).ifEmpty { "no changes" }
}

/** One transaction as a sync sees it: a transfer has one leg, a trade two (what leaves, then what arrives). */
data class PlannedTransaction(
    val id: TransactionId,
    val timestamp: Instant,
    val description: String,
    val legs: List<PlannedLeg>,
)

data class PlannedLeg(
    val from: AccountId,
    val to: AccountId,
    val amount: Money,
)

/** A transaction a sync keeps but changes; [fields] are the ones that differ. */
data class PlannedUpdate(
    val before: PlannedTransaction,
    val after: PlannedTransaction,
) {
    val fields: List<PlannedField> = PlannedField.entries.filter { it.of(before) != it.of(after) }
}

/** The fields a sync can change on a transaction, as a person reads them. */
enum class PlannedField(
    val label: String,
    internal val of: (PlannedTransaction) -> Any,
) {
    TIME("time", { it.timestamp }),
    DESCRIPTION("description", { it.description }),
    ACCOUNTS("accounts", { t -> t.legs.map { it.from to it.to } }),
    AMOUNT("amount", { t -> t.legs.map { it.amount } }),
}

fun Transfer.planned(): PlannedTransaction =
    PlannedTransaction(id, timestamp, description, listOf(PlannedLeg(sourceAccountId, targetAccountId, amount)))

fun Trade.planned(): PlannedTransaction =
    PlannedTransaction(
        id,
        timestamp,
        description,
        listOf(PlannedLeg(fromAccountId, toAccountId, from), PlannedLeg(toAccountId, fromAccountId, to)),
    )

/**
 * The transactions some sources made, read so a sync's before and after can be compared ([planFrom]), each with the
 * record that made it ([records]: a CSV row, an API node), so a transaction its re-run re-made under a new id is still
 * the same one.
 */
class TransactionSnapshot(
    val transactions: Map<TransactionId, PlannedTransaction>,
    val records: Map<TransactionId, Source> = emptyMap(),
) {
    companion object {
        suspend fun read(
            transfers: Map<TransferId, Source>,
            trades: Map<TradeId, Source>,
            transactionRepository: TransactionReadRepository,
            tradeRepository: TradeReadRepository?,
        ): TransactionSnapshot {
            val transferRows = if (transfers.isEmpty()) emptyMap() else transactionRepository.getTransactionsByIds(transfers.keys)
            val tradeRows = tradeRepository?.let { repo -> trades.keys.mapNotNull { repo.getTradeById(it).first() } }.orEmpty()
            return TransactionSnapshot(
                transferRows.values.associate { it.id to it.planned() } + tradeRows.associate { it.id to it.planned() },
                buildMap<TransactionId, Source> {
                    putAll(transfers)
                    putAll(trades)
                },
            )
        }
    }
}

/**
 * What changed from [before] to [after]. A transaction is the same one when it kept its id or, re-made under a new id,
 * when the same record made it (its CSV row or API node, whichever account's statement it is): those are updates, listed
 * only when a field differs. What is left only in [after] was created, and only in [before] removed.
 */
fun planFrom(
    before: TransactionSnapshot,
    after: TransactionSnapshot,
    conflicts: List<MaterializationConflict> = emptyList(),
    releasedOverrides: Set<TransactionId> = emptySet(),
): ImportPlan {
    val order = compareBy<PlannedTransaction>({ it.timestamp }, { it.id.id })
    val kept = before.transactions.keys.filter { it in after.transactions }
    val pairs = kept.map { before.transactions.getValue(it) to after.transactions.getValue(it) }.toMutableList()
    val gone = before.transactions.filterKeys { it !in after.transactions }.values
    val new = after.transactions.filterKeys { it !in before.transactions }.values
    // A record that makes several transactions (a row and its fee) makes them in the same order every time.
    val newByRecord = new.groupBy { after.records[it.id]?.record() }.mapValues { (_, it) -> it.sortedBy { t -> t.id.id } }
    val removed = mutableListOf<PlannedTransaction>()
    val created = new.toMutableSet()
    for ((record, olds) in gone.groupBy { before.records[it.id]?.record() }) {
        val news = record?.let { newByRecord[it] }.orEmpty()
        olds.sortedBy { it.id.id }.forEachIndexed { i, old ->
            val remade = news.getOrNull(i)
            if (remade == null) {
                removed += old
            } else {
                pairs += old to remade
                created -= remade
            }
        }
    }
    return ImportPlan(
        created = created.sortedWith(order),
        updated =
            pairs
                .map { (old, now) -> PlannedUpdate(old, now) }
                .filter { it.fields.isNotEmpty() }
                .sortedWith(compareBy(order) { it.after }),
        removed = removed.sortedWith(order),
        conflicts = conflicts,
        releasedOverrides = releasedOverrides,
    )
}

/** The record itself, without which account's statement it was read as (a mapping change can move that). */
private fun Source.record(): Source =
    when (this) {
        is Source.Csv -> copy(statementAccountId = null)
        is Source.Api -> copy(statementAccountId = null)
        else -> this
    }

/** One titled list of an [ImportPlan], as text: the dialogs, `mm import` and the TUI show the same lines. */
data class ImportPlanSection(
    val title: String,
    val lines: List<String>,
    /** A person should look at it: the import now disagrees with what they set. */
    val needsAttention: Boolean = false,
)

/**
 * The plan's non-empty sections: created, updated (each changed field, old → new), removed, the hand edits a new import
 * value met, and the overrides released. [accountName] and [amount] say how a front end names an account and a sum.
 */
fun ImportPlan.sections(
    accountName: (AccountId) -> String,
    amount: (Money) -> String = { "${it.toDisplayValue()} ${it.asset.code}" },
): List<ImportPlanSection> {
    fun PlannedTransaction.legs() = legs.joinToString("; ") { "${accountName(it.from)} → ${accountName(it.to)} ${amount(it.amount)}" }

    fun PlannedTransaction.line() = "#${id.id} $timestamp · $description · ${legs()}"

    fun PlannedUpdate.ids() = if (before.id == after.id) "#${after.id.id}" else "#${before.id.id} → #${after.id.id}"

    fun PlannedField.change(update: PlannedUpdate): String =
        when (this) {
            PlannedField.TIME -> "time ${update.before.timestamp} → ${update.after.timestamp}"
            PlannedField.DESCRIPTION -> "description \"${update.before.description}\" → \"${update.after.description}\""
            PlannedField.ACCOUNTS, PlannedField.AMOUNT -> "$label ${update.before.legs()} → ${update.after.legs()}"
        }

    fun MaterializationConflict.line(): String {
        val subject =
            when (val s = subject) {
                is MaterializationConflict.Subject.Field -> {
                    s.field.name
                        .lowercase()
                        .replace('_', ' ')
                }

                is MaterializationConflict.Subject.Attribute -> {
                    "attribute ${s.typeId.id}${if (s.groupKey.isEmpty()) "" else " (${s.groupKey})"}"
                }
            }
        return "#$transactionId $subject: kept \"${overrideValue ?: "removed"}\"; the import now says " +
            "\"${importAfter ?: "nothing"}\" (was \"${importBefore ?: "nothing"}\")"
    }

    return listOf(
        ImportPlanSection("Created (${created.size})", created.map { it.line() }),
        ImportPlanSection(
            "Updated (${updated.size})",
            updated.map { update ->
                "${update.ids()} ${update.after.description}: " +
                    update.fields.joinToString("; ") { it.change(update) }
            },
        ),
        ImportPlanSection("Removed (${removed.size})", removed.map { it.line() }),
        ImportPlanSection(
            "Hand edits kept against a new import value (${conflicts.size})",
            conflicts.map { it.line() },
            needsAttention = true,
        ),
        ImportPlanSection(
            "Hand-edited transactions no longer imported (${releasedOverrides.size})",
            releasedOverrides.sortedBy { it.id }.map { "#${it.id}: its hand edits no longer apply" },
        ),
    ).filter { it.lines.isNotEmpty() }
}
