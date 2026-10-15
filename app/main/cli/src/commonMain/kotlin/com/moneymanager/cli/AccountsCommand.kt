package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.AccountBalanceJson
import com.moneymanager.query.AccountJson
import com.moneymanager.query.AccountQueryCriteria
import com.moneymanager.query.toBalanceRows
import com.moneymanager.query.toJson
import kotlinx.serialization.builtins.ListSerializer

internal class AccountsCommand : SuspendingCliktCommand(name = "accounts") {
    private val runtime by requireObject<CliRuntime>()
    private val owners by ownerOption()
    private val account by accountNameOption()
    private val includeShadow by includeShadowOption()

    override fun help(context: Context): String = "List accounts with their owners and balances"

    override suspend fun run() {
        val criteria = AccountQueryCriteria(owners = owners.toSet(), name = account, includeShadow = includeShadow)
        val accounts =
            runtime.withSession { session ->
                session.services.refreshBalances()
                val snapshot = session.services.accountSnapshot()
                resolvingNames { snapshot.filter(criteria) }.map { it.toJson() }
            }
        runtime.output.emit(accounts, ListSerializer(AccountJson.serializer())) { rows ->
            table {
                header { row("Id", "Name", "Owners", "Balances") }
                body {
                    rows.forEach { account ->
                        row(
                            account.id,
                            account.name + if (account.shadow) " (shadow)" else "",
                            account.owners.joinToString { it.name },
                            account.balances.joinToString("\n") { "${it.amount} ${it.asset}" },
                        )
                    }
                }
            }
        }
    }
}

internal class BalancesCommand : SuspendingCliktCommand(name = "balances") {
    private val runtime by requireObject<CliRuntime>()
    private val owners by ownerOption()
    private val account by accountNameOption()
    private val assets by assetOption()
    private val includeShadow by includeShadowOption()
    private val nonZero by option("--non-zero", help = "Leave out zero balances").flag()

    override fun help(context: Context): String = "List balances, one row per account and asset"

    override suspend fun run() {
        val criteria =
            AccountQueryCriteria(
                owners = owners.toSet(),
                name = account,
                assets = assets.toSet(),
                includeShadow = includeShadow,
                nonZeroOnly = nonZero,
            )
        val balances =
            runtime.withSession { session ->
                session.services.refreshBalances()
                val snapshot = session.services.accountSnapshot()
                resolvingNames { snapshot.filter(criteria) }.flatMap { it.toBalanceRows() }
            }
        runtime.output.emit(balances, ListSerializer(AccountBalanceJson.serializer())) { rows ->
            table {
                column(2) { align = TextAlign.RIGHT }
                header { row("Account", "Asset", "Amount") }
                body { rows.forEach { row(it.account, it.asset, it.amount) } }
            }
        }
    }
}
