package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.repository.CurrencyReadRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.absoluteValue

internal class CurrencyCache(
    private val currencyRepository: CurrencyReadRepository,
    private val assetAliases: Map<String, String>,
) {
    private val mutex = Mutex()
    private var currenciesByCode: Map<String, Currency>? = null
    private var codeByName: Lazy<Map<String, String>> = lazyOf(emptyMap())

    // A code, else a currency's name, as a CSV row's is read (see [resolveAssetCode]).
    suspend fun getCurrency(code: String): Currency? {
        val currencies = loadCurrencies()
        return currencies[resolveAssetCode(code.trim().uppercase(), assetAliases, codeByName) { it in currencies }]
    }

    private suspend fun loadCurrencies(): Map<String, Currency> =
        mutex.withLock {
            val currencies = currenciesByCode
            if (currencies != null) return@withLock currencies

            currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }.also {
                currenciesByCode = it
                codeByName = lazy { codesByName(it.values.map { currency -> currency.code to currency.name }) }
            }
        }
}

/**
 * Display name for a downloaded own account: the name its rules give, else its description (else its id).
 * Two genuinely distinct accounts can land on the identical rule-given name (e.g. two personal accounts
 * with no bank details to tell them apart) — appending a short id suffix only in that case keeps every
 * account uniquely named without cluttering the common single-account case (see [resolveOwnAccountKey]).
 */
internal fun ApiImportAccount.displayName(siblingAccounts: Collection<ApiImportAccount>): String {
    val name = ruleName ?: return description.ifBlank { id }
    val collides = siblingAccounts.any { it.id != id && it.ruleName == name }
    return if (collides) "$name (${id.takeLast(6)})" else name
}

/** The counterparty's account name: the rules' name, else the description, else "Unknown". */
internal fun ApiTransactionPageItem.counterpartyName(): String = counterpartyAccountName ?: description.ifBlank { "Unknown" }

/** The amount, description and direction of a transaction, independent of account identity. */
internal data class TransferData(
    val money: Money,
    val description: String,
    val isIncoming: Boolean,
)

// Most currencies have two decimal places, so a currency created without its minor unit is read as having two.
internal const val DEFAULT_MINOR_UNIT_DECIMALS = 2

/**
 * Converts a raw integer a bank API reports in its own ISO 4217 minor units (e.g. Monzo's `1234`
 * meaning £12.34) into [Money] at the app's storage precision. Constructing `Money` directly from
 * the raw integer would treat it as already being in the app's own (much higher) storage scale,
 * silently truncating every amount toward zero — the provider's minor-unit width and the app's
 * storage scale are unrelated. The width is the currency's ISO minor unit
 * ([Currency.fractionDigits], from the catalog's asset list), else 2; [decimalPlacesOverrides] lets a strategy
 * correct a provider that doesn't follow the ISO standard for a given currency.
 */
internal fun minorUnitsToMoney(
    rawMinorUnits: Long,
    currency: Currency,
    decimalPlacesOverrides: Map<String, Int>,
): Money {
    val decimalPlaces = decimalPlacesOverrides[currency.code.uppercase()] ?: currency.fractionDigits ?: DEFAULT_MINOR_UNIT_DECIMALS
    // The editor rejects negatives, but hand-edited or imported JSON can carry one, and a negative shift would
    // silently scale the amount up instead of down.
    require(decimalPlaces >= 0) { "Minor-unit decimal places for ${currency.code} must not be negative: $decimalPlaces" }
    return Money.fromDisplayValue(BigDecimal(rawMinorUnits).movePointLeft(decimalPlaces), currency)
}

internal fun ApiTransactionPageItem.toTransferData(
    currency: Currency,
    minorUnitDecimalPlaces: Map<String, Int>,
): TransferData {
    val money =
        when {
            amountMinorUnits != null -> minorUnitsToMoney(amountMinorUnits.absoluteValue, currency, minorUnitDecimalPlaces)
            amountDecimalMajor != null -> Money.fromDisplayValue(amountDecimalMajor, currency)
            else -> Money(0L, currency)
        }
    return TransferData(
        money = money,
        description = description.ifBlank { counterpartyAccountName ?: "Unknown" },
        isIncoming = isIncoming,
    )
}
