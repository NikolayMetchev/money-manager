package com.moneymanager.query

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every JSON result the CLI prints is wrapped in this. [schemaVersion] only moves when a field is renamed,
 * removed or changes meaning; new fields can appear at any version, so readers should ignore unknown ones.
 */
@Serializable
data class Envelope<T>(
    val schemaVersion: Int,
    val data: T,
    /** Present only when there is another page: pass it back as the cursor to fetch it. */
    val nextCursor: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        fun <T> of(
            data: T,
            nextCursor: String? = null,
        ): Envelope<T> = Envelope(SCHEMA_VERSION, data, nextCursor)
    }
}

/** One account's balance in one asset: [AccountJson]'s balances, flattened to a row per (account, asset). */
@Serializable
data class AccountBalanceJson(
    val accountId: Long,
    val account: String,
    val asset: String,
    val kind: AssetKind,
    /** An exact decimal string, as in [BalanceJson]. */
    val amount: String,
)

fun AccountWithBalances.toBalanceRows(): List<AccountBalanceJson> =
    balances.map { balance ->
        val json = balance.toBalanceJson()
        AccountBalanceJson(accountId = account.id.id, account = account.name, asset = json.asset, kind = json.kind, amount = json.amount)
    }

@Serializable
data class TransactionJson(
    val id: Long,
    val kind: TransactionKindJson,
    /** ISO-8601 instant. */
    val timestamp: String,
    val description: String,
    val asset: String,
    val assetKind: AssetKind,
    /** Signed from the account's side: negative leaves it. An exact decimal string. */
    val amount: String,
    /** The account's balance in [asset] after this row. An exact decimal string. */
    val runningBalance: String,
    val counterpartyAccountId: Long,
    /** Null if the counterparty account is gone. */
    val counterpartyAccount: String?,
    val excluded: Boolean,
    val reconciled: Boolean,
)

@Serializable
enum class TransactionKindJson {
    @SerialName("transfer")
    TRANSFER,

    @SerialName("trade")
    TRADE,
}

/** [accountNames] names the counterparty: the side of the row that isn't its own account. */
fun AccountRow.toJson(accountNames: Map<AccountId, String>): TransactionJson {
    val counterparty = if (sourceAccountId == accountId) targetAccountId else sourceAccountId
    val amount = transactionAmount.toBalanceJson()
    return TransactionJson(
        id = transactionId.id,
        kind =
            when (kind) {
                TransactionKind.TRANSFER -> TransactionKindJson.TRANSFER
                TransactionKind.TRADE -> TransactionKindJson.TRADE
            },
        timestamp = timestamp.toString(),
        description = description,
        asset = amount.asset,
        assetKind = amount.kind,
        amount = amount.amount,
        runningBalance = runningBalance.decimalString(),
        counterpartyAccountId = counterparty.id,
        counterpartyAccount = accountNames[counterparty],
        excluded = isExcluded,
        reconciled = isReconciled,
    )
}

fun List<Account>.namesById(): Map<AccountId, String> = associate { it.id to it.name }

@Serializable
data class PersonJson(
    val id: Long,
    val firstName: String,
    val middleName: String?,
    val lastName: String?,
    val fullName: String,
)

fun Person.toJson(): PersonJson =
    PersonJson(id = id.id, firstName = firstName, middleName = middleName, lastName = lastName, fullName = fullName)

@Serializable
data class StrategyJson(
    /** A UUID. Catalog installs give a strategy a fresh one in every database, so [name] is what identifies it across databases. */
    val id: String,
    val name: String,
    val kind: StrategyKindJson,
    /** ISO-8601 instant. */
    val updatedAt: String,
)

@Serializable
enum class StrategyKindJson {
    @SerialName("csv")
    CSV,

    @SerialName("api")
    API,
}

fun CsvImportStrategy.toJson(): StrategyJson = StrategyJson(id.id.toString(), name, StrategyKindJson.CSV, updatedAt.toString())

fun ApiImportStrategy.toJson(): StrategyJson = StrategyJson(id.id.toString(), name, StrategyKindJson.API, updatedAt.toString())

/** Installed strategies: CSV, then API, each by name. */
fun installedStrategies(
    csv: List<CsvImportStrategy>,
    api: List<ApiImportStrategy>,
): List<StrategyJson> = csv.sortedBy { it.name.lowercase() }.map { it.toJson() } + api.sortedBy { it.name.lowercase() }.map { it.toJson() }

internal fun Money.decimalString(): String = toDisplayValue().toString()
