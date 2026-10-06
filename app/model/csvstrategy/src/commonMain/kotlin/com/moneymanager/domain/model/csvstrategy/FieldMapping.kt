package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
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
 * Typically used for the source account (the account the CSV statement belongs to).
 */
@Serializable
data class HardCodedAccountMapping(
    override val fieldType: TransferField,
    val accountId: AccountId,
) : FieldMapping

/**
 * Names the account on one side of a row (the source or the target): the first of [rules] that applies
 * names it, and the account is then found by that name (its current name, then a former one) or
 * created under [defaultCategoryId]. Before naming, the value the rule read is checked against the
 * user's persisted account mappings, which redirect it to the account they chose (renamed accounts,
 * merchants mapped onto one account).
 *
 * One rule list covers what used to be five mapping kinds: a plain lookup (one rule naming the account
 * after the value), a prefixed template, regex rules with a fallback, an account-attribute match, and
 * a conditional choice between two of those (rules with [AccountRule.conditions]).
 */
@Serializable
data class AccountRulesMapping(
    override val fieldType: TransferField,
    // First applicable rule wins - order is semantic, keeps default insertion-order serialization.
    val rules: List<AccountRule>,
    val defaultCategoryId: Long = Category.UNCATEGORIZED_ID,
) : FieldMapping {
    companion object {
        /**
         * [whenTrue]'s rules guarded by [conditions], then [whenFalse]'s: the first mapping's account when
         * every condition holds, otherwise the second's. New accounts take [whenFalse]'s category.
         */
        fun conditional(
            fieldType: TransferField,
            conditions: List<Condition>,
            whenTrue: AccountRulesMapping,
            whenFalse: AccountRulesMapping,
        ): AccountRulesMapping =
            AccountRulesMapping(
                fieldType = fieldType,
                rules = whenTrue.rules.map { it.copy(conditions = conditions + it.conditions) } + whenFalse.rules,
                defaultCategoryId = whenFalse.defaultCategoryId,
            )
    }
}

/**
 * One way of naming an account from a row. A rule applies when all of its [conditions] hold and, when
 * it has a [pattern], the pattern matches the value it reads — or, with an [attributeTypeName], exactly
 * one account's attribute regexes match the value (that account is then the answer, whatever [name]
 * says).
 *
 * @property value The row value the rule reads: the first of its columns holding a non-blank value. Its
 *   extraction (if any) cleans the value before it is substituted into [name]; persisted account
 *   mappings and [pattern] see it uncleaned.
 * @property trim Whether the value is trimmed first (and again after extraction).
 * @property pattern A regex (case-insensitive) the value must match for the rule to apply; its capture
 *   groups can be substituted into [name] and [personName].
 * @property name The account name. `{value}` is replaced by the (cleaned) value; `$0`/`$1`…`$9`/`${name}`
 *   by [pattern]'s captures (a pattern-less rule substitutes only `{value}`). A name built from captures has
 *   its whitespace runs collapsed. A blank result means no account unless [fallbackName] is set.
 * @property fallbackName The name to use when [name] renders blank (a capture that came out empty).
 * @property attributeTypeName Match the value against the regex tokens this account-attribute type
 *   holds on every account (see [AttributeAccountMatch]); the rule applies when exactly one matches.
 * @property counterpartyIsPerson The account belongs to a person: the import also creates the Person
 *   (named [personName], else the account name) and an ownership link.
 * @property counterpartyIsUnidentified The rule names the counterparty but not its identity: the export
 *   says money arrived or left, not whose account it was (e.g. crypto.com's card statement records a
 *   top-up only as "GBP Deposit"), so the import engine reconciles the row against a real record of
 *   the same movement — see `ImportTransfer.unidentifiedCounterpartyAccountId`.
 */
@Serializable
data class AccountRule(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedConditionListSerializer::class)
    val conditions: List<Condition> = emptyList(),
    val value: ValueExpr,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val trim: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pattern: String? = null,
    val name: String = VALUE_PLACEHOLDER,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fallbackName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attributeTypeName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val counterpartyIsPerson: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val personName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val counterpartyIsUnidentified: Boolean = false,
) {
    companion object {
        /** In [name], the (cleaned) value the rule read. */
        const val VALUE_PLACEHOLDER: String = "{value}"
    }
}

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
 * [direction] says whether the money moves out of the source (statement) account into the target, or
 * the other way: [Direction.AmountSign] for statements whose signed amount says (positive = money in,
 * which swaps the source and target), [Direction.Outgoing] for unsigned amounts that always leave the
 * source, [Direction.Field] for a column naming the direction.
 *
 * When [fee] is set and the row reports one, the fee is imported as its own transfer out of the source
 * account, linked to the main transaction (via a `fee` relationship) — exports like Wise's, where the
 * amount column is net of fees but the fee also left the account (an ATM withdrawal: 200.00 withdrawn
 * plus a 7.29 fee movement).
 *
 * When [foreignAmount] is set and the row reports a different currency there, the parsed amount is what
 * the statement account settled, and the movement itself is booked in the foreign currency (see
 * [ForeignAmount]).
 */
@Serializable
data class AmountParsingMapping(
    override val fieldType: TransferField,
    val mode: AmountMode,
    val amountColumnName: String? = null,
    val creditColumnName: String? = null,
    val debitColumnName: String? = null,
    val direction: Direction = Direction.Outgoing,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fee: FeeRule? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val foreignAmount: ForeignAmount? = null,
) : FieldMapping {
    init {
        when (mode) {
            AmountMode.SINGLE_COLUMN ->
                requireNotNull(amountColumnName) {
                    "amountColumnName is required for SINGLE_COLUMN mode"
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
