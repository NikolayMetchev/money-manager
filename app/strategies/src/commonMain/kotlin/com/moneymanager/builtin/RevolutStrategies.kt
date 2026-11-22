package com.moneymanager.builtin

import com.moneymanager.builtin.StatementDefaults.QIF_STATEMENT_RECONCILE_WINDOW_SECONDS
import com.moneymanager.builtin.StatementDefaults.STATEMENT_PLACEHOLDER_WINDOW
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Revolut CSV strategy. */
object RevolutStrategies {
    private val revolutCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000001a")

    private const val REVOLUT_STARTED_COLUMN = "Started Date"
    private const val REVOLUT_COMPLETED_COLUMN = "Completed Date"

    /** A column the Revolut strategy's rules write: the row's identity, which the export lacks. */
    private const val REVOLUT_ROW_ID_COLUMN = "Row ID"

    /** The one account every Revolut currency balance lives in (an account holds one balance per currency). */
    private const val REVOLUT_ACCOUNT = "Revolut"

    /** The leg group of a currency exchange, whose two sides sit in two currencies' files. */
    private const val REVOLUT_EXCHANGE_GROUP = "exchange"

    /** The leg group of an exchange row that names no currency. */
    private const val REVOLUT_UNNAMED_EXCHANGE_GROUP = "exchange-unnamed"

    /** The `Type` of a Revolut exchange row. */
    private const val REVOLUT_EXCHANGE_TYPE = "Exchange"

    /** The asset an exchange with no named other side is booked against until a person says what it was. */
    private const val REVOLUT_UNKNOWN_ASSET = "UNKNOWN"

    /** A transfer's description, capturing who the other end is. */
    private const val REVOLUT_COUNTERPARTY_PATTERN = "^(?:(?:International |SWIFT )?Transfer (?:to|from) |To |Payment from )(.+)$"

