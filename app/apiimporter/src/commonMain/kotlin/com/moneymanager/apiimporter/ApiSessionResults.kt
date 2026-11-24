package com.moneymanager.apiimporter

import kotlin.time.Instant

data class ApiSessionImportResult(
    val accountCount: Int,
    val transactionCount: Int,
    val personCount: Int = 0,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
)

data class ApiCounterpartySuggestion(
    val counterpartyId: String,
    val suggestedAccountName: String,
    val downloadedNames: List<String>,
)

data class ApiAccountsDownloadResult(
    val accountCount: Int,
    val skipped: Boolean = false,
)

data class ApiTransactionsDownloadResult(
    val accountCount: Int,
    val transactionResponseCount: Int,
    /**
     * Where this download actually started because earlier sessions had already covered the history
     * before it; null when the full configured lookback was swept.
     */
    val incrementalSince: Instant? = null,
)

data class ApiAccountIdentifiersDownloadResult(
    val accountCount: Int,
    val skipped: Boolean = false,
)

data class ApiPeopleDownloadResult(
    val personCount: Int,
    val skipped: Boolean = false,
)

/**
 * The combined outcome of one download: accounts, plus transactions (null when region-blocked) and
 * people (null when the strategy has no people-download endpoint), all into a single session.
 */
data class ApiSessionDownloadResult(
    val accounts: ApiAccountsDownloadResult,
    val transactions: ApiTransactionsDownloadResult?,
    val people: ApiPeopleDownloadResult?,
)

fun ApiAccountsDownloadResult.displaySummary(): String =
    if (skipped) {
        "Accounts already downloaded: $accountCount account(s) (skipped)."
    } else {
        "Accounts downloaded: $accountCount account(s)."
    }

fun ApiTransactionsDownloadResult.displaySummary(): String =
    buildString {
        append("Transactions downloaded: $accountCount account(s), $transactionResponseCount new response page(s).")
        // Date-only, in UTC: this is a coarse "how far back did we go" hint, not a precise timestamp.
        incrementalSince?.let { append(" Incremental: fetched from ${it.toString().substringBefore('T')} onwards.") }
    }

fun ApiPeopleDownloadResult.displaySummary(): String =
    if (skipped) {
        "People already downloaded: $personCount person(s) (skipped)."
    } else {
        "People downloaded: $personCount person(s)."
    }

fun ApiSessionDownloadResult.displaySummary(): String =
    buildList {
        add(accounts.displaySummary())
        transactions?.let { add(it.displaySummary()) }
        people?.let { add(it.displaySummary()) }
    }.joinToString("\n")

data class ApiPeopleImportResult(
    val personCount: Int,
)
