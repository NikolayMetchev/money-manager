package com.moneymanager.database.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager
import kotlin.system.exitProcess

private val json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: generate <dbPath> <outputFile>")
        exitProcess(1)
    }

    when (args[0]) {
        "generate" -> generateBalancesFixture(dbPath = args[1], outputFile = File(args[2]))
        else -> {
            println("Usage: generate <dbPath> <outputFile>")
            exitProcess(1)
        }
    }
}

private fun generateBalancesFixture(
    dbPath: String,
    outputFile: File,
) {
    outputFile.parentFile?.mkdirs()

    val query =
        """
        SELECT
          a.id AS account_id,
          a.name AS account_name,
          t.currency_id AS currency_id,
          SUM(
            CASE
              WHEN t.target_account_id = a.id THEN t.amount
              WHEN t.source_account_id = a.id THEN -t.amount
              ELSE 0
            END
          ) AS balance
        FROM account a
        JOIN transfer t
          ON t.target_account_id = a.id
          OR t.source_account_id = a.id
        WHERE a.name LIKE 'Monzo:%'
           OR a.name LIKE 'Monzo Counterparty:%'
        GROUP BY a.id, a.name, t.currency_id
        ORDER BY a.name, a.id, t.currency_id
        """.trimIndent()

    val rows =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery(query).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ExpectedBalance(
                                    accountId = rs.getLong("account_id"),
                                    accountName = rs.getString("account_name"),
                                    currencyId = rs.getLong("currency_id"),
                                    balance = rs.getLong("balance"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    outputFile.writeText(json.encodeToString(ListSerializer(ExpectedBalance.serializer()), rows))
}

@Serializable
private data class ExpectedBalance(
    val accountId: Long,
    val accountName: String,
    val currencyId: Long,
    val balance: Long,
)
