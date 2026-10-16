package com.moneymanager.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.moneymanager.database.di.AppServices
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.query.AccountQuery
import com.moneymanager.query.AccountRef
import com.moneymanager.query.AccountSnapshot
import com.moneymanager.query.AssetRef
import com.moneymanager.query.NameMatch
import com.moneymanager.query.OwnerRef
import com.moneymanager.query.UnresolvedReferenceException
import com.moneymanager.query.parseDateBound
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

// The options the query commands share. Each only parses; what a value matches is app/query's call, so the
// desktop app, the CLI and the TUI agree.

internal fun ParameterHolder.ownerOption() =
    option("--owner", metavar = "NAME|ID", help = "Only accounts owned by this person (full name, any case, or id); repeatable")
        .convert { value -> value.toLongOrNull()?.let { OwnerRef.Id(PersonId(it)) } ?: OwnerRef.Name(value) }
        .multiple()

internal fun ParameterHolder.accountNameOption() =
    option("--account", metavar = "REGEX", help = "Only accounts whose name matches this regular expression (any case)")
        .convert { nameMatch(it) }

/** One account: an id, or a regular expression that must match exactly one account's name. */
internal fun ParameterHolder.accountRefOption() =
    option("--account", metavar = "REGEX|ID", help = "The account: its id, or a regular expression matching one account's name")
        .convert { value -> value.toLongOrNull()?.let { AccountRef.Id(AccountId(it)) } ?: AccountRef.Name(nameMatch(value)) }

internal fun ParameterHolder.assetOption() =
    option("--asset", metavar = "CODE", help = "Only this asset, e.g. GBP or BTC; repeatable").convert { AssetRef.Code(it) }.multiple()

internal fun ParameterHolder.includeShadowOption() =
    option("--include-shadow", help = "Also show reconciliation sources' shadow accounts (e.g. Koinly's)").flag()

private fun nameMatch(value: String): NameMatch =
    try {
        NameMatch.Pattern(value)
    } catch (invalid: IllegalArgumentException) {
        throw UsageError("'$value' isn't a valid regular expression: ${invalid.message}")
    }

/** A `--from`/`--to` value (see [parseDateBound]); anything else is a usage error. */
internal fun parseDateOption(
    value: String,
    zone: TimeZone,
    through: Boolean,
): Instant =
    try {
        parseDateBound(value, zone, through)
    } catch (invalid: IllegalArgumentException) {
        throw UsageError(invalid.message.orEmpty())
    }

/** Every account with owners, balances and the shadow flag, read the way the Accounts screen reads them. */
internal suspend fun AppServices.accountSnapshot(): AccountSnapshot =
    AccountQuery(
        accountRepository = accounts.accountRepository,
        accountAttributeRepository = accounts.accountAttributeRepository,
        transactionRepository = transactions.transactionRepository,
        personRepository = people.personRepository,
        personAccountOwnershipRepository = people.personAccountOwnershipRepository,
    ).snapshot().first()

/** Balances come from materialized views; bring them up to date first (a view rebuild isn't a change to sync). */
internal suspend fun AppServices.refreshBalances() {
    imports.maintenance.refreshMaterializedViews()
}

/** Runs [block], turning a name that matches nobody, or several, into a usage error that lists the candidates. */
internal inline fun <T> resolvingNames(block: () -> T): T =
    try {
        block()
    } catch (unresolved: UnresolvedReferenceException) {
        throw UsageError(unresolved.message.orEmpty())
    }
