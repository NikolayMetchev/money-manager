package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.ValueExpr
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlin.math.absoluteValue

private val logger = Logger.withTag("ApiTransactionParsing")

internal fun parseTransactionsWithPath(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiTransactionPageItem> =
    try {
        val mappings = strategy.config.bankFeedMappings()
        val engine = RuleEngine()
        responseItemsArray(
            json,
            strategy.config
                .bankFeed()
                .endpoint.responseArrayKey,
        )?.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val outcome = engine.evaluate(mappings.rules, JsonRecord(obj))
            if (outcome.skipped) return@mapIndexedNotNull null
            val created = obj.resolveJsonPath(mappings.timestampField)?.let { parseApiTimestamp(it, mappings.timestampFormat) }
            val amount = parseAmount(obj, mappings, outcome.incoming)
            val currency = obj.resolveJsonPath(mappings.currencyField)
            val foreignAmount = outcome.foreignAmount?.let { parseMagnitude(obj, it.amount, mappings.amountFormat) }
            val foreignCurrency = outcome.foreignAmount?.let { obj.resolve(it.currency) }?.takeIf { it.isNotBlank() }
            val counterparty = outcome.namedAccountWithNaming(AccountSide.COUNTERPARTY, engine)
            val counterpartyName = counterparty?.first?.name?.ifBlank { null }
            val builtIn = counterparty?.second?.builtIn == true
            val feeRule = outcome.fees.firstOrNull { obj.carriesFee(it, currency) }
            val fee = feeRule?.let { parseMagnitude(obj, it.amount, mappings.amountFormat) }
            val feeCurrency = feeRule?.currency?.let { obj.resolve(it) }
            // A fee included in the amount that is all of it: the item is itself a fee, booked to the own account's fee account.
            val toFeeAccount =
                feeRule?.includedInAmount == true &&
                    fee != null &&
                    amount != null &&
                    (feeCurrency.isNullOrBlank() || feeCurrency.equals(currency, ignoreCase = true)) &&
                    fee.isMagnitudeOf(amount)
            val chargedFee = fee?.takeUnless { toFeeAccount }
            val feeDescription = feeRule?.description?.let { obj.resolve(it) }
            val balance = outcome.balance?.let { parseSigned(obj, it.amount, mappings.amountFormat) }
            val balanceCurrency =
                outcome.balance
                    ?.currency
                    ?.let { obj.resolve(it) }
                    ?.takeIf { it.isNotBlank() }
            val balanceAt =
                outcome.balance?.at?.let { obj.resolve(it) }?.takeIf { it.isNotBlank() }?.let { raw ->
                    parseApiTimestamp(raw, mappings.timestampFormat) ?: error("Failed to parse balance time: $raw")
                }
            if (created != null && amount != null && currency != null) {
                ApiTransactionPageItem(
                    amountMinorUnits = amount.minorUnits,
                    amountDecimalMajor = amount.decimalMajor,
                    amountSign = amount.sign,
                    created = created,
                    currencyCode = currency.uppercase(),
                    description = obj.resolveJsonPath(mappings.descriptionField).orEmpty(),
                    jsonPath =
                        arrayItemJsonPath(
                            strategy.config
                                .bankFeed()
                                .endpoint.responseArrayKey,
                            index,
                        ),
                    outcome = outcome,
                    counterpartyAccountName = counterpartyName.takeUnless { builtIn || toFeeAccount },
                    builtInCounterparty = counterpartyName.takeIf { builtIn && !toFeeAccount },
                    feeAccountCounterparty = toFeeAccount,
                    rawJson = obj,
                    foreignAmount = foreignAmount,
                    foreignCurrencyCode = foreignCurrency?.uppercase(),
                    feeRule = feeRule.takeUnless { toFeeAccount },
                    feeAmountMinorUnits = chargedFee?.minorUnits,
                    feeAmountDecimalMajor = chargedFee?.decimalMajor,
                    feeCurrencyCode = feeCurrency?.uppercase(),
                    feeDescription = feeDescription,
                    balance = balance,
                    balanceCurrencyCode = balanceCurrency?.uppercase(),
                    balanceAt = balanceAt,
                )
            } else {
                logger.e {
                    "Skipping API transaction at index $index: missing required fields " +
                        "(created=$created, amount=$amount, currency=$currency)"
                }
                null
            }
        }
            ?: emptyList()
    } catch (e: SerializationException) {
        logger.e(e) { "Failed to parse transactions response for strategy '${strategy.name}'" }
        emptyList()
    }