    /**
     * Built-in strategy for Revolut's account statement export (`account-statement_2018-05-01_2026-11-08_en-gb_3e1b0f.csv`):
     * `Type, Product, Started Date, Completed Date, Description, Amount, Fee, Currency, State, Balance`. One file
     * holds one currency, and `Amount` is signed and excludes `Fee` (the running balance falls by both).
     *
     * Every file books to the one account "Revolut", which holds a balance per currency. The counterparty comes from the description: the
     * name after "To", "Transfer from", "SWIFT Transfer to" or "Payment from"; ATM withdrawals consolidate into the
     * built-in "ATM"; `REVX_TRANSFER` goes to the "Revolut X" account the exchange API strategy creates. A top-up
     * (a card, or open banking) never says where the money came from, so its counterparty is a placeholder that
     * reconciles against the paying bank's record of the same movement.
     *
     * The two sides of a currency exchange are rows in two different files ("Exchanged to USD" out of the GBP
     * balance, the same words into the USD one). A cross-file trade group pairs them by their instant, so an
     * exchange is one trade on the Revolut account whichever file is applied first. Until the other currency's file
     * is staged the row waits, rather than being booked as a transfer the trade would then double.
     *
     * An `Exchange` row that names no currency ("Transfer to Revolut Ltd") has no known other side. It pairs with any row
     * at its instant in another file, and until one turns up it books a trade against an `UNKNOWN` asset (the same number on
     * both sides) tagged `manual-entry`: Manual Entries asks for the real currency and amount, and the real trade replaces
     * it when its other side is staged.
     *
     * The export has no transaction id, but its running balance makes a row unique, so the product, start time, type,
     * description, amount and balance together identify it. A pending row (no balance yet) is skipped until a later
     * export holds it settled; a reverted, declined or failed one is imported excluded.
     */
    fun buildRevolutCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = REVOLUT_STARTED_COLUMN,
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Description", "Type"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Amount",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        val rules =
            listOf(
                rule(
                    RuleAction.SetValue(
                        REVOLUT_ROW_ID_COLUMN,
                        RuleValue.constant(
                            "{Product} {$REVOLUT_STARTED_COLUMN} {Type} {Description} {Currency} {Amount} {Balance}",
                        ),
                    ),
                    name = "Product, start time, type, description, amount and balance identify a row",
                ),
                // Revolut's running balance is in completion order: a card payment started on the 26th and completed on
                // the 28th is in the balance of the 28th, not of the 26th.
                balance("Balance", atColumn = REVOLUT_COMPLETED_COLUMN),
                attribute(REVOLUT_ROW_ID_COLUMN, "revolut-row-id", unique = true),
                attribute("Type", "revolut-type"),
                rule(
                    RuleAction.Skip,
                    conditions = listOf(Condition("State", ConditionOp.EQUALS, value = "PENDING")),
                    name = "A pending row has no balance yet: the next export holds it settled",
                ),
                rule(
                    RuleAction.Skip,
                    conditions =
                        listOf(
                            Condition("Type", ConditionOp.EQUALS, value = REVOLUT_EXCHANGE_TYPE),
                            Condition("State", ConditionOp.NOT_EQUALS, value = "COMPLETED"),
                        ),
                    name = "An exchange that did not complete is no trade, and half of one would never pair",
                ),
                rule(
                    RuleAction.Exclude(RuleValue.constant("Revolut state {State}")),
                    conditions = listOf(Condition("State", ConditionOp.NOT_EQUALS, value = "COMPLETED")),
                    name = "Money that never moved",
                ),
                incomingWhenPositive("Amount"),
                rule(
                    RuleAction.Leg(REVOLUT_EXCHANGE_GROUP, LegRole.BySign("Amount")),
                    conditions = listOf(Condition("Description", ConditionOp.STARTS_WITH, value = "Exchanged to ")),
                    name = "Both sides of an exchange are legs of one trade",
                ),
                // Claimed second, as the first leg claim that resolves wins: an exchange row that names no currency pairs with
                // whichever other row sits at its instant, or waits in Manual Entries for a person to say what it was.
                rule(
                    RuleAction.Leg(REVOLUT_UNNAMED_EXCHANGE_GROUP, LegRole.BySign("Amount")),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = REVOLUT_EXCHANGE_TYPE)),
                    name = "An exchange row that names no currency is a leg of a trade with an unknown other side",
                ),
                fee(FeeRule(amount = ValueExpr.of("Fee")), conditions = listOf(Condition("Fee", ConditionOp.POSITIVE))),
                wholeFeeWhen("Amount", "Type", "^Fee$"),
                accounts(AccountSide.SOURCE, fixedNaming("Currency", REVOLUT_ACCOUNT)),
                accounts(
                    AccountSide.TARGET,
                    fixedNaming("Description", REVOLUT_ACCOUNT),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = REVOLUT_EXCHANGE_TYPE)),
                ),
                accounts(
                    AccountSide.TARGET,
                    builtInNaming("Description", "ATM"),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = "ATM")),
                ),
                accounts(
                    AccountSide.TARGET,
                    fixedNaming("Description", "Revolut X"),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = "REVX_TRANSFER")),
                ),
                accounts(
                    AccountSide.TARGET,
                    naming("Description", pattern = "^Top-up by \\*(\\d+)$", name = "Card *$1", unidentified = true),
                    naming("Description", pattern = "^Open banking top-up$", name = "Open banking top-up", unidentified = true),
                    naming("Description", pattern = REVOLUT_COUNTERPARTY_PATTERN, name = "$1", trim = true),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = "Topup")),
                ),
                accounts(AccountSide.TARGET, naming("Description", pattern = REVOLUT_COUNTERPARTY_PATTERN, name = "$1", trim = true)),
                accounts(AccountSide.TARGET, naming("Description", "Type")),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(revolutCsvStrategyId),
            name = "Revolut CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf(
                            "Type",
                            "Product",
                            REVOLUT_STARTED_COLUMN,
                            REVOLUT_COMPLETED_COLUMN,
                            "Description",
                            "Amount",
                            "Fee",
                            "Currency",
                            "State",
                            "Balance",
                        ),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = REVOLUT_EXCHANGE_GROUP,
                                // Both sides read "Exchanged to <the currency bought>", which tells two exchanges a second apart.
                                key = listOf(ValueExpr.of("Description")),
                                // Revolut stamps both sides of an exchange with the same second.
                                windowSeconds = 1,
                                assembly = LegAssembly.Trade(description = "Exchange {from} to {to}", acrossFiles = true),
                            ),
                            LegGroupRule(
                                name = REVOLUT_UNNAMED_EXCHANGE_GROUP,
                                windowSeconds = 1,
                                assembly =
                                    LegAssembly.Trade(
                                        description = "Exchange {from} to {to}",
                                        acrossFiles = true,
                                        unpairedAsset = REVOLUT_UNKNOWN_ASSET,
                                    ),
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
