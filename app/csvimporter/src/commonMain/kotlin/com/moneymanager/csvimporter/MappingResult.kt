package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.importengineapi.ImportConversion
import kotlin.time.Instant

sealed interface MappingResult {
    /**
     * @property transfer The mapped transfer
     * @property newAccounts New accounts to create (source and/or target side)
     * @property attributes List of (attributeTypeName, value) pairs extracted from CSV
     * @property importStatus The import status (IMPORTED for new, DUPLICATE if exists with same values, UPDATED if exists with different values)
     * @property existingTransferId If status is DUPLICATE or UPDATED, the ID of the existing transfer
     * @property discoveredMappings For each new account being created, the CSV column/value that triggered it (for auto-capture)
     */
    data class Success(
        val rowIndex: Long,
        val transfer: Transfer,
        val newAccounts: List<NewAccount> = emptyList(),
        val attributes: List<Pair<String, String>> = emptyList(),
        val importStatus: ImportStatus = ImportStatus.IMPORTED,
        val existingTransferId: TransferId? = null,
        val discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
        /** Fee charged on the row, imported as its own linked fee transfer; null when there is none. */
        val feeAmount: Money? = null,
        /** The fee transfer's description; null uses a generic one. */
        val feeDescription: String? = null,
        /**
         * The credited leg of a cross-asset conversion (from the TO_CURRENCY/TO_AMOUNT mappings). When
         * set, the row is a `trade`: [transfer]'s amount/source is the debited leg and this the credited
         * leg entering [transfer]'s target account. Null for ordinary single-asset rows.
         */
        val tradeTo: Money? = null,
        /**
         * Name of the counterparty account when it was resolved via a person-flagged regex rule, so
         * the import can additionally create a Person + ownership link; null otherwise.
         */
        val personalCounterpartyName: String? = null,
        /**
         * Set when the row is a pass-through (conduit) charge (e.g. Curve): the transfer's counterparty
         * side (target, or source when [CsvPassThrough.incoming]) is the conduit account (funding leg)
         * and [CsvPassThrough.merchantName] is the real merchant the engine routes the spend leg to.
         * Null for ordinary rows.
         */
        val passThrough: CsvPassThrough? = null,
        /** Set when the row is one leg of a multi-row movement (see `LegGroupRule`); null otherwise. */
        val groupLeg: GroupLeg? = null,
        /** Raw funding value from [CsvStrategyConfig.fundingAttributeMatch]'s column; null when unset/blank. */
        val fundingMatchValue: String? = null,
        /**
         * The counterparty account when the strategy could not identify it and simply named it after the
         * row's description (see `ImportTransfer.unidentifiedCounterpartyAccountId`); null otherwise.
         * [UNRESOLVED_ACCOUNT_ID] until the account exists — the re-map after account creation resolves it.
         */
        val unidentifiedCounterpartyAccountId: AccountId? = null,
        /** The own account's conversion when [transfer] is booked in a foreign currency; null otherwise. */
        val conversion: ImportConversion? = null,
        /**
         * The account whose statement the row is (its own account, on whichever side the direction put it); null
         * when it isn't known. [UNRESOLVED_ACCOUNT_ID] until the account exists.
         */
        val statementAccountId: AccountId? = null,
        /** What the statement says its account holds once the row has happened (see `RuleAction.Balance`); null when it doesn't say. */
        val reportedBalance: Money? = null,
        /** When the statement's running balance counts the row (`RuleAction.Balance.at`); null: the row's own time. */
        val reportedBalanceAt: Instant? = null,
    ) : MappingResult {
        /** Convenience for flows where only the target side can discover a new account. */
        val newAccountName: String? get() = newAccounts.firstOrNull()?.name

        /** Convenience for flows where only the target side can discover a new account. */
        val discoveredMapping: DiscoveredAccountMapping? get() = discoveredMappings.firstOrNull()
    }

    /** A row a strategy rule skipped ([com.moneymanager.domain.model.rules.RuleAction.Skip]): nothing is imported for it. */
    data class Skipped(
        val rowIndex: Long,
    ) : MappingResult

    data class Error(
        val rowIndex: Long,
        val errorMessage: String,
    ) : MappingResult
}

/**
 * Stand-in id for an account that does not exist yet: the mapper's first pass emits it for accounts it
 * has asked the caller to create. It must never survive the re-map that follows account creation — a
 * transfer carrying it would violate the transfer's account foreign key and abort the whole file.
 */
val UNRESOLVED_ACCOUNT_ID = AccountId(-1)

