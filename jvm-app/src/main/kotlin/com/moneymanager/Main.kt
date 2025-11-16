package com.moneymanager

import com.moneymanager.data.Database
import com.moneymanager.data.DatabaseDriverFactory
import com.moneymanager.domain.model.AccountType
import kotlinx.datetime.Clock

fun main() {
    println("Money Manager - JVM Application")
    println("=================================")

    // Initialize the database
    Database.initialize(DatabaseDriverFactory())
    val database = Database.getInstance()

    // Example: Query all accounts
    val accounts = database.accountQueries.selectAll().executeAsList()
    println("\nCurrent accounts: ${accounts.size}")

    if (accounts.isEmpty()) {
        println("No accounts found. The database is ready for use!")
        println("\nYou can now:")
        println("- Create accounts")
        println("- Add categories")
        println("- Record transactions")
    } else {
        accounts.forEach { account ->
            println("  - ${account.name} (${account.type}): ${account.currentBalance} ${account.currency}")
        }
    }

    println("\nDatabase initialized successfully!")
}
