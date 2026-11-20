package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.WriteIntent
import kotlinx.coroutines.flow.update

internal fun <T : WriteIntent> List<T>.creates() = filter { it.operation == ImportOperation.CREATE }

internal fun <T : WriteIntent> List<T>.updates() = filter { it.operation == ImportOperation.UPDATE }

internal fun <T : WriteIntent> List<T>.deletes() = filter { it.operation == ImportOperation.DELETE }

/** Fails loudly when an intent omits the fields its [ImportOperation] requires (nullable-by-design). */
internal fun validate(batch: ImportBatch) {
    require(batch.observationMutations.isEmpty() || batch.materialization != null) {
        "observation mutations need a materialization request"
    }
    batch.transfers.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require(it.fromAccount != null && it.toAccount != null && it.timestamp != null && it.amount != null) {
                    "CREATE transfer requires fromAccount/toAccount/timestamp/amount"
                }
            }

            ImportOperation.UPDATE, ImportOperation.DELETE -> {
                require(it.existingId != null) { "${it.operation} transfer requires existingId" }
            }
        }
    }
    batch.trades.forEach {
        when (it.operation) {
            ImportOperation.CREATE, ImportOperation.UPDATE -> {
                require(
                    it.timestamp != null && it.description != null &&
                        it.fromAccountId != null && it.fromAmount != null &&
                        it.toAccountId != null && it.toAmount != null,
                ) {
                    "${it.operation} trade requires timestamp/description/fromAccountId/" +
                        "fromAmount/toAccountId/toAmount"
                }
                if (it.operation == ImportOperation.UPDATE) {
                    require(it.existingId != null) { "UPDATE trade requires existingId" }
                }
            }

            ImportOperation.DELETE -> {
                require(it.existingId != null) { "DELETE trade requires existingId" }
            }
        }
    }
    batch.accountsToCreate.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require((it.name != null || it.match is AccountMatchKey.FeeAccountOf) && it.openingDate != null) {
                    "CREATE account requires name/openingDate"
                }
            }

            ImportOperation.UPDATE, ImportOperation.DELETE -> {
                require(it.existingId != null) { "${it.operation} account requires existingId" }
            }
        }
    }
    batch.categories.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require(it.name != null) { "CREATE category requires name" }
            }

            ImportOperation.UPDATE -> {
                require(it.existingId != null && it.category != null) { "UPDATE category requires existingId and category" }
            }

            ImportOperation.DELETE -> {
                require(it.existingId != null) { "DELETE category requires existingId" }
            }
        }
    }
    batch.currencies.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require(it.code != null && it.name != null) { "CREATE currency requires code/name" }
            }

            ImportOperation.UPDATE -> {
                require(it.currency != null) { "UPDATE currency requires currency" }
            }

            ImportOperation.DELETE -> {
                require(it.existingId != null) { "DELETE currency requires existingId" }
            }
        }
    }
    batch.peopleToCreate.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require(it.firstName != null) { "CREATE person requires firstName" }
            }

            ImportOperation.UPDATE, ImportOperation.DELETE -> {
                require(it.existingId != null) { "${it.operation} person requires existingId" }
            }
        }
    }
    batch.ownerships.forEach {
        when (it.operation) {
            ImportOperation.CREATE -> {
                require((it.personKey != null || it.existingPersonId != null) && it.account != null) {
                    "CREATE ownership requires a person and an account"
                }
            }

            ImportOperation.DELETE -> {
                require(it.existingId != null) { "DELETE ownership requires existingId" }
            }

            ImportOperation.UPDATE -> {
                error("Ownerships have no UPDATE operation")
            }
        }
    }
}

/** Fails a shadow-only batch whose account references resolved onto an account outside [source]'s shadow accounts. */
internal suspend fun requireShadowOnlyResolution(
    source: String,
    resolved: Collection<AccountId>,
    accountResolver: AccountResolver,
) {
    val shadowIds = accountResolver.shadowAccountIds(source)
    val strays = resolved.filterNot { it in shadowIds }
    require(strays.isEmpty()) { "Reconciliation import for '$source' resolved onto non-shadow account(s) $strays" }
}

/**
 * Fails a shadow-only batch (see [ImportBatch.shadowSource]) that names an existing account outside
 * [source]'s shadow accounts — the last line of defence keeping reconciliation data off real accounts.
 * Batch-local account refs are policed after resolution instead.
 */
internal suspend fun requireShadowOnlyReferences(
    batch: ImportBatch,
    source: String,
    accountResolver: AccountResolver,
) {
    // Only creates: an UPDATE/DELETE names an existing record by id, which could be a real one.
    val nonCreates =
        batch.transfers.count { it.operation != ImportOperation.CREATE } +
            batch.trades.count { it.operation != ImportOperation.CREATE } +
            batch.accountsToCreate.count { it.operation != ImportOperation.CREATE }
    require(nonCreates == 0) { "Reconciliation import for '$source' may only create records ($nonCreates update/delete intents)" }
    // A copy is the one write a source's row makes on the real accounts, and only a batch of its own makes it.
    require(batch.reconciliationCopies.isEmpty()) { "Reconciliation import for '$source' may not copy onto real accounts" }
    val referenced = mutableSetOf<AccountId>()

    fun add(ref: AccountRef?) {
        if (ref is AccountRef.Existing) referenced += ref.id
    }
    for (t in batch.transfers.creates()) {
        add(t.fromAccount)
        add(t.toAccount)
        t.fee?.let {
            add(it.source)
            add(it.target)
        }
        t.passThrough?.let { pt ->
            pt.conduits.forEach(::add)
            add(pt.merchantTarget)
        }
    }
    for (trade in batch.trades.creates()) {
        trade.fromAccountId?.let(referenced::add)
        trade.toAccountId?.let(referenced::add)
    }
    if (referenced.isEmpty()) return
    val strays = referenced - accountResolver.shadowAccountIds(source)
    require(strays.isEmpty()) { "Reconciliation import for '$source' references non-shadow account(s) $strays" }
}