/** Normalized amount: exactly one of [minorUnits]/[decimalMajor] is set; [sign] is the direction. */
internal data class ParsedAmount(
    val minorUnits: Long?,
    val decimalMajor: BigDecimal?,
    val sign: Int,
) {
    /** Whether this magnitude is the whole of [amount], whatever its sign. */
    fun isMagnitudeOf(amount: ParsedAmount): Boolean =
        if (minorUnits != null) {
            minorUnits == amount.minorUnits?.absoluteValue
        } else {
            decimalMajor != null && amount.decimalMajor?.let { decimalMajor.compareTo(it.abs()) == 0 } == true
        }
}

/**
 * Parses a transaction amount per the strategy's amount format. The sign is the direction the rules
 * claimed ([incoming]), else the amount's own; a zero amount has none.
 */
internal fun parseAmount(
    obj: JsonObject,
    mappings: ApiTransactionMappings,
    incoming: Boolean?,
): ParsedAmount? {
    val raw = obj.resolveJsonPath(mappings.amountField) ?: return null
    return when (mappings.amountFormat) {
        ApiAmountFormat.MINOR_UNITS_INTEGER -> {
            val value = raw.toLongOrNull() ?: return null
            ParsedAmount(minorUnits = value, decimalMajor = null, sign = resolveSign(value.compareTo(0L), incoming))
        }

        ApiAmountFormat.DECIMAL_MAJOR_UNITS -> {
            val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
            ParsedAmount(minorUnits = null, decimalMajor = decimal.abs(), sign = resolveSign(decimal.compareTo(BigDecimal.ZERO), incoming))
        }
    }
}

/**
 * Parses the amount at [amount] per the strategy's [ApiAmountFormat], as a magnitude — for a fee (always
 * money out of the own account) or a foreign amount (whose direction is the settled amount's). Returns
 * null when the amount is absent, unparseable or zero.
 */
internal fun parseMagnitude(
    obj: JsonObject,
    amount: ValueExpr,
    amountFormat: ApiAmountFormat,
): ParsedAmount? {
    val raw = obj.resolve(amount).ifBlank { return null }
    return when (amountFormat) {
        ApiAmountFormat.MINOR_UNITS_INTEGER -> {
            val value = raw.toLongOrNull() ?: return null
            if (value == 0L) return null
            ParsedAmount(minorUnits = value.absoluteValue, decimalMajor = null, sign = 0)
        }

        ApiAmountFormat.DECIMAL_MAJOR_UNITS -> {
            val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
            if (decimal.compareTo(BigDecimal.ZERO) == 0) return null
            ParsedAmount(minorUnits = null, decimalMajor = decimal.abs(), sign = 0)
        }
    }
}

/** Parses the amount at [amount] per the strategy's [ApiAmountFormat], keeping its sign (a balance can be overdrawn). */
internal fun parseSigned(
    obj: JsonObject,
    amount: ValueExpr,
    amountFormat: ApiAmountFormat,
): ParsedAmount? {
    val raw = obj.resolve(amount).ifBlank { return null }
    return when (amountFormat) {
        ApiAmountFormat.MINOR_UNITS_INTEGER -> {
            val value = raw.toLongOrNull() ?: return null
            ParsedAmount(minorUnits = value, decimalMajor = null, sign = value.compareTo(0L))
        }

        ApiAmountFormat.DECIMAL_MAJOR_UNITS -> {
            val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
            ParsedAmount(minorUnits = null, decimalMajor = decimal, sign = decimal.compareTo(BigDecimal.ZERO))
        }
    }
}

/** The transaction sign (-1/0/1): [incoming] when the rules claimed a direction, else the amount's own. */
internal fun resolveSign(
    magnitudeComparison: Int,
    incoming: Boolean?,
): Int {
    val magnitudeSign = magnitudeComparison.coerceIn(-1, 1)
    return when {
        magnitudeSign == 0 -> 0
        incoming == null -> magnitudeSign
        incoming -> 1
        else -> -1
    }
}
