package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.serialization.SortedListSerializer
import kotlinx.serialization.Serializable

/**
 * Declares that imported transfers matching an attribute pattern require a manually
 * entered companion transaction.
 *
 * Some exports omit one side of a recurring pair of transactions. Wise's CSV, for
 * example, includes the monthly "Assets fee" but not the interest payment it implies,
 * so the interest must be entered by hand. A rule identifies the imported transfers
 * needing a companion via [matchAttributeName]/[matchValuePattern], and the companion
 * transfer records the matched transfer's attribute value under [linkAttributeName] so
 * the pair can be detected. A matched transfer with no such link is "missing" its
 * companion and is surfaced for manual entry.
 *
 * The companion mirrors the matched transfer: source/target accounts flipped, same
 * currency and timestamp; only the amount is entered by the user.
 *
 * @property name Human-readable name for the rule (e.g. "Interest earned")
 * @property matchAttributeName Attribute identifying matched transfers (e.g. "wise-id")
 * @property matchValuePattern SQL LIKE pattern the attribute value must match
 *                             (e.g. "ACCRUAL_CHARGE-%")
 * @property linkAttributeName Attribute stored on the companion transfer holding the
 *                             matched transfer's [matchAttributeName] value
 * @property companionDescription Description given to created companion transfers
 */
@Serializable
data class CompanionTransactionRule(
    val name: String,
    val matchAttributeName: String,
    val matchValuePattern: String,
    val linkAttributeName: String,
    val companionDescription: String,
) : Comparable<CompanionTransactionRule> {
    override fun compareTo(other: CompanionTransactionRule): Int =
        compareValuesBy(
            this,
            other,
            { it.name },
            { it.matchAttributeName },
            { it.matchValuePattern },
            { it.linkAttributeName },
            { it.companionDescription },
        )
}

/** Serializes companion-rule lists sorted by natural order — each rule is matched independently, so list order carries no meaning. */
object SortedCompanionTransactionRuleListSerializer : SortedListSerializer<CompanionTransactionRule>(CompanionTransactionRule.serializer())