/**
 * Stand-in id for the fee account of a row's own account, the counterparty of a row that is itself a fee (its `Fee` is
 * included in the amount and is all of it). The engine resolves fee accounts, so the mapper leaves this for [runCsvImport] to replace, by own account,
 * before anything books the row.
 */
val OWN_FEE_ACCOUNT_ID = AccountId(-2)

/**
 * A new account that needs to be created during import.
 */
data class NewAccount(
    val name: String,
    val categoryId: Long,
)

/**
 * Pass-through routing for a row whose charge passes through a chain of conduit accounts (e.g. card →
 * Curve → PayPal → merchant). The transfer itself is the funding leg (card -> first conduit); the
 * engine synthesises one spend leg per adjacent chain pair (C1 -> C2, …, Cn -> [merchantName]) and
 * links each movement to the next via [relationshipTypeId]. When [incoming] (a refund/cancellation
 * back onto the card) every leg runs the other way: funding first-conduit -> card, spend legs
 * [merchantName] -> Cn, …, C2 -> C1.
 */
data class CsvPassThrough(
    /** Conduit account names, outermost first; the row's counterparty side is the first one. */
    val conduitNames: List<String>,
    /** Effective merchant account name — the mapping target when a persisted mapping matched, else the stripped text. */
    val merchantName: String,
    /** Resolved existing merchant account; null when a new account will be created for [merchantName]. */
    val merchantAccountId: AccountId?,
    /**
     * One spend-leg description per conduit: the statement text remaining after that conduit's prefix
     * was peeled (the last is the fully stripped merchant text, e.g. "Amazoncouk 1234").
     */
    val spendDescriptions: List<String>,
    val relationshipTypeId: Long,
    val incoming: Boolean = false,
) {
    /** The conduit the row's own transfer moves money to/from. */
    val conduitName: String get() = conduitNames.first()
}

/** Which side of its event a leg is on (see `LegGroupRule`); [MEMBER] is a leg of a linking-only group, which has no side. */
enum class GroupLegSide { DEBIT, CREDIT, MEMBER }

/**
 * Marks a mapped row as one leg of a movement the source split across several rows (see
 * `LegGroupRule`). The applier puts legs of the same rule back together per its assembly; a group that
 * does not resolve leaves its rows to import as ordinary transfers, so this marker never drops a row.
 *
 * @property ruleIndex Index of the claiming rule in the strategy's `legGroups`.
 * @property side Whether this leg is the debit (asset leaving) or credit (asset received).
 * @property key The rule's key parts for this row, joined; legs of one event share it.
 */
data class GroupLeg(
    val ruleIndex: Int,
    val side: GroupLegSide,
    val key: String,
    /** The leg matches its rule's `ThroughAccount.fundingWhen` (the funding side of its event) or `Linked.anchorWhen` (its anchor). */
    val funding: Boolean = false,
)

/**
 * Result of preparing an import batch.
 *
 * @property validTransfers List of transfers to import with their status
 * @property errorRows Rows that failed to parse
 * @property skippedRowIndexes Rows a strategy rule skipped
 * @property newAccounts New accounts that need to be created
 * @property statusCounts Count of transfers by import status
 */
data class ImportPreparation(
    val validTransfers: List<MappingResult.Success>,
    val errorRows: List<MappingResult.Error>,
    val newAccounts: Set<NewAccount>,
    val statusCounts: Map<ImportStatus, Int> = emptyMap(),
    val skippedRowIndexes: List<Long> = emptyList(),
)

/**
 * Information about an existing transfer for duplicate detection.
 */
data class ExistingTransferInfo(
    val transferId: TransferId,
    val transfer: Transfer,
    val attributes: List<Pair<String, String>>,
    val uniqueIdentifierValues: Map<String, String>,
)

/**
 * Represents a mapping discovered during import that can be persisted.
 * Used for auto-capturing mappings when new accounts are created.
 *
 * @property csvValue The actual value from the CSV that led to this account
 * @property targetAccountName The name of the account that will be/was created from this value.
 *           For a rule without a pattern this equals csvValue; for a pattern rule it is the rule's
 *           rendered name, which may differ from csvValue.
 * @property matchedPattern The pattern of the account rule that matched, or null when a
 *           pattern-less rule supplied the value.
 *           When non-null, this pattern should be used for the persisted mapping instead of
 *           creating an exact-match pattern for csvValue.
 */
data class DiscoveredAccountMapping(
    val csvValue: String,
    val targetAccountName: String,
    val matchedPattern: String? = null,
)
