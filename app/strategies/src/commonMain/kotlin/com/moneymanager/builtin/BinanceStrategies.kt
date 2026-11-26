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
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Binance CSV strategy. */
object BinanceStrategies {
    private val binanceCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000c")

    /**
     * The single Binance Spot account, holding one balance per asset. Matches the Binance API
     * strategy's synthetic account name, so a movement both sources record resolves to the same
     * account on both sides and cross-source reconciliation links it instead of double-counting.
     */
    private const val BINANCE_ACCOUNT = "Binance"

    /**
     * Placeholder counterparty for a deposit or withdrawal. The export records that money arrived or
     * left but never whose account it was, so this account is not an assertion — it is where an
     * unidentified movement waits to be reconciled against a source that does name the counterparty
     * (the API books a crypto deposit against the on-chain address, falling back to this same name).
     */
    private const val BINANCE_FUNDING_ACCOUNT = "Binance Funding"

    /** Same placeholder role as [BINANCE_FUNDING_ACCOUNT], for the explicitly fiat operations. */
    private const val BINANCE_BANK_ACCOUNT = "Binance Bank"
    private const val BINANCE_EARN_ACCOUNT = "Binance Earn"
    private const val BINANCE_EARN_REWARDS_ACCOUNT = "Binance Earn Rewards"
    private const val BINANCE_DISTRIBUTION_ACCOUNT = "Binance Distribution"

    /**
     * Binance counterparty accounts with no API equivalent — Binance publishes no endpoint for staking,
     * BNB Vault, Launchpool, Launchpad, commission, liquidity farming or dual savings, so these
     * products exist only in the CSV export. Principal pools are kept apart from the income they throw
     * off (and from [BINANCE_EARN_ACCOUNT]) so each balance answers one question: what is staked, and
     * what has it paid out.
     */
    private const val BINANCE_STAKING_ACCOUNT = "Binance Staking"
    private const val BINANCE_STAKING_REWARDS_ACCOUNT = "Binance Staking Rewards"
    private const val BINANCE_VAULT_REWARDS_ACCOUNT = "Binance Vault Rewards"
    private const val BINANCE_LAUNCHPOOL_ACCOUNT = "Binance Launchpool"
    private const val BINANCE_LAUNCHPOOL_REWARDS_ACCOUNT = "Binance Launchpool Rewards"
    private const val BINANCE_LAUNCHPAD_ACCOUNT = "Binance Launchpad"
    private const val BINANCE_COMMISSION_ACCOUNT = "Binance Commission"
    private const val BINANCE_LIQUID_SWAP_ACCOUNT = "Binance Liquid Swap"
    private const val BINANCE_DUAL_SAVINGS_ACCOUNT = "Binance Dual Savings"

    /**
     * Counterparty for Binance's small-assets (dust) sweeps, which arrive as several debited rows and
     * several credited BNB rows that no column attributes to each other. Routing every leg through one
     * account keeps each asset balance exact and isolates the mixed-asset residual, exactly as
     * `CRYPTO_COM_CONVERSIONS_ACCOUNT` does. See [LegAssembly.ThroughAccount].
     */
    private const val BINANCE_CONVERSIONS_ACCOUNT = "Binance Conversions"

    /**
     * Suspense counterparty for a trade leg whose group did not resolve into a trade — a one-sided
     * group, or one naming more than one asset on a side. Reached only in that case (an assembled
     * group's legs become the trade and never a transfer), so a non-zero balance here is a visible
     * signal that an export had a shape the strategy does not model, rather than a silent loss. The
     * account itself is created whenever the file has any trade rows — accounts are resolved while the
     * rows are mapped, before groups are assembled — so an empty one is the normal, healthy state.
     */
    private const val BINANCE_TRADING_ACCOUNT = "Binance Trading"

