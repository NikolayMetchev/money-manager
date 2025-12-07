@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.util

import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class GenerationProgress(
    val accountsCreated: Int = 0,
    val totalAccounts: Int = 100,
    val transactionsCreated: Int = 0,
    val totalTransactions: Int = 0,
    val currentOperation: String = "Initializing...",
)

suspend fun generateSampleData(
    repositorySet: RepositorySet,
    progressFlow: MutableStateFlow<GenerationProgress>,
) {
    val random = Random.Default

    // Step 1: Fetch currencies
    progressFlow.emit(
        GenerationProgress(
            currentOperation = "Fetching currencies...",
        ),
    )

    val allCurrencies = repositorySet.currencyRepository.getAllCurrencies().first()
    if (allCurrencies.isEmpty()) {
        throw IllegalStateException("No currencies found in database. Please create currencies first.")
    }

    // Pick 10-20 popular currencies (or all if less than 20)
    val popularCurrencyCodes =
        listOf(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY", "INR", "MXN",
            "BRL", "KRW", "SGD", "NZD", "SEK", "NOK", "DKK", "PLN", "THB", "ZAR",
        )

    val selectedCurrencies =
        allCurrencies.filter { currency ->
            popularCurrencyCodes.contains(currency.code)
        }.take(20).ifEmpty { allCurrencies.take(20) }

    if (selectedCurrencies.isEmpty()) {
        throw IllegalStateException("No currencies available for sample data generation.")
    }

    // Step 2: Generate account names
    progressFlow.emit(
        GenerationProgress(
            currentOperation = "Generating account names...",
        ),
    )

    val accountNames = generateAccountNames(100)

    // Step 3: Determine transaction counts per account (variable distribution)
    val transactionCounts = mutableListOf<Int>()
    for (i in 0 until 100) {
        val count =
            when {
                i < 20 -> random.nextInt(5000, 10001) // 20% get 5000-10000 transactions
                i < 70 -> random.nextInt(1000, 3001) // 50% get 1000-3000 transactions
                else -> random.nextInt(100, 501) // 30% get 100-500 transactions
            }
        transactionCounts.add(count)
    }

    val totalExpectedTransactions = transactionCounts.sum()

    progressFlow.emit(
        GenerationProgress(
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Creating accounts...",
        ),
    )

    // Step 4: Create accounts in batch
    val now = Clock.System.now()

    progressFlow.emit(
        GenerationProgress(
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Creating 100 accounts in batch...",
        ),
    )

    val accountsToCreate =
        accountNames.map { name ->
            Account(
                // Will be auto-generated
                id = AccountId(0),
                name = name,
                // Opened 1-10 years ago
                openingDate = now.minus(random.nextInt(1, 3650).days),
            )
        }

    val accountIds = repositorySet.accountRepository.createAccountsBatch(accountsToCreate)

    progressFlow.emit(
        GenerationProgress(
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Created all 100 accounts",
        ),
    )

    // Step 5: Generate transactions in batches
    progressFlow.emit(
        GenerationProgress(
            accountsCreated = 100,
            totalAccounts = 100,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Generating transactions...",
        ),
    )

    val startDate = Instant.parse("2015-01-01T00:00:00Z")
    val endDate = Instant.parse("2025-12-31T23:59:59Z")
    val dateRangeMillis = endDate.toEpochMilliseconds() - startDate.toEpochMilliseconds()

    // Generate all transactions first, then insert in batches
    val allTransfers = mutableListOf<Transfer>()

    for ((accountIndex, accountId) in accountIds.withIndex()) {
        val transactionCount = transactionCounts[accountIndex]

        for (txIndex in 0 until transactionCount) {
            // Random timestamp between 2015-2025
            val randomMillis = random.nextLong(dateRangeMillis)
            val timestamp = Instant.fromEpochMilliseconds(startDate.toEpochMilliseconds() + randomMillis)

            // Random target account (different from source)
            val targetAccountId = accountIds.filter { it != accountId }.random(random)

            // Random currency
            val currency = selectedCurrencies.random(random)

            // Random amount (1-10000 with 2 decimal places)
            val amount = (random.nextInt(100, 1000001) / 100.0)

            // Random description
            val description = generateTransactionDescription(random)

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = accountId,
                    targetAccountId = targetAccountId,
                    currencyId = currency.id,
                    amount = amount,
                )

            allTransfers.add(transfer)
        }
    }

    // Insert transfers in batches of 1000 for better progress tracking
    val batchSize = 1000
    var transactionsCreated = 0

    for (batchStart in allTransfers.indices step batchSize) {
        val batchEnd = minOf(batchStart + batchSize, allTransfers.size)
        val batch = allTransfers.subList(batchStart, batchEnd)

        repositorySet.transactionRepository.createTransfersBatch(batch)
        transactionsCreated += batch.size

        progressFlow.emit(
            GenerationProgress(
                accountsCreated = 100,
                totalAccounts = 100,
                transactionsCreated = transactionsCreated,
                totalTransactions = totalExpectedTransactions,
                currentOperation = "Inserted $transactionsCreated/$totalExpectedTransactions transactions...",
            ),
        )
    }

    // Final progress update
    progressFlow.emit(
        GenerationProgress(
            accountsCreated = 100,
            totalAccounts = 100,
            transactionsCreated = transactionsCreated,
            totalTransactions = totalExpectedTransactions,
            currentOperation = "Sample data generation complete!",
        ),
    )
}

