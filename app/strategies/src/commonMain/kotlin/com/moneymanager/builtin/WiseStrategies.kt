package com.moneymanager.builtin

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Wise CSV strategy. */
object WiseStrategies {
    private val wiseCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000003")

    /** The one account every Wise currency balance lives in (an account holds one balance per currency). */
    private const val WISE_ACCOUNT = "Wise"

    /** Wise itself as a counterparty: the account its fees go to and its interest comes from. */
    private const val WISE_INSTITUTION_ACCOUNT = "TransferWise"

    /** Columns the Wise strategy's rules write: a balance-to-balance conversion's credited currency and amount. */
    private const val WISE_TO_CURRENCY_COLUMN = "Conversion currency"
    private const val WISE_TO_AMOUNT_COLUMN = "Conversion amount"

    /**
     * Builds the built-in Wise CSV import strategy for Wise's transaction-history.csv export.
     *
     * Each row carries both sides of the transaction (Source and Target columns) and a Direction
     * column saying which side is the user's Wise balance. A row preprocessing rule swaps the
     * Source/Target values for IN rows so all field mappings can read the Source-side columns,
     * then flips the resolved accounts. Every row books to the one account "Wise", which holds a
     * balance per currency, as Revolut's does. A balance-to-balance conversion (same name on both
     * sides, different currencies) is a trade inside that account: the source currency out, the
     * target currency in. Wise itself as the counterparty ("Wise", paying interest) is the
     * "TransferWise" account its fees already go to, so it never collides with the own account.
     *
     * Wise's export omits interest-earned rows, but each monthly "Assets fee" (wise-id like
     * `ACCRUAL_CHARGE-…`) implies one, so a manual-entry rule asks for the mirroring interest
     * transfer.
     */
    fun buildWiseCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Created on",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(
                        fieldType = TransferField.DESCRIPTION,
                        value = ValueExpr(listOf("Reference", "Note", "Category", "Target name")),
                    ),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Source amount (after fees)",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Source currency"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
                // Blank except on a conversion, whose rule fills them in: only that row is a trade.
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.TO_AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = WISE_TO_AMOUNT_COLUMN,
                    ),
                TransferField.TO_CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.TO_CURRENCY, value = ValueExpr(listOf(WISE_TO_CURRENCY_COLUMN))),
            )
        val conversion =
            listOf(
                Condition("Source name", ConditionOp.EQUALS_PATH, otherPath = "Target name"),
                Condition("Source name", ConditionOp.NOT_BLANK),
                Condition("Source currency", ConditionOp.NOT_EQUALS_PATH, otherPath = "Target currency"),
            )
        val rules =
            listOf(
                attribute("ID", "wise-id", unique = true),
                attribute("Status", "wise-status"),
                attribute("Direction", "wise-direction"),
                attribute("Exchange rate", "wise-exchange-rate"),
                attribute("Category", "wise-category"),
                attribute("Note", "note"),
                attribute("Created by", "wise-created-by"),
                // An IN row's Wise balance is its Target: swap the sides so every mapping reads the Source
                // columns, then flip the resolved accounts back.
                rule(
                    RuleAction.SwapValues("Source name", "Target name"),
                    RuleAction.SwapValues("Source amount (after fees)", "Target amount (after fees)"),
                    RuleAction.SwapValues("Source currency", "Target currency"),
                    RuleAction.Flip,
                    conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "IN")),
                ),
                accounts(AccountSide.SOURCE, fixedNaming("Source currency", WISE_ACCOUNT)),
                rule(
                    RuleAction.SetValue(WISE_TO_CURRENCY_COLUMN, RuleValue.read("Target currency")),
                    RuleAction.SetValue(WISE_TO_AMOUNT_COLUMN, RuleValue.read("Target amount (after fees)")),
                    conditions = conversion,
                    name = "A balance-to-balance conversion is a trade: the target currency comes in",
                ),
                accounts(AccountSide.TARGET, fixedNaming("Target currency", WISE_ACCOUNT), conditions = conversion),
                accounts(
                    AccountSide.TARGET,
                    fixedNaming("Target name", WISE_INSTITUTION_ACCOUNT),
                    conditions = listOf(Condition("Target name", ConditionOp.EQUALS, value = WISE_ACCOUNT)),
                ),
                accounts(AccountSide.TARGET, AccountNaming(value = ValueExpr.of("Target name", "Source name"))),
                fee(
                    FeeRule(amount = ValueExpr(listOf("Source fee amount"))),
                    conditions = listOf(Condition("Direction", ConditionOp.EQUALS, value = "OUT")),
                ),
                rule(
                    RuleAction.ManualEntry(
                        name = "Interest earned",
                        description = "Interest earned",
                        relationshipType = "interest",
                    ),
                    conditions = listOf(Condition("ID", ConditionOp.STARTS_WITH, value = "ACCRUAL_CHARGE-")),
                ),
            )
        val identificationColumns =
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
        return CsvImportStrategy(
            id = CsvImportStrategyId(wiseCsvStrategyId),
            name = "Wise CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = identificationColumns,
                    fieldMappings = fieldMappings,
                    rules = rules,
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
