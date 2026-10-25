package com.moneymanager.domain.model.rules

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One "if [conditions] then [actions]" rule — the one decision vocabulary of every strategy, CSV and API
 * alike. A strategy lists its rules in order and [RuleEngine] runs them over each record (a CSV row, an
 * API item, an API account item): every rule whose conditions all hold applies its actions.
 *
 * @property name What the rule is for, shown in the editor. Never affects the outcome.
 * @property conditions All must hold for the rule to apply; empty means every record.
 */
@Serializable
data class StrategyRule(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedConditionListSerializer::class)
    val conditions: List<Condition> = emptyList(),
    // Actions apply in order, and a record rewrite is seen by the actions after it - order is semantic.
    val actions: List<RuleAction>,
) {
    companion object {
        /** A rule offering [namings], in order, as [side]'s account candidates when every one of [conditions] holds. */
        fun accounts(
            side: AccountSide,
            namings: List<AccountNaming>,
            conditions: List<Condition> = emptyList(),
        ): StrategyRule = StrategyRule(conditions = conditions, actions = namings.map { RuleAction.Account(side, it) })
    }
}

/**
 * A value an action computes: read from the record ([Read]) or written out ([Template], whose `{path}`
 * placeholders are replaced by the record's values at those paths — so a template without placeholders
 * is a constant).
 */
@Serializable
sealed interface RuleValue {
    @Serializable
    @SerialName("read")
    data class Read(
        val expr: ValueExpr,
    ) : RuleValue

    @Serializable
    @SerialName("template")
    data class Template(
        val template: String,
    ) : RuleValue

    companion object {
        fun read(
            path: String,
            vararg fallbacks: String,
            extraction: Extraction? = null,
        ): RuleValue = Read(ValueExpr.of(path, *fallbacks, extraction = extraction))

        fun constant(value: String): RuleValue = Template(value)
    }
}

/** Which account an [RuleAction.Account] names. */
@Serializable
enum class AccountSide {
    /** The account a row moves money out of (CSV). */
    SOURCE,

    /** The account a row moves money into (CSV). */
    TARGET,

    /** The other side of the strategy's own account (an API transaction's counterparty). */
    COUNTERPARTY,

    /** The strategy's own account, named from an account item (API accounts). */
    OWN,

    /** The intermediate account a multi-row group runs through. */
    INTERMEDIATE,
}

/**
 * How an account is named from a record. It applies when its [pattern] (if any) matches the value it
 * reads, or, with an [attributeTypeName], when exactly one account's attribute regexes match the value
 * (that account is then the answer, whatever [name] says). Otherwise the next candidate for the same
 * side is tried, so an account action is a candidate the importer confirms, not a final answer.
 *
 * @property value The value read: its extraction (if any) cleans the value before it is substituted into
 *   [name]; persisted account mappings and [pattern] see it uncleaned.
 * @property trim Whether the value is trimmed first (and again after extraction).
 * @property pattern A regex (case-insensitive) the value must match; its captures can be substituted
 *   into [name] and [personName].
 * @property name The account name. `{value}` is replaced by the (cleaned) value; `$0`/`$1`…`$9`/`${name}`
 *   by [pattern]'s captures. A blank result means no account unless [fallbackName] is set.
 * @property fallbackName The name used when [name] renders blank.
 * @property attributeTypeName Match the value against this account-attribute type's tokens.
 * @property person The account belongs to a person, named [personName] (else the account name).
 * @property unidentified The record names the counterparty but not its identity (a "GBP Deposit"), so
 *   the import engine reconciles it against a real record of the same movement.
 * @property builtIn A well-known counterparty such as "ATM": every record naming it consolidates into one
 *   account, whatever counterparty id or merchant the record carries (API transactions).
 */
@Serializable
data class AccountNaming(
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
    val person: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val personName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unidentified: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val builtIn: Boolean = false,
) {
    companion object {
        /** In [name], the (cleaned) value the naming read. */
        const val VALUE_PLACEHOLDER: String = "{value}"
    }
}

/** Which side of a multi-row event a [RuleAction.Leg] is. */
@Serializable
sealed interface LegRole {
    /** An asset leaving the owner account. */
    @Serializable
    @SerialName("debit")
    data object Debit : LegRole

    /** An asset arriving in the owner account. */
    @Serializable
    @SerialName("credit")
    data object Credit : LegRole

    /** The sign of the value at [path]: negative a debit, positive a credit, zero or unparseable no leg. */
    @Serializable
    @SerialName("bySign")
    data class BySign(
        val path: String,
    ) : LegRole
}

/** Where a [RuleAction.Capture]'s values go when records are assembled into rows (PDF lines). */
@Serializable
enum class CaptureTarget {
    /** The record starts a new row; the row in progress ends. */
    NEW_ROW,

