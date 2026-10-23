package com.moneymanager.database

import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Built-in strategies are no longer seeded; installing them through the engine (the same
// path a catalog install takes) must survive the JSON round trip through the database.
class BuiltInCsvStrategyInstallTest : DbTest() {
    @Test
    fun `installing the built-in Wise CSV strategy round-trips through the database`() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Wise CSV" }

            // The strategy auto-matches Wise's transaction-history.csv header
            val wiseHeader =
                setOf(
                    "ID",
                    "Status",
                    "Direction",
                    "Created on",
                    "Finished on",
                    "Source fee amount",
                    "Source fee currency",
                    "Target fee amount",
                    "Target fee currency",
                    "Source name",
                    "Source amount (after fees)",
                    "Source currency",
                    "Target name",
                    "Target amount (after fees)",
                    "Target currency",
                    "Exchange rate",
                    "Reference",
                    "Batch",
                    "Created by",
                    "Category",
                    "Note",
                )
            assertTrue(strategy.matchesColumns(wiseHeader))

            // The mapping types and rules survive the JSON round trip through the database
            val rules = strategy.config.rules
            assertIs<AccountFromRulesMapping>(strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT])
            val source = rules.namings(AccountSide.SOURCE).single()
            assertEquals("Wise: {value}", source.name)
            assertEquals("Source currency", source.value.primaryPath)

            assertIs<AccountFromRulesMapping>(strategy.config.fieldMappings[TransferField.TARGET_ACCOUNT])
            assertTrue(
                rules.first { it.namings(AccountSide.TARGET).isNotEmpty() }.conditions.isNotEmpty(),
                "the guarded template rule comes first",
            )

            val timestamp = strategy.config.fieldMappings[TransferField.TIMESTAMP]
            assertIs<DateTimeParsingMapping>(timestamp)
            assertEquals("yyyy-MM-dd HH:mm:ss", timestamp.dateTimeFormat)

            // OUT rows add the source fee to the debit (the amount column is net of fees)
            assertEquals(
                "Source fee amount",
                rules
                    .actions<RuleAction.Fee>()
                    .single()
                    .fee.amount.primaryPath,
            )

            val swapRule = rules.single { RuleAction.Flip in it.actions }
            assertEquals(3, swapRule.actions.filterIsInstance<RuleAction.SwapValues>().size)

            // The Wise transaction ID drives duplicate detection on re-import
            assertTrue(rules.idAttribute("wise-id").uniqueIdentifier)
            assertEquals(RuleValue.read("ID"), rules.idAttribute("wise-id").value)

            // Assets fees require a manually entered interest transfer (companion rule
            // survives the JSON round trip through the database)
            val companionRule = strategy.config.companionTransactionRules.single()
            assertEquals("Interest earned", companionRule.name)
            assertEquals("wise-id", companionRule.matchAttributeName)
            assertEquals("ACCRUAL_CHARGE-%", companionRule.matchValuePattern)
            assertEquals("wise-interest-for", companionRule.linkAttributeName)
            assertEquals("Interest earned", companionRule.companionDescription)
        }

    @Test
    fun `installing the built-in Monzo CSV strategy round-trips through the database`() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo CSV" }

            // The strategy auto-matches Monzo's transaction export header
            val monzoHeader =
                setOf(
                    "Transaction ID",
                    "Date",
                    "Time",
                    "Type",
                    "Name",
                    "Emoji",
                    "Category",
                    "Amount",
                    "Currency",
                    "Local amount",
                    "Local currency",
                    "Notes and #tags",
                    "Address",
                    "Receipt",
                    "Description",
                    "Category split",
                    "Money Out",
                    "Money In",
                )
            assertTrue(strategy.matchesColumns(monzoHeader))

            // No source account mapping is defined (account ids are database-specific);
            // the user picks the Monzo account when applying the strategy
            assertNull(strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT])

            // Positive amounts flow INTO the account, so credits flip source/target
            assertTrue(strategy.config.rules.isIncomingWhenPositive("Amount"))

            // The Monzo transaction ID drives duplicate detection on re-import
            val idAttribute = strategy.config.rules.idAttribute("Monzo Transaction ID")
            assertTrue(idAttribute.uniqueIdentifier)
            assertEquals(RuleValue.read("Transaction ID"), idAttribute.value)
        }

    @Test
    fun `installing the built-in Binance CSV strategy round-trips through the database`() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            val strategy =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Binance CSV" }

            // The modern export's header, and only it: the legacy one lacks User_ID.
            assertTrue(
                strategy.matchesColumns(setOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")),
            )
            assertTrue(
                !strategy.matchesColumns(setOf("UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")),
                "the legacy 6-column header is not an exact match",
            )
            // The content rule is what keeps a legacy file out via the tolerant subset path.
            assertEquals(
                "User_ID",
                strategy.config.contentMatchRules
                    .single()
                    .path,
            )

            // Trade-group assembly survives the round trip - without it the export's trade rows would
            // import as suspense transfers instead of trades.
            val rules = strategy.config.rules
            val (conversion, tradeGroup) = strategy.config.legGroups
            assertIs<LegAssembly.Trade>(tradeGroup.assembly)
            val tradeLegRule = rules.single { rule -> rule.legs().any { it.group == tradeGroup.name } }
            assertEquals("Operation", tradeLegRule.conditions.single().path)
            assertEquals(LegRole.BySign("Change"), tradeLegRule.legs().single().role, "the ambiguous leg name is resolved by sign")
            assertEquals(0L, tradeGroup.windowSeconds)

            // So does the dust conversion rule, including the sign-based side classification.
            assertIs<LegAssembly.ThroughAccount>(conversion.assembly)
            val dustLegRule = rules.single { rule -> rule.legs().any { it.group == conversion.name } }
            assertEquals("Binance Conversions", dustLegRule.namings(AccountSide.INTERMEDIATE).single().name)
            assertEquals(LegRole.BySign("Change"), dustLegRule.legs().single().role, "both dust legs share one Operation")

            // The Operation column routes every row's counterparty, and the funding rules survive as
            // unidentified placeholders — which is what lets a deposit reconcile against the API's
            // record of it, since the API names the on-chain address the export cannot.
            assertIs<AccountFromRulesMapping>(strategy.config.fieldMappings[TransferField.TARGET_ACCOUNT])
            val targets = rules.namings(AccountSide.TARGET)
            assertTrue(targets.all { it.value.primaryPath == "Operation" })
            assertTrue(
                targets.first { it.name == "Binance Funding" }.unidentified,
                "a deposit/withdrawal counterparty is a placeholder, not an identity",
            )

            assertTrue(rules.isIncomingWhenPositive("Change"), "a positive Change arrives into the Binance account")

            val timestamp = strategy.config.fieldMappings[TransferField.TIMESTAMP]
            assertIs<DateTimeParsingMapping>(timestamp)
            assertEquals("yyyy-MM-dd HH:mm:ss", timestamp.dateTimeFormat)
        }
}

private fun StrategyRule.namings(side: AccountSide): List<AccountNaming> =
    actions.filterIsInstance<RuleAction.Account>().filter { it.side == side }.map { it.naming }

private fun List<StrategyRule>.namings(side: AccountSide): List<AccountNaming> = flatMap { it.namings(side) }

private fun StrategyRule.legs(): List<RuleAction.Leg> = actions.filterIsInstance<RuleAction.Leg>()

private inline fun <reified A : RuleAction> List<StrategyRule>.actions(): List<A> = flatMap { it.actions.filterIsInstance<A>() }

private fun List<StrategyRule>.idAttribute(typeName: String): RuleAction.Attribute =
    actions<RuleAction.Attribute>().single { it.typeName == typeName }

/** Whether a rule says a positive [amountColumn] is money coming in. */
private fun List<StrategyRule>.isIncomingWhenPositive(amountColumn: String): Boolean =
    any { rule ->
        rule.conditions == listOf(Condition(amountColumn, ConditionOp.POSITIVE)) && RuleAction.SetDirection(incoming = true) in rule.actions
    }