    /**
     * Cross-source reconciliation window for the Binance CSV strategy's **transfers**. Sized by fiat
     * funding, which is the only thing the two sources disagree about by more than a moment: the API
     * records when Binance credited the deposit, the export when the transfer was initiated, and in
     * this user's data that gap runs from 1 second to about 7 minutes (an hour recovers 19 of 25 such
     * rows; widening to a day recovers no more).
     *
     * It is not wider than that because Binance pays `Staking Rewards` in the same amount every day:
     * at an hour, 65 of 9,303 reward rows have an identical-amount sibling in range, but at a day
     * 4,088 do — a window that size would start pairing genuinely distinct rewards.
     */
    private const val BINANCE_RECONCILE_WINDOW_SECONDS = 3600L

    /**
     * Reconciliation window for Binance **trades and dust sweeps**, which is a different question from
     * the transfer window above: the two sources agree about a conversion's instant to the second and
     * differ only in sub-second rounding. It stays tight because trade reconciliation matches a group
     * against the whole in-window candidate set — at an hour, a later order's fills would be dragged in
     * and the sums would stop matching.
     */
    private const val BINANCE_TRADE_RECONCILE_WINDOW_SECONDS = 5L

    /**
     * Window for pairing a dust sweep's debit legs to its credit legs. Binance stamps a sweep's rows
     * within the same second or the next one, while distinct sweeps are hours apart.
     */
    private const val BINANCE_CONVERSION_PAIRING_WINDOW_SECONDS = 2L

