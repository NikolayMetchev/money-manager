package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.TransactionCursor
import com.moneymanager.query.TransactionJson
import com.moneymanager.query.TransactionQuery
import com.moneymanager.query.namesById
import com.moneymanager.query.toJson
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer

/** `mm transactions`: one account's transactions with running balances, newest first, a page at a time. */
internal class TransactionsCommand(
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) : SuspendingCliktCommand(name = "transactions") {
    private val runtime by requireObject<CliRuntime>()
    private val account by accountRefOption().required()
    private val includeShadow by includeShadowOption()
    private val from by option("--from", metavar = "DATE", help = "From this date (YYYY-MM-DD, local time) or ISO-8601 instant")
        .convert { parseDateOption(it, zone(), through = false) }
    private val to by option("--to", metavar = "DATE", help = "Up to and including this date, or before this ISO-8601 instant")
        .convert { parseDateOption(it, zone(), through = true) }
    private val limit by option("--limit", metavar = "N", help = "Rows per page (default $DEFAULT_LIMIT)")
        .int()
        .restrictTo(min = 1)
        .default(DEFAULT_LIMIT)
    private val cursor by option("--cursor", help = "Continue from a previous page's nextCursor (repeat the same --from)")
        .convert { value -> runCatching { TransactionCursor.decode(value) }.getOrElse { fail(it.message.orEmpty()) } }

    override fun help(context: Context): String = "List one account's transactions with running balances, newest first"

    override suspend fun run() {
        val (rows, next) =
            runtime.withSession { session ->
                val services = session.services
                services.refreshBalances()
                val snapshot = services.accountSnapshot()
                val resolved = resolvingNames { snapshot.resolveAccount(account, includeShadow) }
                val page = TransactionQuery(services.transactions.transactionRepository).page(resolved.account.id, limit, from, to, cursor)
                val names = snapshot.accounts.map { it.account }.namesById()
                page.rows.map { it.toJson(names) } to page.next?.encode()
            }
        runtime.output.emit(rows, ListSerializer(TransactionJson.serializer()), next) { transactions ->
            table {
                column(3) { align = TextAlign.RIGHT }
                column(4) { align = TextAlign.RIGHT }
                header { row("Date", "Description", "Counterparty", "Amount", "Balance") }
                body {
                    transactions.forEach {
                        row(it.timestamp, it.description, it.counterpartyAccount.orEmpty(), "${it.amount} ${it.asset}", it.runningBalance)
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
    }
}