private fun generateAccountNames(count: Int): List<String> {
    val prefixes =
        listOf(
            "Personal", "Business", "Savings", "Emergency", "Investment", "Retirement",
            "Travel", "Education", "Health", "Entertainment", "Shopping", "Food",
            "Transport", "Utilities", "Mortgage", "Rent", "Vacation", "Hobby",
            "Charity", "Gift", "Project", "Revenue", "Expense", "Cash",
        )

    val suffixes =
        listOf(
            "Checking", "Savings", "Account", "Fund", "Wallet", "Reserve",
            "Portfolio", "Budget", "Stash", "Pool", "Treasury", "Vault",
        )

    val names = mutableListOf<String>()

    // Generate unique names
    var index = 0
    while (names.size < count) {
        if (index < count / 2) {
            // First half: Prefix + Suffix
            val prefix = prefixes.random()
            val suffix = suffixes.random()
            val name = "$prefix $suffix"
            if (!names.contains(name)) {
                names.add(name)
            }
        } else {
            // Second half: Add numbers to ensure uniqueness
            val prefix = prefixes.random()
            val suffix = suffixes.random()
            val number = (names.size / 10) + 1
            names.add("$prefix $suffix $number")
        }
        index++
    }

    return names.take(count)
}

private fun generateTransactionDescription(random: Random): String {
    val descriptions =
        listOf(
            // Income
            "Salary deposit",
            "Freelance payment",
            "Dividend income",
            "Interest earned",
            "Bonus payment",
            "Refund received",
            "Gift received",
            "Cashback reward",
            // Expenses
            "Grocery shopping",
            "Restaurant bill",
            "Gas station",
            "Online shopping",
            "Utility bill",
            "Rent payment",
            "Mortgage payment",
            "Insurance premium",
            "Phone bill",
            "Internet bill",
            "Gym membership",
            "Subscription service",
            "Coffee shop",
            "Taxi fare",
            "Public transport",
            "Parking fee",
            "Medical expense",
            "Pharmacy purchase",
            "Book purchase",
            "Movie ticket",
            "Concert ticket",
            "Hotel booking",
            "Flight ticket",
            "Car maintenance",
            "Home repair",
            "Clothing purchase",
            "Electronics purchase",
            "Gift purchase",
            "Charity donation",
            "ATM withdrawal",
            // Transfers
            "Transfer to savings",
            "Transfer from savings",
            "Account rebalancing",
            "Fund allocation",
            "Investment transfer",
            "Emergency fund deposit",
            "Budget allocation",
        )

    return descriptions.random(random)
}
