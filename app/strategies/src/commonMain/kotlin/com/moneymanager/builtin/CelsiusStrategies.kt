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
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Celsius CSV strategy. */
object CelsiusStrategies {
    private val celsiusCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000017")

    /** The name Koinly and Accointing give this wallet, so linking their shadow wallets to it finds it by name. */
    private const val CELSIUS_ACCOUNT = "Celsius Network"
    private const val CELSIUS_REWARDS_ACCOUNT = "Celsius Rewards"
    private const val CELSIUS_DEPOSITS_ACCOUNT = "Celsius Deposits"
    private const val CELSIUS_WITHDRAWALS_ACCOUNT = "Celsius Withdrawals"

    // The export's header has a space after each comma, and those spaces are part of the column names.
    private const val CELSIUS_ID_COLUMN = "Internal id"
    private const val CELSIUS_TIME_COLUMN = " Date and time"
    private const val CELSIUS_TYPE_COLUMN = " Transaction type"
    private const val CELSIUS_COIN_COLUMN = " Coin type"
    private const val CELSIUS_AMOUNT_COLUMN = " Coin amount"
    private const val CELSIUS_USD_COLUMN = " USD Value"
    private const val CELSIUS_ORIGINAL_COIN_COLUMN = " Original Reward Coin"
    private const val CELSIUS_ORIGINAL_AMOUNT_COLUMN = " Reward Amount In Original Coin"
    private const val CELSIUS_CONFIRMED_COLUMN = " Confirmed"

    /**
     * Celsius Network's transaction export (`transactions-export-<uuid>.csv`): one row per movement on the one
     * Celsius wallet, with a signed amount, in UTC to the minute. A withdrawal Celsius never confirmed
     * (`Confirmed` = `No`) was retried under a new row, so it stays on record but out of the balance.
     */
    fun buildCelsiusCsvStrategy(now: Instant): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(celsiusCsvStrategyId),
            name = "Celsius CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf(
                            CELSIUS_ID_COLUMN,
                            CELSIUS_TIME_COLUMN,
                            CELSIUS_TYPE_COLUMN,
                            CELSIUS_COIN_COLUMN,
                            CELSIUS_AMOUNT_COLUMN,
                            CELSIUS_USD_COLUMN,
                            CELSIUS_ORIGINAL_COIN_COLUMN,
                            CELSIUS_ORIGINAL_AMOUNT_COLUMN,
                            CELSIUS_CONFIRMED_COLUMN,
                        ),
                    fieldMappings =
                        mapOf(
                            accountFromRules(TransferField.SOURCE_ACCOUNT),
                            accountFromRules(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = CELSIUS_TIME_COLUMN,
                                    dateFormat = "MMMM d, yyyy",
                                    dateTimeFormat = "MMMM d, yyyy h:mm a",
                                ),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf(CELSIUS_TYPE_COLUMN))),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = CELSIUS_AMOUNT_COLUMN,
                                ),
                            TransferField.CURRENCY to
                                CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf(CELSIUS_COIN_COLUMN))),
                            TransferField.TIMEZONE to
                                HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
                        ),
                    rules =
                        listOf(
                            attribute(CELSIUS_ID_COLUMN, "celsius-id", unique = true),
                            attribute(CELSIUS_TYPE_COLUMN, "celsius-type"),
                            attribute(CELSIUS_USD_COLUMN, "celsius-usd-value"),
                            attribute(CELSIUS_ORIGINAL_COIN_COLUMN, "celsius-original-reward-coin"),
                            attribute(CELSIUS_ORIGINAL_AMOUNT_COLUMN, "celsius-original-reward-amount"),
                            excludeWhen(CELSIUS_CONFIRMED_COLUMN, "^No$", "not confirmed by Celsius"),
                            incomingWhenPositive(CELSIUS_AMOUNT_COLUMN),
                            accounts(AccountSide.SOURCE, fixedNaming(CELSIUS_TYPE_COLUMN, CELSIUS_ACCOUNT)),
                            accounts(
                                AccountSide.TARGET,
                                naming(CELSIUS_TYPE_COLUMN, pattern = "Reward$", name = CELSIUS_REWARDS_ACCOUNT),
                                // The export never says where the money came from or went.
                                naming(CELSIUS_TYPE_COLUMN, pattern = "^Transfer$", name = CELSIUS_DEPOSITS_ACCOUNT, unidentified = true),
                                naming(
                                    CELSIUS_TYPE_COLUMN,
                                    pattern = "^Withdrawal$",
                                    name = CELSIUS_WITHDRAWALS_ACCOUNT,
                                    unidentified = true,
                                ),
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
}
