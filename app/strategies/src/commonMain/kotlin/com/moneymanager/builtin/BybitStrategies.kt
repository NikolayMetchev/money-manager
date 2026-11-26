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
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Bybit spot, funding and unified CSV strategies. */
object BybitStrategies {
    private val bybitUnifiedCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000010")

    private val bybitFundingCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000f")

    private val bybitSpotCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")

    /**
     * The one Bybit account, named as the Bybit API strategy's synthetic account. Bybit splits a user's
     * holdings across Spot, Funding, Unified Trading and Earn wallets and exports one ledger per wallet,
     * but the API treats them as a single balance, so every ledger books against this account too —
     * that is what lets either source reconcile against the other.
     */
    private const val BYBIT_ACCOUNT = "Bybit"

    /** Placeholder counterparty for a deposit or withdrawal, as `BINANCE_FUNDING_ACCOUNT`. */
    private const val BYBIT_FUNDING_ACCOUNT = "Bybit Funding"
    private const val BYBIT_DERIVATIVES_ACCOUNT = "Bybit Derivatives"

    /**
     * Counterparty for a move between two of the user's own Bybit wallets. Both ends are [BYBIT_ACCOUNT],
     * so such a move changes nothing; each ledger still records its half, which is booked here and
     * excluded (see [BYBIT_INTERNAL_TRANSFER_EXCLUSION]) — kept visible, counted nowhere.
     */
    private const val BYBIT_INTERNAL_TRANSFERS_ACCOUNT = "Bybit Internal Transfers"

    /** Suspense for a row the strategies do not model; the role of `BINANCE_TRADING_ACCOUNT`. */
    private const val BYBIT_TRADING_ACCOUNT = "Bybit Trading"

    /** The `excluded` value a wallet-to-wallet move carries; a re-import only lifts this value. */
    private const val BYBIT_INTERNAL_TRANSFER_EXCLUSION = "Bybit internal wallet transfer"

    /**
     * Transfer reconciliation window against the Bybit API. The two sources stamp a withdrawal a couple
     * of seconds apart (`createTime` vs the ledger's booking); an hour leaves room for settlement lag the
     * way `BINANCE_RECONCILE_WINDOW_SECONDS` does, without reaching a different day's movement.
     */
    private const val BYBIT_RECONCILE_WINDOW_SECONDS = 3600L

    /** Trade reconciliation window; see `BINANCE_TRADE_RECONCILE_WINDOW_SECONDS` for why it is tight. */
    private const val BYBIT_TRADE_RECONCILE_WINDOW_SECONDS = 5L

    /** Every Bybit ledger names a row's kind in this column. */
    private const val BYBIT_TYPE_COLUMN = "Type"

    /** The Spot ledger's leg group folding a fill's rows into one trade. */
    private const val BYBIT_TRADE_GROUP = "trade"

    /** Every Bybit ledger stamps rows `yyyy-MM-dd HH:mm:ss` in UTC. */
    private fun bybitTimestamp(columnName: String) =
        DateTimeParsingMapping(
            fieldType = TransferField.TIMESTAMP,
            dateColumnName = columnName,
            dateFormat = "yyyy-MM-dd",
            dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
        )

