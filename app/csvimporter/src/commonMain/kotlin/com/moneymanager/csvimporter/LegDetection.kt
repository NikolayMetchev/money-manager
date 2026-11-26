package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome

/** Separator joining the parts of a conversion pairing key; a control char that won't appear in data. */
private const val PAIRING_KEY_SEPARATOR = "\u0000"

/** A detected leg, plus the intermediate account a through-account leg routes via. */
internal data class LegDetection(
    val leg: GroupLeg,
    val throughAccountName: String?,
)

/**
 * The leg this row is: the first of the rules' leg claims whose group exists and whose role resolves,
 * and — for a through-account group whose rules name an intermediate account — whose intermediate
 * account resolves. Null when no claim does.
 */
internal fun detectGroupLeg(
    config: CsvStrategyConfig<*>,
    accounts: StatementAccounts,
    rules: RuleEvaluator,
    outcome: RuleOutcome,
    values: RowValues,
): LegDetection? {
    val trimmed by lazy { values.trimmedRecord }
    return outcome.legs.firstNotNullOfOrNull { claim ->
        val index = config.legGroups.indexOfFirst { it.name == claim.group }
        val group = config.legGroups.getOrNull(index) ?: return@firstNotNullOfOrNull null
        val side =
            when (val role = claim.role) {
                LegRole.Debit -> {
                    GroupLegSide.DEBIT
                }

                LegRole.Credit -> {
                    GroupLegSide.CREDIT
                }

                LegRole.Member -> {
                    GroupLegSide.MEMBER
                }

                is LegRole.BySign -> {
                    val amount =
                        values.record
                            .text(role.path)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { parseBigDecimal(it) }.getOrNull() }
                    when {
                        amount == null -> null
                        amount < BigDecimal.ZERO -> GroupLegSide.DEBIT
                        amount > BigDecimal.ZERO -> GroupLegSide.CREDIT
                        else -> null
                    }
                }
            } ?: return@firstNotNullOfOrNull null
        // A member has no side, so only a group that links its rows can use it.
        if (side == GroupLegSide.MEMBER && group.assembly !is LegAssembly.Linked) return@firstNotNullOfOrNull null
        val intermediates = outcome.accountsFor(AccountSide.INTERMEDIATE)
        val throughAccountName =
            when (group.assembly) {
                is LegAssembly.Trade, is LegAssembly.Linked -> {
                    null
                }

                is LegAssembly.ThroughAccount -> {
                    if (intermediates.isEmpty()) {
                        null
                    } else {
                        intermediates
                            .firstNotNullOfOrNull { accounts.applyAccountNaming(it, values) }
                            ?.accountName
                            ?.takeIf { it.isNotBlank() }
                            ?: return@firstNotNullOfOrNull null
                    }
                }
            }
        val funding =
            when (val assembly = group.assembly) {
                is LegAssembly.ThroughAccount -> assembly.fundingWhen
                is LegAssembly.Linked -> assembly.anchorWhen
                is LegAssembly.Trade -> emptyList()
            }.takeIf { it.isNotEmpty() }?.let { rules.all(it, trimmed) } == true
        val key =
            group.key.joinToString(
                PAIRING_KEY_SEPARATOR,
            ) { part -> rules.resolve(part) { values.record.text(it)?.trim() } }
        LegDetection(GroupLeg(index, side, key, funding), throughAccountName)
    }
}
