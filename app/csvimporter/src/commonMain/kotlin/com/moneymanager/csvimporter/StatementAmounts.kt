package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEvaluator

internal data class ChargedFee(
    val magnitude: BigDecimal,
    val amount: Money,
    val description: String?,
    val includedInAmount: Boolean,
)

internal fun parseBigDecimal(value: String): BigDecimal {
    val cleaned =
        value
            .trim()
            .replace(",", "") // Remove thousand separators
            .replace(" ", "")
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
    return BigDecimal(cleaned)
}

internal class StatementAmounts(
    private val config: CsvStrategyConfig<FieldMapping>,
    private val rules: RuleEvaluator,
    private val existingCurrencies: Map<CurrencyId, Currency>,
    private val existingCurrenciesByCode: Map<String, Currency>,
    /** Crypto assets keyed by uppercased ticker; consulted when a code isn't a known fiat currency. */
    private val existingCryptoByCode: Map<String, CryptoAsset>,
    /** The database's own asset aliases, as alias to asset code; see [resolveAssetCode]. */
    private val assetAliases: Map<String, String>,
) {
    fun parseForeignAmount(
        foreign: ForeignAmount?,
        values: RowValues,
        settledCurrency: Asset,
    ): Money? {
        if (foreign == null) return null
        val lookup: (String) -> String? = { values.record.text(it)?.trim() }
        val code = rules.resolve(foreign.currency, lookup).trim()
        val rawAmount = rules.resolve(foreign.amount, lookup).trim()
        if (code.isEmpty() || rawAmount.isEmpty()) return null
        val asset = lookupAsset(code) ?: error("Foreign currency not found")
        val magnitude = parseBigDecimal(rawAmount).abs()
        if (asset.id == settledCurrency.id || magnitude.compareTo(BigDecimal.ZERO) == 0) return null
        return Money.fromDisplayValue(magnitude, asset)
    }

    /**
     * The balance the row reports (see [RuleAction.Balance]), with its sign: null when no rule declares one or the cell is
     * blank. Its currency is the row's unless the rule names another.
     */
    fun parseBalance(
        balance: RuleAction.Balance?,
        values: RowValues,
        rowCurrency: Asset,
    ): Money? {
        if (balance == null) return null
        val lookup: (String) -> String? = { values.record.text(it)?.trim() }
        val raw = rules.resolve(balance.amount, lookup).trim()
        if (raw.isEmpty()) return null
        val asset =
            balance.currency?.let { expr ->
                val code = rules.resolve(expr, lookup).trim()
                if (code.isEmpty()) rowCurrency else lookupAsset(code) ?: error("Balance currency not found: $code")
            } ?: rowCurrency
        return Money.fromDisplayValue(parseBigDecimal(raw), asset)
    }

    fun parseAmount(
        amountMapping: FieldMapping,
        values: RowValues,
    ): BigDecimal {
        val mapping = amountMapping as AmountParsingMapping
        return when (mapping.mode) {
            AmountMode.SINGLE_COLUMN -> {
                val columnName =
                    mapping.amountColumnName
                        ?: error("amountColumnName required for SINGLE_COLUMN mode")
                parseBigDecimal(values.required(columnName))
            }

            AmountMode.CREDIT_DEBIT_COLUMNS -> {
                val creditColumnName =
                    mapping.creditColumnName
                        ?: error("creditColumnName required")
                val debitColumnName =
                    mapping.debitColumnName
                        ?: error("debitColumnName required")
                val creditValue = values.required(creditColumnName)
                val debitValue = values.required(debitColumnName)
                val credit = if (creditValue.isNotBlank()) parseBigDecimal(creditValue) else BigDecimal.ZERO
                val debit = if (debitValue.isNotBlank()) parseBigDecimal(debitValue) else BigDecimal.ZERO
                credit - debit
            }
        }
    }

    fun parseFee(
        fees: List<FeeRule>,
        values: RowValues,
        rowCurrency: Asset,
    ): ChargedFee? = fees.firstNotNullOfOrNull { chargedFee(it, values, rowCurrency) }

    /**
     * The fee [fee] charges on this row: none when its conditions don't hold, it is charged on another
     * asset, or the amount is blank or zero. Its sign is ignored — a fee is a movement out of the
     * row's own account. The fee is in [rowCurrency] unless the rule's currency reads a
     * non-blank code of its own.
     */
    private fun chargedFee(
        fee: FeeRule,
        values: RowValues,
        rowCurrency: Asset,
    ): ChargedFee? {
        val lookup = { path: String -> values.record.text(path)?.trim() }
        if (!rules.all(fee.conditions, values.record)) return null
        if (fee.chargedOnAsset?.let { !rules.resolve(it, lookup).equals(rowCurrency.code, ignoreCase = true) } == true) {
            return null
        }
        val feeValue = rules.resolve(fee.amount, lookup)
        if (feeValue.isBlank()) return null
        val magnitude = parseBigDecimal(feeValue).abs()
        if (magnitude <= BigDecimal.ZERO) return null
        val code = fee.currency?.let { rules.resolve(it, lookup) }.orEmpty()
        val feeCurrency = if (code.isBlank()) rowCurrency else lookupAsset(code) ?: error("Fee currency not found")
        return ChargedFee(
            magnitude = magnitude,
            amount = Money.fromDisplayValue(magnitude, feeCurrency),
            description = fee.description?.let { rules.resolve(it, lookup) }?.takeIf { it.isNotBlank() },
            includedInAmount = fee.includedInAmount,
        )
    }

    fun parseCurrency(
        mapping: FieldMapping,
        values: RowValues,
    ): Asset? =
        when (mapping) {
            is HardCodedCurrencyMapping -> existingCurrencies[mapping.currencyId]
            is CurrencyLookupMapping -> lookupAsset(rules.resolve(mapping.value) { values.required(it).trim() })
            else -> throw IllegalArgumentException("Invalid currency mapping type: ${mapping::class}")
        }

    /** The code [mapping] reads from the row, before any alias turns it into another asset's. */
    private fun assetCodeIn(
        mapping: FieldMapping,
        values: RowValues,
    ): String? =
        (mapping as? CurrencyLookupMapping)?.let { lookup ->
            config.assetCodes.canonical(rules.resolve(lookup.value) { values.required(it).trim() })
        }

    private val assetCodeByName =
        lazy {
            codesByName(
                existingCurrenciesByCode.values.map { it.code to it.name } + existingCryptoByCode.values.map { it.code to it.name },
            )
        }

    // Fiat first; fall back to crypto so a strategy can denominate a leg in a crypto asset.
    private fun lookupAsset(code: String): Asset? =
        resolveAssetCode(config.assetCodes.canonical(code), assetAliases, assetCodeByName) {
            it in existingCurrenciesByCode ||
                it in existingCryptoByCode
        }.let { existingCurrenciesByCode[it] ?: existingCryptoByCode[it] }

    fun tradeTo(
        currencyMapping: FieldMapping,
        currency: Asset,
        rawAmount: BigDecimal,
        values: RowValues,
    ): Money? {
        val toCurrencyMapping = config.fieldMappings[TransferField.TO_CURRENCY] ?: return null
        val toAmountMapping = config.fieldMappings[TransferField.TO_AMOUNT] ?: return null
        val toAsset = parseCurrency(toCurrencyMapping, values) ?: return null
        val toRaw = parseAmount(toAmountMapping, values).abs()
        if (toAsset.id == currency.id) {
            // A row naming one code twice is an ordinary transfer. Two codes an alias joined, for
            // different amounts, is a conversion a transfer can't hold: the credited amount would be lost.
            val fromCode = assetCodeIn(currencyMapping, values) ?: currency.code
            val toCode = assetCodeIn(toCurrencyMapping, values) ?: toAsset.code
            if (!fromCode.equals(toCode, ignoreCase = true) && toRaw.compareTo(rawAmount.abs()) != 0) {
                error("$fromCode and $toCode are both ${currency.code}, so this conversion needs two assets")
            }
            return null
        }
        if (toRaw.compareTo(BigDecimal.ZERO) == 0) return null
        return Money.fromDisplayValue(toRaw, toAsset)
    }
}
