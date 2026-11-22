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
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Built-in Monzo CSV strategy. */
object MonzoStrategies {
    private val monzoCsvStrategyId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000004")

    /**
     * Cross-source reconciliation window for the Monzo CSV strategy. A personal<->joint transfer posts
     * to both account exports within seconds of each other (each side carries its own Transaction ID),
     * so a short window avoids pairing unrelated same-amount transfers on a busy day.
     */
    private const val MONZO_RECONCILE_WINDOW_SECONDS = 120L

    /**
     * Builds the built-in Monzo CSV import strategy for Monzo's transaction export.
     *
     * No SOURCE_ACCOUNT mapping is seeded (account ids are database-specific); the user picks
     * the Monzo account when applying the strategy — an import directory's configured account
     * disambiguates two folders of otherwise-identical exports (e.g. separate main/joint account
     * folders). Positive amounts flow INTO the account, so an amount-sign direction swaps source/target
     * for credits. Transactions are deduplicated by the Transaction ID column on re-import; a transfer
     * between two Monzo accounts that were each imported from their own CSV export (a different
     * Transaction ID per side) reconciles instead of double-counting, within
     * [MONZO_RECONCILE_WINDOW_SECONDS].
     */
    fun buildMonzoCsvStrategy(now: Instant): CsvImportStrategy {
        val fieldMappings =
            mapOf(
                accountFromRules(TransferField.TARGET_ACCOUNT),
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(
                        fieldType = TransferField.TIMESTAMP,
                        dateColumnName = "Date",
                        dateFormat = "dd/MM/yyyy",
                        timeColumnName = "Time",
                        timeFormat = "HH:mm:ss",
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
                TransferField.TIMEZONE to
                    // Monzo's CSV Date/Time columns are already plain UTC, not British local time —
                    // confirmed against the API's own timestamps, which agree with the raw CSV digits
                    // exactly year-round. "Europe/London" looked right in winter (GMT = UTC) but during
                    // BST wrongly shifted every CSV row an hour early, pushing Monzo-to-Monzo transfers
                    // outside the cross-source reconcile window and creating duplicates instead of
                    // reconciled pairs.
                    HardCodedTimezoneMapping(
                        fieldType = TransferField.TIMEZONE,
                        timezoneId = "UTC",
                    ),
            )
        val rules =
            listOf(
                attribute("Transaction ID", "Monzo Transaction ID", unique = true),
                attribute("Emoji", "Emoji"),
                attribute("Category", "Monzo Category"),
                attribute("Notes and #tags", "Notes and #tags"),
                attribute("Address", "Address"),
                attribute("Receipt", "Receipt"),
                attribute("Type", "Type"),
                incomingWhenPositive("Amount"),
                // A card payment abroad: Amount is the GBP Monzo settled, Local amount what the merchant was paid.
                rule(
                    RuleAction.Foreign(
                        ForeignAmount(amount = ValueExpr(listOf("Local amount")), currency = ValueExpr(listOf("Local currency"))),
                    ),
                ),
                // A Monzo-to-Monzo transfer's "Name" is another Monzo user, not a merchant — route it to an
                // account clearly identified as their Monzo account ("Monzo <name>") and mark the counterparty a
                // person, so the import also creates/links a Person as its owner.
                accounts(
                    AccountSide.TARGET,
                    naming("Name", pattern = ".+", name = "Monzo $0", fallbackName = "", person = true, personName = "$0"),
                    naming("Name"),
                    conditions = listOf(Condition("Type", ConditionOp.EQUALS, value = "Monzo-to-Monzo")),
                ),
                accounts(AccountSide.TARGET, AccountNaming(value = ValueExpr.of("Name", "Type"))),
            )
        val identificationColumns =
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
        return CsvImportStrategy(
            id = CsvImportStrategyId(monzoCsvStrategyId),
            name = "Monzo CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = identificationColumns,
                    fieldMappings = fieldMappings,
                    rules = rules,
                    crossSourceReconcileWindow = SettlementWindow.Elapsed(MONZO_RECONCILE_WINDOW_SECONDS),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