    /**
     * Built-in strategy for Binance's "transaction history" export — one signed row per movement:
     * `User_ID, UTC_Time, Account, Operation, Coin, Change, Remark`.
     *
     * **Modern exports only.** Binance's pre-2022 exports carry the same columns minus `User_ID`, and
     * an older `Operation` vocabulary (`Savings purchase` for `Simple Earn Flexible Subscription`,
     * `POS savings interest` for `Staking Rewards`, `Super BNB Mining` for `BNB Vault Rewards`, …) plus
     * `LD*` mirror rows the modern format dropped. Importing both would book the same event twice under
     * two different descriptions, so [CsvStrategyConfig.contentMatchRules] requires the `User_ID` column: a legacy file
     * scores zero and, because this strategy carries content rules, is also excluded from the
     * no-signals fallback, so it resolves to no strategy and is reported skipped rather than misread.
     * Re-export the same period from Binance to import it.
     *
     * `Operation` drives everything. Deposits, withdrawals, Earn subscriptions and rewards route to the
     * accounts the Binance API strategy also creates, so whichever source imports second reconciles
     * against the first. Staking, BNB Vault, Launchpool, Launchpad, commission, liquidity farming and
     * dual savings have no API endpoint at all and are the reason to import this file.
     *
     * Trades are split across rows: Binance stamps every partial fill of both legs with the same second
     * (a single order can produce a dozen `Transaction Sold`/`Transaction Revenue` rows).
     * A trade [LegGroupRule]
     * folds each such group into one `trade`. Fee rows stay out of the group on purpose — a `trade` row
     * has no fee field — and route to the Binance account's fee account as their own transfers, as the API does.
     *
     * A `Withdraw` row remarked "Withdraw fee is included" asks (a `ManualEntry` rule) for its fee in Manual Entries.
     * Such a row (and every `Fiat Withdrawal`) is the
     * **gross** amount, while the API records the withdrawal net and books the fee as its own transfer.
     * Amount equality can never pair those, so the engine reconciles the gross row against the net +
     * fee pair instead, in either import order.
     *
     * Dust sweeps are the one conversion that cannot be assembled: a sweep debits several assets and
     * credits several BNB amounts, and nothing in the file says which credit came from which debit
     * (their order does not correspond, and the credited amount is net of Binance's service charge
     * while the debited amount is gross). They go through a [LegAssembly.ThroughAccount] rule instead,
     * which keeps every
     * balance exact without inventing a pairing. Both legs share one `Operation`, so a
     * [LegRole.BySign] role classifies them by the sign of `Change`.
     */
    @Suppress("LongMethod")
    fun buildBinanceCsvStrategy(now: Instant): CsvImportStrategy {
        // Every pattern is anchored: account-rule patterns use containsMatchIn, so a bare "Deposit" would
        // also claim "Fiat Deposit" and a bare "Buy" would claim "Transaction Buy".
        val targetAccounts =
            arrayOf(
                // The export says money arrived or left, never whose account it was: a deposit row names
                // no bank and no wallet. The API does know — it books a crypto deposit against the
                // on-chain address it came from ("ETH:0x9b4f…") and only falls back to Binance Funding
                // when there is none. So the counterparty here is a *placeholder*: marking it
                // unidentified lets the engine reconcile the row against the API's record of the same
                // movement whatever counterparty that record names, instead of double-counting it.
                naming(
                    "Operation",
                    pattern = "^(Fiat Deposit|Fiat Withdrawal)$",
                    name = BINANCE_BANK_ACCOUNT,
                    unidentified = true,
                ),
                naming(
                    "Operation",
                    pattern = "^(Deposit|Withdraw)$",
                    name = BINANCE_FUNDING_ACCOUNT,
                    unidentified = true,
                ),
                naming(
                    "Operation",
                    pattern = "^Simple Earn (Flexible|Locked) (Subscription|Redemption)$",
                    name = BINANCE_EARN_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Simple Earn (Flexible (Interest|Airdrop)|Locked Rewards)$",
                    name = BINANCE_EARN_REWARDS_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Staking (Purchase|Redemption)$",
                    name = BINANCE_STAKING_ACCOUNT,
                ),
                naming("Operation", pattern = "^Staking Rewards$", name = BINANCE_STAKING_REWARDS_ACCOUNT),
                naming("Operation", pattern = "^BNB Vault Rewards$", name = BINANCE_VAULT_REWARDS_ACCOUNT),
                naming(
                    "Operation",
                    pattern = "^Launchpool Subscription/Redemption$",
                    name = BINANCE_LAUNCHPOOL_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Launchpool (Interest|Earnings Withdrawal)$",
                    name = BINANCE_LAUNCHPOOL_REWARDS_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Launchpad (Subscribe|Token Distribution)$",
                    name = BINANCE_LAUNCHPAD_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^(Distribution|Rewards Distribution)$",
                    name = BINANCE_DISTRIBUTION_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Commission (History|Rebate)$",
                    name = BINANCE_COMMISSION_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^(Liquid Swap Add|Liquidity Farming Remove)$",
                    name = BINANCE_LIQUID_SWAP_ACCOUNT,
                ),
                naming(
                    "Operation",
                    pattern = "^Dual Savings (Purchase|Settlement)$",
                    name = BINANCE_DUAL_SAVINGS_ACCOUNT,
                ),
                // Dust legs are re-routed to the conversion account by the dust leg rule; this rule is
                // the home for a leg that somehow escapes detection (a zero Change).
                naming(
                    "Operation",
                    pattern = "^Small Assets Exchange BNB( \\(Spot\\))?$",
                    name = BINANCE_CONVERSIONS_ACCOUNT,
                ),
                // Trade legs only reach here when their group did not resolve; and the trailing
                // catch-all keeps a future unknown Operation from minting an account named after it.
                fixedNaming("Operation", BINANCE_TRADING_ACCOUNT),
            )
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.SOURCE_ACCOUNT),
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "UTC_Time",
                        dateFormat = "yyyy-MM-dd",
                        dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                    ),
                TransferField.DESCRIPTION to
                    DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Operation"))),
                TransferField.AMOUNT to
                    AmountParsingMapping(
                        fieldType = TransferField.AMOUNT,
                        mode = AmountMode.SINGLE_COLUMN,
                        amountColumnName = "Change",
                    ),
                TransferField.CURRENCY to
                    CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Coin"))),
                TransferField.TIMEZONE to
                    HardCodedTimezoneMapping(fieldType = TransferField.TIMEZONE, timezoneId = "UTC"),
            )
        val rules =
            listOf(
                attribute("User_ID", "binance-user-id"),
                attribute("Operation", "binance-operation"),
                attribute("Remark", "binance-remark"),
                // The gross row has no fee figure, so a person types it in (Manual Entries); it is booked excluded because
                // the withdrawal already contains it, and it exists so the withdrawal reconciles against net + fee.
                rule(
                    RuleAction.ManualEntry(
                        name = "Withdrawal fee",
                        description = "Withdrawal fee",
                        relationshipType = "fee",
                        excludedFromBalances = true,
                    ),
                    conditions = listOf(Condition("Remark", ConditionOp.MATCHES, "fee is included")),
                ),
                wholeFeeWhen("Change", "Operation", "^(Fee|Transaction Fee)$"),
                // Change carries the direction: negative leaves the Binance account, positive arrives.
                incomingWhenPositive("Change"),
                // Account is "Spot" on every row; keying the naming off it rather than hard-coding an id keeps
                // the mapping portable and leaves room for a future wallet column value.
                accounts(AccountSide.SOURCE, fixedNaming("Account", BINANCE_ACCOUNT), naming("Account")),
                accounts(AccountSide.TARGET, *targetAccounts, naming("Operation")),
                // Dust sweeps: both legs share one Operation, so Change's sign decides the side.
                rule(
                    RuleAction.Leg(BINANCE_DUST_GROUP, LegRole.BySign("Change")),
                    RuleAction.Account(AccountSide.INTERMEDIATE, naming("Operation", name = BINANCE_CONVERSIONS_ACCOUNT)),
                    conditions =
                        listOf(
                            Condition("Operation", ConditionOp.MATCHES, "^Small Assets Exchange BNB( \\(Spot\\))?$"),
                            Condition("Operation", ConditionOp.NOT_BLANK),
                        ),
                ),
                // "Transaction Related" is the older name for *either* leg of a fill, so the sign of Change - not
                // the operation name - has to decide which side each row is.
                rule(
                    RuleAction.Leg(BINANCE_TRADE_GROUP, LegRole.BySign("Change")),
                    conditions =
                        listOf(
                            Condition(
                                "Operation",
                                ConditionOp.MATCHES,
                                "(?:^(Sell|Transaction (Spend|Sold))$)|" +
                                    "(?:^(Buy|Transaction (Buy|Revenue)|Binance Convert|Transaction Related)$)",
                            ),
                        ),
                ),
            )
        return CsvImportStrategy(
            id = CsvImportStrategyId(binanceCsvStrategyId),
            name = "Binance CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns =
                        setOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark"),
                    fieldMappings = fieldMappings,
                    rules = rules,
                    // Binance names its exports with bare UUIDs, so there is no filename signal to use - and a
                    // filename match would win outright over content scoring and let a legacy file through.
                    contentMatchRules = listOf(Condition("User_ID", ConditionOp.MATCHES, "^\\s*\\d+\\s*$")),
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(BINANCE_RECONCILE_WINDOW_SECONDS),
                    // The export has no unique id and repeats identical rows day after day (a flat daily Simple Earn
                    // interest), so the default 3-day fuzzy match swallowed a later day as a duplicate of an earlier one.
                    duplicateDateTolerance = SettlementWindow.Elapsed(0),
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = BINANCE_DUST_GROUP,
                                windowSeconds = BINANCE_CONVERSION_PAIRING_WINDOW_SECONDS,
                                assembly = LegAssembly.ThroughAccount(relationshipTypeName = "conversion"),
                                reconcileWindowSeconds = BINANCE_TRADE_RECONCILE_WINDOW_SECONDS,
                            ),
                            LegGroupRule(
                                name = BINANCE_TRADE_GROUP,
                                // The window and description keep their defaults: every leg of one fill carries the
                                // identical second so no jitter needs tolerating, and the default "Buy {to}/{from}"
                                // already matches the API importer's wording.
                                assembly = LegAssembly.Trade(),
                                reconcileWindowSeconds = BINANCE_TRADE_RECONCILE_WINDOW_SECONDS,
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Binance's leg group of a dust sweep's debits and BNB credits. */
    private const val BINANCE_DUST_GROUP = "dust"

    /** Binance's leg group folding a fill's rows into one trade. */
    private const val BINANCE_TRADE_GROUP = "trade"
}
