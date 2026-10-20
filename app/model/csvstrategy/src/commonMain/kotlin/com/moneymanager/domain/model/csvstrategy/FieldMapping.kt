package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Base interface for all field mapping types.
 * Each mapping defines how a Transfer field is populated from CSV data.
 */
@Serializable
sealed interface FieldMapping {
    val fieldType: TransferField
}

/**
 * Always uses a specific account, regardless of CSV data.
 * Typically used for the source account (the account the CSV statement belongs to). Accounts read from
 * the row are named by the strategy's rules instead (`RuleAction.Account`).
 */
@Serializable
data class HardCodedAccountMapping(
    override val fieldType: TransferField,
    val accountId: AccountId,
) : FieldMapping

/**
 * The account on this side is read from the row: the strategy's rules name it (`RuleAction.Account`
 * for the [fieldType]'s side), and the first candidate that applies wins. The account is then found by
 * that name (its current name, then a former one) or created under [defaultCategoryId]. Before naming,
 * the value the candidate read is checked against the user's persisted account mappings, which redirect
 * it to the account they chose (renamed accounts, merchants mapped onto one account).
 */
@Serializable
data class AccountFromRulesMapping(
    override val fieldType: TransferField,
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping

/**
 * Parses a date/time from one or two CSV columns.
 * If only a date column is specified, uses the defaultTime.
 *
 * When [dateTimeFormat] is set, the [dateColumnName] column holds a combined
 * date+time value parsed with that format, and the other time settings are ignored.
 */
@Serializable
data class DateTimeParsingMapping(
    override val fieldType: TransferField,
    val dateColumnName: String,
    val dateFormat: String,
    val timeColumnName: String? = null,
    val timeFormat: String? = null,
    val defaultTime: String = DEFAULT_TIME,
    val dateTimeFormat: String? = null,
) : FieldMapping {
    companion object {
        /** The time stamped onto rows whose source carries only a date. */
        const val DEFAULT_TIME: String = "12:00:00"
    }
}

/**
 * Copies a string value from the row (the description), read via [value]: the first of its columns
 * holding a non-blank value, cleaned through its extraction when that matches (the raw value is kept
 * otherwise, so nothing is lost).
 */
@Serializable
data class DirectColumnMapping(
    override val fieldType: TransferField,
    val value: ValueExpr,
) : FieldMapping

/**
 * Parses a numeric amount from CSV columns.
 * Supports two modes: single column with +/- values, or separate credit/debit columns (credit - debit).
 *
 * The magnitude is all it gives: the strategy's rules say which way the money moves
 * (`RuleAction.SetDirection`), what fee the row reports (`RuleAction.Fee`) and what it paid in another
 * currency (`RuleAction.Foreign`).
 */
@Serializable
data class AmountParsingMapping(
    override val fieldType: TransferField,
    val mode: AmountMode,
    val amountColumnName: String? = null,
    val creditColumnName: String? = null,
    val debitColumnName: String? = null,
) : FieldMapping {
    init {
        when (mode) {
            AmountMode.SINGLE_COLUMN -> {
                requireNotNull(amountColumnName) {
                    "amountColumnName is required for SINGLE_COLUMN mode"
                }
            }

            AmountMode.CREDIT_DEBIT_COLUMNS -> {
                requireNotNull(creditColumnName) {
                    "creditColumnName is required for CREDIT_DEBIT_COLUMNS mode"
                }
                requireNotNull(debitColumnName) {
                    "debitColumnName is required for CREDIT_DEBIT_COLUMNS mode"
                }
            }
        }
    }
}

/**
 * Always uses a specific currency, regardless of CSV data.
 */
@Serializable
data class HardCodedCurrencyMapping(
    override val fieldType: TransferField,
    val currencyId: CurrencyId,
) : FieldMapping

/**
 * Looks up a currency by code, read via [value] (trimmed before its extraction runs). The value should be
 * an ISO 4217 currency code (e.g., "GBP", "USD", "EUR") or a crypto code; an extraction cleans a
 * decorated cell first (e.g. Koinly's `BTC;1` → `BTC`).
 */
@Serializable
data class CurrencyLookupMapping(
    override val fieldType: TransferField,
    val value: ValueExpr,
) : FieldMapping

/**
 * Always uses a specific timezone, regardless of CSV data.
 * The timezoneId should be a valid IANA timezone ID (e.g., "Europe/London", "UTC").
 */
@Serializable
data class HardCodedTimezoneMapping(
    override val fieldType: TransferField,
    val timezoneId: String,
) : FieldMapping

/**
 * Looks up a timezone from a CSV column holding an IANA timezone ID (e.g. "Europe/London") or an offset
 * ("+01:00"). [aliases] translate what a source writes instead (keys compared upper-cased): PayPal's export
 * says "GMT"/"BST", which are no zone ids, so its strategy maps them to their offsets.
 */
@Serializable
data class TimezoneLookupMapping(
    override val fieldType: TransferField,
    val columnName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val aliases: Map<String, String> = emptyMap(),
) : FieldMapping
