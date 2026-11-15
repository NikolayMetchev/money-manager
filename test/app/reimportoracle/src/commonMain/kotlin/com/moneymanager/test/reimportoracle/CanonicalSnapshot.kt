package com.moneymanager.test.reimportoracle

import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.WellKnownIds
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * What a database holds, written without a single id or insertion order, so two databases that hold the same money
 * render the same text however they got there: accounts (with their attributes), transfers and trades by value,
 * relationships by their members' values, and each account's closing balance of every asset on every UTC day it moved.
 *
 * Each section is a sorted multiset of lines. Two records with identical values are two identical lines, so a
 * duplicate shows up as a repeated line rather than disappearing.
 */
data class CanonicalSnapshot(
    val accounts: List<String>,
    val transfers: List<String>,
    val trades: List<String>,
    val relationships: List<String>,
    val balances: List<String>,
) {
    /** The whole snapshot as text, section by section, for `assertEquals` to diff. */
    fun render(): String =
        buildString {
            section("accounts", accounts)
            section("transfers", transfers)
            section("trades", trades)
            section("relationships", relationships)
            section("daily balances", balances)
        }

    private fun StringBuilder.section(
        title: String,
        lines: List<String>,
    ) {
        append("== ")
            .append(title)
            .append(" (")
            .append(lines.size)
            .append(")\n")
        lines.forEach { append(it).append('\n') }
    }
}

/** Reads [CanonicalSnapshot] from this database, refreshing the materialized views the balances come from first. */
suspend fun DatabaseComponent.canonicalSnapshot(): CanonicalSnapshot {
    maintenanceService.refreshMaterializedViews()
    val accounts = accountRepository.getAllAccounts().first()
    val names = accounts.associate { it.id to it.name }

    fun name(id: AccountId): String = names[id] ?: "<missing account>"

    val transfers = accounts.flatMap { transactionRepository.getTransactionsByAccount(it.id).first() }.distinctBy { it.id.id }
    val trades = accounts.flatMap { tradeRepository.getTradesByAccount(it.id).first() }.distinctBy { it.id.id }

    val transferLines = transfers.associate { it.id.id to it.line(::name) }
    val tradeLines = trades.associate { it.id.id to it.line(::name) }
    val memberLines = transferLines + tradeLines

    val relationships =
        relationshipRepository
            .getByTransactions(transfers.map { it.id } + trades.map { it.id })
            .distinctBy { it.id }
            .map { relationship ->
                val members =
                    relationship.members
                        .map { "${it.role.name} {${memberLines[it.transactionId.id] ?: "<missing transaction>"}}" }
                        .sorted()
                "${relationship.relationshipType.name}: ${members.joinToString(" & ")}"
            }

    val attributesByAccount = accountAttributeRepository.getAll().first().groupBy { it.accountId }
    val accountLines =
        accounts.map { account ->
            val attributes =
                attributesByAccount[account.id]
                    .orEmpty()
                    .map { attribute ->
                        // The fee-account tag holds the id of the account it serves: name it instead.
                        val value =
                            if (attribute.attributeType.id.id == WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID) {
                                attribute.value.toLongOrNull()?.let { name(AccountId(it)) } ?: attribute.value
                            } else {
                                attribute.value
                            }
                        "${attribute.attributeType.name}=$value"
                    }.sorted()
            account.name +
                attributes.joinToString(prefix = " [", postfix = "]", separator = ", ").takeIf { attributes.isNotEmpty() }.orEmpty()
        }

    val balanceLines = accounts.flatMap { account -> dailyClosingBalances(account.id).map { "${account.name} | $it" } }

    return CanonicalSnapshot(
        accounts = accountLines.sorted(),
        transfers = transferLines.values.sorted(),
        trades = tradeLines.values.sorted(),
        relationships = relationships.sorted(),
        balances = balanceLines.sorted(),
    )
}

/** "day | asset | balance" for every UTC day [accountId] moved an asset, from the running balances. */
private suspend fun DatabaseComponent.dailyClosingBalances(accountId: AccountId): List<String> {
    val rows = mutableListOf<AccountRow>()
    var paging: PagingInfo? = null
    do {
        val page = transactionRepository.getRunningBalanceByAccountPaginated(accountId, BALANCE_PAGE_SIZE, paging)
        rows += page.items
        paging = page.pagingInfo
    } while (page.pagingInfo.hasMore && page.items.isNotEmpty())
    // Newest first (timestamp, then id, descending), so the first row of each (asset, day) closes that day.
    val closing = linkedMapOf<Pair<String, String>, Money>()
    for (row in rows) {
        val day =
            row.timestamp
                .toLocalDateTime(TimeZone.UTC)
                .date
                .toString()
        closing.getOrPut(row.runningBalance.asset.code to day) { row.runningBalance }
    }
    return closing.map { (key, balance) -> "${key.second} | ${key.first} | ${balance.plain()}" }
}

private const val BALANCE_PAGE_SIZE = 10_000

private fun Transfer.line(name: (AccountId) -> String): String =
    "$timestamp | ${name(sourceAccountId)} -> ${name(targetAccountId)} | ${amount.plain()} ${amount.asset.code} | " +
        "$description${attributes.render()}"

private fun Trade.line(name: (AccountId) -> String): String =
    "$timestamp | ${name(fromAccountId)} ${from.plain()} ${from.asset.code} -> ${name(toAccountId)} ${to.plain()} ${to.asset.code} | " +
        "$description${attributes.render()}"

private fun List<TransferAttribute>.render(): String =
    if (isEmpty()) {
        ""
    } else {
        map { "${it.attributeType.name}=${it.value}" }.sorted().joinToString(
            prefix = " [",
            postfix = "]",
            separator = ", ",
        )
    }

/** The amount without the trailing zeros of the 18 places every asset is stored at. */
private fun Money.plain(): String {
    val text = toDisplayValue().toString()
    return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
}