    /** The values join the row in progress (a continuation line). Before any row, they're dropped. */
    ROW,

    /** The values belong to the whole document (a statement date) and fill every row that lacks them. */
    DOCUMENT,
}

/**
 * What a [StrategyRule] does. Each action either rewrites the record, so later rules and actions see the
 * new value, or claims a slot of the [RuleOutcome]. A single-valued slot keeps the first claim; a list
 * slot (attributes, fees, account candidates, legs) collects every claim in order.
 */
@Serializable
sealed interface RuleAction {
    /** Exchanges the values at [first] and [second]. */
    @Serializable
    @SerialName("swap")
    data class SwapValues(
        val first: String,
        val second: String,
    ) : RuleAction

    /** Sets the value at [path] (it then reads as present). */
    @Serializable
    @SerialName("set")
    data class SetValue(
        val path: String,
        val value: RuleValue,
    ) : RuleAction

    /** Swaps the record's source and target accounts once they're resolved; two flips cancel. */
    @Serializable
    @SerialName("flip")
    data object Flip : RuleAction

    /** The money moves into the record's own account ([incoming]) or out of it. */
    @Serializable
    @SerialName("direction")
    data class SetDirection(
        val incoming: Boolean,
    ) : RuleAction

    /** The record is not imported at all; no later rule runs. */
    @Serializable
    @SerialName("skip")
    data object Skip : RuleAction

    /** The record is imported but excluded from balances; [reason] (if any) says why. */
    @Serializable
    @SerialName("exclude")
    data class Exclude(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val reason: RuleValue? = null,
    ) : RuleAction

    /** A candidate name for the [side] account (see [AccountNaming]). */
    @Serializable
    @SerialName("account")
    data class Account(
        val side: AccountSide,
        val naming: AccountNaming,
    ) : RuleAction

    /** The record's stable external id. */
    @Serializable
    @SerialName("externalId")
    data class ExternalId(
        val value: RuleValue,
    ) : RuleAction

    /** The counterparty is a person rather than a business. */
    @Serializable
    @SerialName("counterpartyIsPerson")
    data object CounterpartyIsPerson : RuleAction

    /**
     * The counterparty user id the record carries is throwaway (Monzo mints a fresh `anonuser_…` per bank
     * transfer), so it never identifies the counterparty, which is then matched by name.
     */
    @Serializable
    @SerialName("ignoreCounterpartyId")
    data object IgnoreCounterpartyId : RuleAction

    /**
     * Tags the record with attribute [typeName] = [value] (omitted when the value is blank). A
     * [uniqueIdentifier] attribute also identifies the record across imports.
     */
    @Serializable
    @SerialName("attribute")
    data class Attribute(
        val typeName: String,
        val value: RuleValue,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val uniqueIdentifier: Boolean = false,
    ) : RuleAction

    /** A fee the record reports (see [FeeRule]). */
    @Serializable
    @SerialName("fee")
    data class Fee(
        val fee: FeeRule,
    ) : RuleAction

    /** What the counterparty was paid in another currency (see [ForeignAmount]). */
    @Serializable
    @SerialName("foreignAmount")
    data class Foreign(
        val foreignAmount: ForeignAmount,
    ) : RuleAction

    /** The record runs through the conduit [accountName] as a pass-through chain. */
    @Serializable
    @SerialName("throughConduit")
    data class ThroughConduit(
        val accountName: String,
    ) : RuleAction

    /** A trade record buys its base asset ([buy]) or sells it. */
    @Serializable
    @SerialName("tradeSide")
    data class TradeSide(
        val buy: Boolean,
    ) : RuleAction

    /** The record is a [role] leg of the strategy's multi-row group named [group]. */
    @Serializable
    @SerialName("leg")
    data class Leg(
        val group: String,
        val role: LegRole,
    ) : RuleAction

    /** The record is a leg of a ledger trade rather than a transfer of its own. */
    @Serializable
    @SerialName("ledgerTradeLeg")
    data object LedgerTradeLeg : RuleAction

    /**
     * Matches [pattern] (case-insensitive) against the value at [path] and sets each named group
     * (`(?<date>…)`) as a value of its own, as [SetValue] would, then claims [target]: where the record's
     * values go when records are assembled into rows (see [CaptureTarget]). When the pattern doesn't match,
     * nothing is set and the rule's remaining actions don't run.
     */
    @Serializable
    @SerialName("capture")
    data class Capture(
        val path: String,
        val pattern: String,
        val target: CaptureTarget,
    ) : RuleAction
}

private val NAMED_GROUP = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

/** The named groups of [pattern], in the order they open: the values a [RuleAction.Capture] sets. */
fun captureGroupNames(pattern: String): List<String> =
    NAMED_GROUP
        .findAll(pattern)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
