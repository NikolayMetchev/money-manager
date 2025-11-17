package com.moneymanager

import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountType
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

suspend fun main() {
    println("Money Manager - JVM Application")
    println("=================================")

    // Initialize DI component using Metro-generated code
    val component: AppComponent = AppComponent.create(DatabaseDriverFactory())

    // Get repositories from the component
    val accountRepository = component.accountRepository
    val categoryRepository = component.categoryRepository
    val transactionRepository = component.transactionRepository

    println("\nDependency injection initialized successfully!")

    // Example: Query all accounts using the repository
    val accounts = accountRepository.getAllAccounts().first()
    println("\nCurrent accounts: ${accounts.size}")

    if (accounts.isEmpty()) {
        println("No accounts found. The database is ready for use!")
        println("\nYou can now:")
        println("- Create accounts")
        println("- Add categories")
        println("- Record transactions")

        // Example: Create a sample account
        println("\nCreating a sample checking account...")
        val sampleAccount = Account(
            name = "Main Checking",
            type = AccountType.CHECKING,
            currency = "USD",
            initialBalance = 1000.0,
            currentBalance = 1000.0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )
        val accountId = accountRepository.createAccount(sampleAccount)
        println("Created account with ID: $accountId")

        // Fetch and display the created account
        val createdAccount = accountRepository.getAccountById(accountId).first()
        createdAccount?.let {
            println("  - ${it.name} (${it.type}): ${it.currentBalance} ${it.currency}")
        }
    } else {
        accounts.forEach { account ->
            println("  - ${account.name} (${account.type}): ${account.currentBalance} ${account.currency}")
        }
    }

    println("\nApplication completed successfully!")
}
