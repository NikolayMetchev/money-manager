package com.moneymanager.builtin

import com.moneymanager.builtin.BuiltInCsvStrategies.marcusCsvStrategyId
import com.moneymanager.builtin.StatementDefaults.QIF_STATEMENT_RECONCILE_WINDOW_SECONDS
import com.moneymanager.builtin.StatementDefaults.STATEMENT_PLACEHOLDER_WINDOW
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/** Built-in Marcus CSV strategy. */
object MarcusStrategies {
    private const val MARCUS_DATE_COLUMN = "TransactionDate"
    private const val MARCUS_DESCRIPTION_COLUMN = "Description"
    private const val MARCUS_VALUE_COLUMN = "Value"
    private const val MARCUS_BALANCE_COLUMN = "AccountBalance"
    private const val MARCUS_ACCOUNT_NUMBER_COLUMN = "AccountNumber"

    /** A column the Marcus strategy's rules write: the row's identity, which the export lacks. */
    private const val MARCUS_ROW_ID_COLUMN = "Row ID"

    /** A Marcus transfer's description, capturing the other account's number. */
    private const val MARCUS_TRANSFER_PATTERN = "^(?:Transfer from|Withdrawal to)\\s+(\\d+)$"

    /** The Marcus account when no account carries its number yet. */
    private const val MARCUS_ACCOUNT = "Marcus"

    /** Who pays a Marcus account its interest. */
    private const val MARCUS_INTEREST_ACCOUNT = "Marcus Interest"

    /**
     * Built-in strategy for Marcus by Goldman Sachs savings exports (`Transactions 40001234 2020-05-10 11_04.csv`):
     * `TransactionDate, Description, Value, AccountBalance, AccountName, AccountNumber`, one signed GBP row per
     * movement, dated without a time.
     *
     * Marcus names both ends only by account number: its own in `AccountNumber`, the other in the description
     * ("Transfer from 40005678", "Withdrawal to 40005678"). Each resolves to the account carrying that
     * `account-account-number` attribute, the identity a bank feed (Monzo's API) gives the accounts it pays, so a
     * transfer Monzo and Marcus both record lands on the same two accounts and reconciles, in either import order.
     * A number no account carries yet names its own account, which a person can map onto theirs.
     *
     * The export has no transaction id, but its running balance makes each row unique, and overlapping exports
     * repeat a row exactly, so the account, date, amount and balance together are the row's
     * identity (two accounts can share the other three).
     */
    fun buildMarcusCsvStrategy(
        now: Instant,
        currencyId: CurrencyId,
    ): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(marcusCsvStrategyId),
            name = "Marcus CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf(
                            MARCUS_DATE_COLUMN,
                            MARCUS_DESCRIPTION_COLUMN,
                            MARCUS_VALUE_COLUMN,
                            MARCUS_BALANCE_COLUMN,
                            "AccountName",
                            MARCUS_ACCOUNT_NUMBER_COLUMN,
                        ),
                    fieldMappings =
                        mapOf(
                            accountFromRules(TransferField.SOURCE_ACCOUNT),
                            accountFromRules(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = MARCUS_DATE_COLUMN,
                                    dateFormat = "yyyyMMdd",
                                ),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr.of(MARCUS_DESCRIPTION_COLUMN)),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = MARCUS_VALUE_COLUMN,
                                ),
                            TransferField.CURRENCY to HardCodedCurrencyMapping(fieldType = TransferField.CURRENCY, currencyId = currencyId),
                            TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
                        ),
                    rules =
                        listOf(
                            rule(
                                RuleAction.SetValue(
                                    MARCUS_ROW_ID_COLUMN,
                                    RuleValue.constant(
                                        "{$MARCUS_ACCOUNT_NUMBER_COLUMN} {$MARCUS_DATE_COLUMN} {$MARCUS_VALUE_COLUMN} {$MARCUS_BALANCE_COLUMN}",
                                    ),
                                ),
                                name = "Account, date, amount and balance identify a row",
                            ),
                            attribute(MARCUS_ROW_ID_COLUMN, "marcus-row-id", unique = true),
                            incomingWhenPositive(MARCUS_VALUE_COLUMN),
                            balance(MARCUS_BALANCE_COLUMN),
                            accounts(
                                AccountSide.SOURCE,
                                naming(
                                    MARCUS_ACCOUNT_NUMBER_COLUMN,
                                    attributeTypeName = WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_NAME,
                                ),
                                fixedNaming(MARCUS_ACCOUNT_NUMBER_COLUMN, MARCUS_ACCOUNT),
                            ),
                            accounts(
                                AccountSide.TARGET,
                                naming(
                                    MARCUS_DESCRIPTION_COLUMN,
                                    pattern = MARCUS_TRANSFER_PATTERN,
                                    attributeTypeName = WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_NAME,
                                ),
                                naming(MARCUS_DESCRIPTION_COLUMN, pattern = MARCUS_TRANSFER_PATTERN, name = "Account $1"),
                                naming(MARCUS_DESCRIPTION_COLUMN, pattern = "^Interest\\b", name = MARCUS_INTEREST_ACCOUNT),
                                naming(MARCUS_DESCRIPTION_COLUMN),
                            ),
                        ),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(QIF_STATEMENT_RECONCILE_WINDOW_SECONDS),
                    placeholderReconcileWindow = STATEMENT_PLACEHOLDER_WINDOW,
                ),
            createdAt = now,
            updatedAt = now,
        )
}