    /** The field mappings every Bybit ledger shares, with a signed [amountColumn]. */
    private fun bybitFieldMappings(
        timeColumn: String,
        amountColumn: String,
        coinColumn: String,
    ) = mapOf(
        accountFromRules(TransferField.SOURCE_ACCOUNT),
        accountFromRules(TransferField.TARGET_ACCOUNT),
        TransferField.TIMESTAMP to bybitTimestamp(timeColumn),
        TransferField.DESCRIPTION to
            DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf(BYBIT_TYPE_COLUMN, coinColumn))),
        TransferField.AMOUNT to
            AmountParsingMapping(
                fieldType = TransferField.AMOUNT,
                mode = AmountMode.SINGLE_COLUMN,
                amountColumnName = amountColumn,
            ),
        TransferField.CURRENCY to CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf(coinColumn))),
        TransferField.TIMEZONE to HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
    )

    /** A deposit/withdrawal: the export never says where the money came from or went. */
    private fun bybitFunding(pattern: String) =
        naming(BYBIT_TYPE_COLUMN, pattern = pattern, name = BYBIT_FUNDING_ACCOUNT, unidentified = true)

    /**
     * The rules every Bybit ledger shares: its attributes (plus [extraAttributes]) and the exclusion of
     * wallet-to-wallet moves, positive [amountColumn] rows arriving into Bybit, the Bybit account on one side
     * and [targets] over the `Type` column on the other.
     */
    private fun bybitRules(
        amountColumn: String,
        internalTransferPattern: String,
        targets: Array<AccountNaming>,
        extraAttributes: List<StrategyRule> = emptyList(),
    ) = listOf(
        attribute("Uid", "bybit-uid"),
        attribute(BYBIT_TYPE_COLUMN, "bybit-type"),
        excludeWhen(BYBIT_TYPE_COLUMN, internalTransferPattern, BYBIT_INTERNAL_TRANSFER_EXCLUSION),
    ) + extraAttributes +
        listOf(
            incomingWhenPositive(amountColumn),
            accounts(AccountSide.SOURCE, fixedNaming("Uid", BYBIT_ACCOUNT), naming("Uid")),
            // A pattern resolves nothing for a blank cell (a Spot Convert leg's Type), so a blank Type is routed
            // to the trade suspense explicitly; its group normally assembles into a trade anyway.
            accounts(
                AccountSide.TARGET,
                fixedNaming("Uid", BYBIT_TRADING_ACCOUNT),
                naming("Uid"),
                conditions = listOf(Condition(BYBIT_TYPE_COLUMN, ConditionOp.BLANK)),
            ),
            accounts(AccountSide.TARGET, *targets, naming(BYBIT_TYPE_COLUMN)),
        )

    /**
     * Bybit's newer exports carry a `Uid` column (and open with a `UID: …` preamble line the CSV parser
     * skips). The 2023-era exports of the same ledgers (`spot_part-*.csv`, `Bybit-UM-TransactionLog-*`)
     * hold the very same rows without it; requiring a numeric Uid keeps them from resolving to these
     * strategies and booking every movement twice, exactly as the Binance strategy does with `User_ID`.
     */
    private val bybitUidRule = Condition("Uid", ConditionOp.MATCHES, "^\\s*\\d+\\s*$")

    /**
     * Bybit's Spot-wallet ledger (`AssetChangeDetails_spot_*`). One row per balance change, typed by
     * `Type`: `trade` fills and their `tradingFee` rows, `userDeposit`, and `internalAccountTransfer*`
     * moves to and from the other wallets. A Convert leaves `Type` blank on both of its legs.
     *
     * A trade is split into one row per fill per leg, every row of an order stamped with the same second
     * (a convert's two legs may straddle a second boundary). A trade [LegGroupRule] folds
     * each group into one `trade`, classifying legs by the sign of `Amount`; fees stay their own
     * transfers to the Bybit account's fee account, which is also where the API books them.
     *
     * Deposits book against the [BYBIT_FUNDING_ACCOUNT] placeholder so they reconcile against the API's
     * record of the same deposit, which names the on-chain address when it has one.
     */
    fun buildBybitSpotCsvStrategy(now: Instant): CsvImportStrategy {
        val internalTransfer = "^internalAccountTransfer"
        val targets =
            arrayOf(
                bybitFunding("^user(Deposit|Withdraw)"),
                naming(BYBIT_TYPE_COLUMN, pattern = internalTransfer, name = BYBIT_INTERNAL_TRANSFERS_ACCOUNT),
                // Trade legs only reach here when their group did not resolve.
                fixedNaming(BYBIT_TYPE_COLUMN, BYBIT_TRADING_ACCOUNT),
            )
        val rules =
            bybitRules("Amount", internalTransfer, targets) +
                wholeFeeWhen("Amount", BYBIT_TYPE_COLUMN, "^tradingFee$") +
                // An empty Type is a Convert leg; the pattern has to admit it explicitly.
                rule(
                    RuleAction.Leg(BYBIT_TRADE_GROUP, LegRole.BySign("Amount")),
                    conditions = listOf(Condition("Type", ConditionOp.MATCHES, "^(trade)?$")),
                )
        return CsvImportStrategy(
            id = CsvImportStrategyId(bybitSpotCsvStrategyId),
            name = "Bybit Spot CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = setOf("Uid", "Type", "Coin", "Amount", "Wallet Balance", "Time(UTC)"),
                    fieldMappings = bybitFieldMappings("Time(UTC)", "Amount", "Coin"),
                    rules = rules,
                    contentMatchRules = listOf(bybitUidRule),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(BYBIT_RECONCILE_WINDOW_SECONDS),
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = BYBIT_TRADE_GROUP,
                                windowSeconds = 1,
                                assembly = LegAssembly.Trade(),
                                reconcileWindowSeconds = BYBIT_TRADE_RECONCILE_WINDOW_SECONDS,
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Bybit's Funding-wallet ledger (`AssetChangeDetails_fund_*`): deposits, withdrawals and transfers to
     * and from the other wallets, whose `Description` names the far wallet ("Transfer to Spot Account").
     *
     * A `Withdraw` row is **gross** — the coins that left plus the network fee — while the API books the
     * net amount and the fee as two transfers. The engine reconciles the one gross row against that pair.
     */
    fun buildBybitFundingCsvStrategy(now: Instant): CsvImportStrategy {
        val internalTransfer = "^Transfer (in|out)$"
        val targets =
            arrayOf(
                bybitFunding("^(Deposit|Withdraw)$"),
                naming(BYBIT_TYPE_COLUMN, pattern = internalTransfer, name = BYBIT_INTERNAL_TRANSFERS_ACCOUNT),
                fixedNaming(BYBIT_TYPE_COLUMN, BYBIT_TRADING_ACCOUNT),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(bybitFundingCsvStrategyId),
            name = "Bybit Funding CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf("Uid", "Date & Time(UTC)", "Coin", "QTY", "Type", "Account Balance", "Description"),
                    fieldMappings = bybitFieldMappings("Date & Time(UTC)", "QTY", "Coin"),
                    rules = bybitRules("QTY", internalTransfer, targets, listOf(attribute("Description", "bybit-description"))),
                    contentMatchRules = listOf(bybitUidRule),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(BYBIT_RECONCILE_WINDOW_SECONDS),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Bybit's Unified Trading Account ledger (`AssetChangeDetails_uta_*`). `Change` is what the wallet
     * actually moved (cash flow + funding − fee). Transfers to and from the other wallets are excluded
     * like the other ledgers'; everything else is derivatives profit and loss, booked against
     * [BYBIT_DERIVATIVES_ACCOUNT] as the API's transaction-log endpoints do.
     */
    fun buildBybitUnifiedCsvStrategy(now: Instant): CsvImportStrategy {
        val internalTransfer = "^TRANSFER_(IN|OUT)$"
        val targets =
            arrayOf(
                naming(BYBIT_TYPE_COLUMN, pattern = internalTransfer, name = BYBIT_INTERNAL_TRANSFERS_ACCOUNT),
                fixedNaming(BYBIT_TYPE_COLUMN, BYBIT_DERIVATIVES_ACCOUNT),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(bybitUnifiedCsvStrategyId),
            name = "Bybit Unified CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf(
                            "Uid",
                            "Currency",
                            "Contract",
                            "Type",
                            "Direction",
                            "Quantity",
                            "Position",
                            "Filled Price",
                            "Funding",
                            "Fee Paid",
                            "Cash Flow",
                            "Change",
                            "Wallet Balance",
                            "Action",
                            "Time(UTC)",
                        ),
                    fieldMappings = bybitFieldMappings("Time(UTC)", "Change", "Currency"),
                    rules = bybitRules("Change", internalTransfer, targets, listOf(attribute("Contract", "bybit-contract"))),
                    contentMatchRules = listOf(bybitUidRule),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(BYBIT_RECONCILE_WINDOW_SECONDS),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
