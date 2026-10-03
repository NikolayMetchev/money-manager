package com.moneymanager.domain.model.rules

import com.moneymanager.domain.model.serialization.SortedListSerializer
import kotlinx.serialization.Serializable

/**
 * How a [Condition] tests the value at its [Condition.path]. One vocabulary for every source: a CSV row
 * and an API item are both a [Record] addressed by path (a column name, or a JSON dot-path).
 *
 * Text comparisons ([EQUALS], [NOT_EQUALS], [IN], [NOT_IN], the `*_PATH` ops) compare trimmed values;
 * [MATCHES]/[STARTS_WITH] see the value exactly as the source wrote it.
 */
@Serializable
enum class ConditionOp {
    /** The path is present (a CSV column the file has; a JSON field that isn't `null`). */
    EXISTS,

    /** The value is absent or blank. */
    BLANK,

    /** The value is present and not blank. */
    NOT_BLANK,

    /** The value equals [Condition.value]. */
    EQUALS,

    /** The value differs from [Condition.value]; an absent value counts as different. */
    NOT_EQUALS,

    /** The value equals [Condition.value], ignoring case. */
    EQUALS_IGNORE_CASE,

    /** The value is one of [Condition.value]'s comma-separated members. */
    IN,

    /** The value is none of [Condition.value]'s comma-separated members; an absent value counts as none. */
    NOT_IN,

    /** The value starts with [Condition.value] (case-sensitive). */
    STARTS_WITH,

    /** The regex [Condition.value] is found in the value (case-insensitive, anywhere unless anchored). */
    MATCHES,

    /** The value equals the value at [Condition.otherPath]. */
    EQUALS_PATH,

    /** The value differs from the value at [Condition.otherPath]. */
    NOT_EQUALS_PATH,

    /** The path holds an array with an element starting with [Condition.value], ignoring case. */
    ANY_ELEMENT_STARTS_WITH,

    /** The path holds an empty object, or nothing. */
    EMPTY_OBJECT,

    /** The path holds a non-empty object. */
    NON_EMPTY_OBJECT,
}

/**
 * A single test of one value of a [Record] — the condition shared by every rule a strategy declares:
 * row preprocessing, conditional account routing, fee conditions, strategy content detection, API item
 * filters, decline detection, built-in counterparty and account-name rules. A list of conditions holds
 * when all of them hold, unless the owning field says otherwise.
 *
 * @property path The value tested: a CSV column name, or a JSON dot-path into an API item.
 * @property op How it is tested.
 * @property value The operand for ops that take one (a literal, a comma-separated list, or a regex).
 * @property otherPath The path compared against, for [ConditionOp.EQUALS_PATH]/[ConditionOp.NOT_EQUALS_PATH].
 */
@Serializable
data class Condition(
    val path: String,
    val op: ConditionOp,
    val value: String? = null,
    val otherPath: String? = null,
) : Comparable<Condition> {
    override fun compareTo(other: Condition): Int =
        compareValuesBy(this, other, { it.path }, { it.op.name }, { it.value }, { it.otherPath })
}

/**
 * Serializes condition lists in [Condition]'s natural order — every list of conditions is evaluated as
 * a set (all must hold, or any may), so list order carries no meaning.
 */
object SortedConditionListSerializer : SortedListSerializer<Condition>(Condition.serializer())

/** What a [ConditionOp] compares the value against. */
enum class ConditionOperand {
    /** Nothing: the op tests the value alone (e.g. [ConditionOp.BLANK]). */
    NONE,

    /** [Condition.value]: a literal, a comma-separated list or a regex. */
    VALUE,

    /** [Condition.otherPath]: another value of the same record. */
    OTHER_PATH,
}

val ConditionOp.operand: ConditionOperand
    get() =
        when (this) {
            ConditionOp.EXISTS, ConditionOp.BLANK, ConditionOp.NOT_BLANK, ConditionOp.EMPTY_OBJECT, ConditionOp.NON_EMPTY_OBJECT ->
                ConditionOperand.NONE
            ConditionOp.EQUALS_PATH, ConditionOp.NOT_EQUALS_PATH -> ConditionOperand.OTHER_PATH
            ConditionOp.EQUALS, ConditionOp.NOT_EQUALS, ConditionOp.EQUALS_IGNORE_CASE, ConditionOp.IN, ConditionOp.NOT_IN,
            ConditionOp.STARTS_WITH, ConditionOp.MATCHES, ConditionOp.ANY_ELEMENT_STARTS_WITH,
            -> ConditionOperand.VALUE
        }

/** The ops that only make sense against structured (JSON) values, not a flat row of columns. */
val ConditionOp.isStructural: Boolean
    get() = this == ConditionOp.ANY_ELEMENT_STARTS_WITH || this == ConditionOp.EMPTY_OBJECT || this == ConditionOp.NON_EMPTY_OBJECT

/** Whether this condition has every input its op needs. */
fun Condition.isComplete(): Boolean =
    path.isNotBlank() &&
        when (op.operand) {
            ConditionOperand.NONE -> true
            ConditionOperand.VALUE -> !value.isNullOrBlank()
            ConditionOperand.OTHER_PATH -> !otherPath.isNullOrBlank()
        }

/** This condition switched to [newOp], keeping only the operand [newOp] uses. */
fun Condition.withOp(newOp: ConditionOp): Condition =
    when (newOp.operand) {
        ConditionOperand.NONE -> copy(op = newOp, value = null, otherPath = null)
        ConditionOperand.VALUE -> copy(op = newOp, value = value ?: "", otherPath = null)
        ConditionOperand.OTHER_PATH -> copy(op = newOp, value = null)
    }
