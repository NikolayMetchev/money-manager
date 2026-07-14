@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.datetime.format.FormatStringsInDatetimeFormats::class,
)

package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ColumnExtraction
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.ConversionAccountRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importengineapi.DESCRIPTION_SIMILARITY_THRESHOLD
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.StringSimilarity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Result of mapping a CSV row to a Transfer.
 */
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
        val transfer: Transfer,
        val newAccounts: List<NewAccount> = emptyList(),
        val attributes: List<Pair<String, String>> = emptyList(),
        val importStatus: ImportStatus = ImportStatus.IMPORTED,
        val existingTransferId: TransferId? = null,
        val discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
        /** Fee charged on the row, imported as its own linked fee transfer; null when there is none. */
        val feeAmount: Money? = null,
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
        /** Set when the row is one leg of an asset conversion (see [ConversionConfig]); null otherwise. */
        val conversionLeg: ConversionLegInfo? = null,
    ) : MappingResult {
        /** Convenience for flows where only the target side can discover a new account. */
        val newAccountName: String? get() = newAccounts.firstOrNull()?.name

        /** Convenience for flows where only the target side can discover a new account. */
        val discoveredMapping: DiscoveredAccountMapping? get() = discoveredMappings.firstOrNull()
    }

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

/** Which side of an asset conversion a row represents (see [ConversionConfig]). */
enum class ConversionSide { DEBIT, CREDIT }

/**
 * Marks a mapped row as one leg of an asset conversion (see [ConversionConfig]). The applier pairs
 * each [ConversionSide.DEBIT] leg to a [ConversionSide.CREDIT] leg with the same [pairingKey] within
 * the config's time window and links them with the conversion relationship.
 *
 * @property side Whether this leg is the debit (asset leaving) or credit (asset received).
 * @property pairingKey Value that must match between a debit and credit leg of the same event.
 */
data class ConversionLegInfo(
    val side: ConversionSide,
    val pairingKey: String,
)

/**
 * A transfer with its associated attributes extracted from CSV.
 * Uses attribute type names (not IDs) since types may need to be created.
 *
 * @property transfer The transfer to import
 * @property attributes List of (attributeTypeName, value) pairs
 * @property importStatus The import status (IMPORTED, DUPLICATE, UPDATED)
 * @property existingTransferId If status is DUPLICATE or UPDATED, the ID of the existing transfer
 * @property rowIndex The original CSV row index for status tracking
 * @property discoveredMappings For each new account being created, the CSV column/value that triggered it
 */
data class CsvTransferWithAttributes(
    val transfer: Transfer,
    val attributes: List<Pair<String, String>>,
    val importStatus: ImportStatus = ImportStatus.IMPORTED,
    val existingTransferId: TransferId? = null,
    val rowIndex: Long,
    val discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
    /** Fee charged on the row, imported as its own linked fee transfer; null when there is none. */
    val feeAmount: Money? = null,
    /** Credited leg of a cross-asset conversion; when set the row is imported as a `trade`. */
    val tradeTo: Money? = null,
    /** Counterparty account name when it is a person (drives Person + ownership creation); else null. */
    val personalCounterpartyName: String? = null,
    /** Pass-through (conduit) routing for the row (e.g. Curve); null for ordinary rows. */
    val passThrough: CsvPassThrough? = null,
    /** Set when the row is one leg of an asset conversion (see [ConversionConfig]); null otherwise. */
    val conversionLeg: ConversionLegInfo? = null,
)

/**
 * Result of preparing an import batch.
 *
 * @property validTransfers List of transfers to import with their status
 * @property errorRows Rows that failed to parse
 * @property newAccounts New accounts that need to be created
 * @property existingAccountMatches Map of account name to existing account ID
 * @property statusCounts Count of transfers by import status
 */
data class ImportPreparation(
    val validTransfers: List<CsvTransferWithAttributes>,
    val errorRows: List<MappingResult.Error>,
    val newAccounts: Set<NewAccount>,
    val existingAccountMatches: Map<String, AccountId>,
    val statusCounts: Map<ImportStatus, Int> = emptyMap(),
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
 *           For AccountLookupMapping, this equals csvValue. For RegexAccountMapping, this is the
 *           extracted account name which may differ from csvValue.
 * @property matchedPattern The regex pattern that matched (for RegexAccountMapping with rules),
 *           or null if this was from AccountLookupMapping or RegexAccountMapping fallback logic.
 *           When non-null, this pattern should be used for the persisted mapping instead of
 *           creating an exact-match pattern for csvValue.
 */
data class DiscoveredAccountMapping(
    val csvValue: String,
    val targetAccountName: String,
    val matchedPattern: String? = null,
)

/** Separator joining the parts of a conversion pairing key; a control char that won't appear in data. */
private const val PAIRING_KEY_SEPARATOR = "\u0000"

/**
 * Maps CSV rows to Transfer objects using an import strategy.
 */
class CsvTransferMapper(
    private val strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    private val existingAccounts: Map<String, Account>,
    private val existingCurrencies: Map<CurrencyId, Currency>,
    private val existingCurrenciesByCode: Map<String, Currency>,
    /** Crypto assets keyed by uppercased ticker; consulted when a code isn't a known fiat currency. */
    private val existingCryptoByCode: Map<String, CryptoAsset> = emptyMap(),
    private val existingTransfers: List<ExistingTransferInfo> = emptyList(),
    accountMappings: List<AccountMapping> = emptyList(),
    /**
     * Former account names (from audit history), keyed by lowercased name → the account that most
     * recently bore it. Consulted as a fallback after current-name lookup so a renamed account still
     * resolves when a row carries its old name. See [AccountReadRepository.getPreviousAccountNames].
     */
    private val historicalAccountNames: Map<String, AccountId> = emptyMap(),
    /** When set, overrides the strategy's SOURCE_ACCOUNT mapping for every row. */
    private val sourceAccountOverride: AccountId? = null,
    /** Detects pass-through (conduit) charges (e.g. Curve) from the row description; null disables it. */
    private val passThroughDetector: PassThroughDetector? = null,
) {
    private val columnIndexByName: Map<String, Int> =
        columns.associate { it.originalName to it.columnIndex }

    // Compiled once per pattern: account rules and column extractions run against every row, so
    // compiling in place would dominate the mapping cost. Case-insensitive like all rule matching here.
    private val patternCache = HashMap<String, Regex>()

    private fun compiledPattern(pattern: String): Regex = patternCache.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }

    // Likewise for date/time formats: building a kotlinx-datetime Format compiles a parser, which is
    // far more expensive than the parse itself.
    private val dateTimeFormatCache = HashMap<String, DateTimeFormat<LocalDateTime>>()
    private val dateFormatCache = HashMap<String, DateTimeFormat<LocalDate>>()
    private val timeFormatCache = HashMap<String, DateTimeFormat<LocalTime>>()

    private fun cachedDateTimeFormat(pattern: String): DateTimeFormat<LocalDateTime> =
        dateTimeFormatCache.getOrPut(pattern) {
            LocalDateTime.Format { byUnicodePattern(pattern) }
        }

    private fun cachedDateFormat(pattern: String): DateTimeFormat<LocalDate> =
        dateFormatCache.getOrPut(pattern) {
            LocalDate.Format { byUnicodePattern(pattern) }
        }

    private fun cachedTimeFormat(pattern: String): DateTimeFormat<LocalTime> =
        timeFormatCache.getOrPut(pattern) {
            LocalTime.Format { byUnicodePattern(pattern) }
        }

    // Precompiled conversion detection (null when the strategy declares no conversionConfig). Regexes
    // are case-insensitive, matching the file's existing account/content-rule matching convention.
    private val conversionDebitRegex: Regex? =
        strategy.conversionConfig?.let { Regex(it.debitPattern, RegexOption.IGNORE_CASE) }
    private val conversionCreditRegex: Regex? =
        strategy.conversionConfig?.let { Regex(it.creditPattern, RegexOption.IGNORE_CASE) }
    private val conversionPairingKeyRegex: Regex? =
        strategy.conversionConfig?.pairingKeyPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
    private val conversionAccountRuleRegexes: List<Pair<Regex, ConversionAccountRule>> =
        strategy.conversionConfig
            ?.conversionAccountRules
            .orEmpty()
            .map { Regex(it.pattern, RegexOption.IGNORE_CASE) to it }

    // Extract unique identifier column names from strategy
    private val uniqueIdentifierColumns: List<String> =
        strategy.attributeMappings.filter { it.isUniqueIdentifier }.map { it.columnName }

    // Index existing transfers by their unique identifier values for fast lookup
    private val existingTransfersByUniqueId: Map<Map<String, String>, ExistingTransferInfo> =
        if (uniqueIdentifierColumns.isNotEmpty()) {
            existingTransfers.associateBy { it.uniqueIdentifierValues }
        } else {
            emptyMap()
        }

    // Only global mappings (strategyId null) and mappings scoped to THIS strategy apply; a
    // strategy-specific match is ordered ahead of a global one so it wins in findPersistedMapping
    // (which returns the first match).
    private val scopedAccountMappings: List<AccountMapping> =
        accountMappings
            .filter { it.strategyId == null || it.strategyId == strategy.id }
            .sortedWith(compareBy({ it.strategyId == null }, { it.id }))

    private val accountsById: Map<AccountId, Account> by lazy {
        existingAccounts.values.associateBy { it.id }
    }

    // CSV account values repeat heavily across rows, and findPersistedMapping is called several
    // times per row — memoizing collapses an O(rows x mappings) regex scan to one pass per value.
    private val persistedMappingCache = HashMap<String, AccountId?>()

    /**
     * Prepares an import by mapping all rows and collecting new accounts to create.
     */
    fun prepareImport(rows: List<CsvRow>): ImportPreparation {
        val validTransfers = mutableListOf<CsvTransferWithAttributes>()
        val errorRows = mutableListOf<MappingResult.Error>()
        val newAccounts = mutableSetOf<NewAccount>()
        val existingMatches = mutableMapOf<String, AccountId>()
        val statusCounts = mutableMapOf<ImportStatus, Int>()

        for (row in rows) {
            when (val result = mapRow(row)) {
                is MappingResult.Success -> {
                    validTransfers.add(
                        CsvTransferWithAttributes(
                            transfer = result.transfer,
                            attributes = result.attributes,
                            importStatus = result.importStatus,
                            existingTransferId = result.existingTransferId,
                            rowIndex = row.rowIndex,
                            discoveredMappings = result.discoveredMappings,
                            feeAmount = result.feeAmount,
                            tradeTo = result.tradeTo,
                            personalCounterpartyName = result.personalCounterpartyName,
                            passThrough = result.passThrough,
                            conversionLeg = result.conversionLeg,
                        ),
                    )
                    // Count by status
                    statusCounts[result.importStatus] = statusCounts.getOrDefault(result.importStatus, 0) + 1

                    newAccounts.addAll(result.newAccounts)
                }
                is MappingResult.Error -> {
                    errorRows.add(result)
                }
            }
        }

        // Collect existing account matches
        for (transferWithAttrs in validTransfers) {
            for ((name, account) in existingAccounts) {
                if (account.id == transferWithAttrs.transfer.sourceAccountId ||
                    account.id == transferWithAttrs.transfer.targetAccountId
                ) {
                    existingMatches[name] = account.id
                }
            }
        }

        return ImportPreparation(
            validTransfers = validTransfers,
            errorRows = errorRows,
            newAccounts = newAccounts,
            existingAccountMatches = existingMatches,
            statusCounts = statusCounts,
        )
    }

    /**
     * Maps a single CSV row to a Transfer.
     */
    fun mapRow(row: CsvRow): MappingResult {
        return try {
            // Attribute extraction and unique-id dedup use the original values so they stay
            // faithful to the CSV; field parsing uses the preprocessed (possibly swapped) values.
            val originalValues = row.values
            val (values, rulesFlip) = applyRowPreprocessing(originalValues)
            val targetMapping =
                strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TARGET_ACCOUNT mapping")
            val timestampMapping =
                strategy.fieldMappings[TransferField.TIMESTAMP]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TIMESTAMP mapping")
            val descriptionMapping =
                strategy.fieldMappings[TransferField.DESCRIPTION]
                    ?: return MappingResult.Error(row.rowIndex, "Missing DESCRIPTION mapping")
            val amountMapping =
                strategy.fieldMappings[TransferField.AMOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing AMOUNT mapping")
            val currencyMapping =
                strategy.fieldMappings[TransferField.CURRENCY]
                    ?: return MappingResult.Error(row.rowIndex, "Missing CURRENCY mapping")

            // Parse amount first (needed for account flipping)
            val rawAmount = parseAmount(amountMapping, values)

            // Parse currency
            val currency =
                parseCurrency(currencyMapping, values)
                    ?: return MappingResult.Error(row.rowIndex, "Currency not found")

            // Cross-asset conversion: when the strategy maps a TO_CURRENCY/TO_AMOUNT and the credited
            // asset differs from the debited one, this row is a trade — the credited leg is captured
            // here and the importer emits a `trade` (debit `amount`, credit `tradeTo`) instead of a
            // single-asset transfer. Computed before account resolution because a trade may legally
            // keep both legs in ONE account (e.g. a crypto→crypto exchange inside the same wallet),
            // which the same-account checks below must not treat as a collision.
            val tradeTo: Money? =
                run {
                    val toCurrencyMapping = strategy.fieldMappings[TransferField.TO_CURRENCY] ?: return@run null
                    val toAmountMapping = strategy.fieldMappings[TransferField.TO_AMOUNT] ?: return@run null
                    val toAsset = parseCurrency(toCurrencyMapping, values) ?: return@run null
                    if (toAsset.id == currency.id) return@run null
                    val toRaw = parseAmount(toAmountMapping, values).abs()
                    if (toRaw.compareTo(BigDecimal.ZERO) == 0) return@run null
                    Money.fromDisplayValue(toRaw, toAsset)
                }

            // Determine if we need to flip accounts (sign-based flip XOR preprocessing flip)
            val amountFlip =
                amountMapping is AmountParsingMapping &&
                    amountMapping.flipAccountsOnPositive &&
                    rawAmount > BigDecimal.ZERO
            val flipAccounts = amountFlip xor rulesFlip

            // Resolve the source account: use override if provided, otherwise fall back to the
            // strategy's SOURCE_ACCOUNT mapping (if present), or return an error.
            val resolvedSourceAccountId: AccountId =
                if (sourceAccountOverride != null) {
                    sourceAccountOverride
                } else {
                    val sourceMapping =
                        strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
                            ?: return MappingResult.Error(
                                row.rowIndex,
                                "No source account selected. Please choose a source account before importing.",
                            )
                    parseAccount(sourceMapping, values)
                }

            // Parse accounts with potential flipping
            var sourceAccountId = resolvedSourceAccountId
            var targetAccountId = parseAccount(targetMapping, values)

            // Asset-conversion leg: route the counterparty side to the shared conversion account so both
            // the debit and credit legs parse as valid single-asset transfers (owner account <-> conversion
            // account). The amount-sign flip below then places the owner on the correct side (owner is the
            // source of a debit, the target of a credit). The applier pairs and links the legs afterwards.
            val conversionDetection = detectConversionLeg(values)
            if (conversionDetection != null) {
                targetAccountId = resolveExistingAccountId(conversionDetection.accountName) ?: UNRESOLVED_ACCOUNT_ID
            }

            // Both account fields can read the same column (e.g. Crypto.com's card strategy resolves both
            // legs from "Transaction Description"), so a persisted counterparty mapping that matches the
            // description can hijack the source leg too and collapse both onto one account. When the source
            // came from the strategy's own SOURCE_ACCOUNT mapping (no UI override) and now collides with the
            // target, re-resolve the source ignoring persisted mappings — its rule/historical name only —
            // which restores the intended own-account (e.g. "Crypto.com Card"). A trade row is exempt:
            // both legs landing in the same account is the intended shape (cross-asset, same wallet),
            // so re-resolving would silently move one leg off a user-mapped account.
            var sourceUsedPersistedMappings = true
            if (tradeTo == null &&
                sourceAccountOverride == null &&
                sourceAccountId == targetAccountId &&
                sourceAccountId != UNRESOLVED_ACCOUNT_ID
            ) {
                strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]?.let {
                    sourceAccountId = parseAccount(it, values, applyPersistedMappings = false)
                    sourceUsedPersistedMappings = false
                }
            }

            if (flipAccounts) {
                val temp = sourceAccountId
                sourceAccountId = targetAccountId
                targetAccountId = temp
            }

            // Parse timezone (optional - defaults to system timezone)
            val timezoneMapping = strategy.fieldMappings[TransferField.TIMEZONE]
            val timezone = parseTimezone(timezoneMapping, values)

            // Parse timestamp
            val timestamp =
                parseTimestamp(timestampMapping as DateTimeParsingMapping, values, timezone)
                    ?: return MappingResult.Error(row.rowIndex, "Failed to parse timestamp")

            // Parse description
            val description = parseDescription(descriptionMapping, values)

            // Detect a pass-through (conduit) charge, e.g. Curve, from the description. An outgoing
            // spend (no flip) becomes the funding leg card -> conduit and the engine adds the spend leg
            // conduit -> merchant; an incoming refund/cancellation (flipAccounts) runs both legs the
            // other way, so the conduit replaces the row's counterparty on whichever side it sits.
            // Detection + the conduit/merchant names come entirely from user-editable config (the
            // engine stays agnostic).
            // A conduit is resolved exactly the way [accountExists] decides whether to create it (current
            // name, then historical name): resolving it any more narrowly would leave the conduit side
            // dangling at AccountId(-1) for a conduit that was never created because it already exists.
            val passThroughMatch = passThroughDetector?.detect(description)
            val chainConduitIds =
                passThroughMatch?.accounts?.mapNotNull { resolveExistingAccountId(it.conduitAccountName) }.orEmpty()
            val conduitAccountId =
                passThroughMatch?.let {
                    resolveExistingAccountId(it.accounts.first().conduitAccountName) ?: UNRESOLVED_ACCOUNT_ID
                }
            // Persisted account mappings apply to the merchant AFTER the full chain of prefixes was
            // peeled (e.g. "Crv*Paypal *Amazoncouk 1234" → "Amazoncouk 1234" → mapping ".*Amazoncouk.*"
            // → Amazon). A mapping that targets any conduit of the chain (or a deleted account) is
            // ignored — the engine must never synthesise a conduit→conduit spend leg.
            val passThrough =
                passThroughMatch?.let { match ->
                    val mapped = findPersistedMapping(match.merchantName)?.let { accountsById[it] }
                    val (merchantName, merchantAccountId) =
                        if (mapped != null && mapped.id !in chainConduitIds) {
                            mapped.name to mapped.id
                        } else {
                            match.merchantName to resolveExistingAccountId(match.merchantName)
                        }
                    CsvPassThrough(
                        conduitNames = match.accounts.map { it.conduitAccountName },
                        merchantName = merchantName,
                        merchantAccountId = merchantAccountId,
                        spendDescriptions = match.hops.map { it.merchantText },
                        relationshipTypeId = match.accounts.first().relationshipTypeId,
                        incoming = flipAccounts,
                    )
                }
            val effectiveSourceAccountId =
                if (conduitAccountId != null && flipAccounts) conduitAccountId else sourceAccountId
            val effectiveTargetAccountId =
                if (conduitAccountId != null && !flipAccounts) conduitAccountId else targetAccountId

            // A transfer must move between two distinct accounts (enforced by a DB CHECK). If both legs
            // resolved to the same real account (e.g. an account mapping that matches this row on both
            // sides), surface it as a per-row error instead of letting one bad row abort the whole file.
            // The placeholder id is the "new account" marker; two not-yet-created accounts stay distinct.
            // A trade row is exempt: the trade CHECK only requires the ASSETS to differ, so a same-account
            // cross-asset exchange (e.g. BTC→ETH inside one wallet) is valid.
            if (tradeTo == null &&
                effectiveSourceAccountId == effectiveTargetAccountId &&
                effectiveSourceAccountId != UNRESOLVED_ACCOUNT_ID
            ) {
                val accountName =
                    existingAccounts.entries.firstOrNull { it.value.id == effectiveSourceAccountId }?.key
                return MappingResult.Error(
                    row.rowIndex,
                    "Source and target resolved to the same account" +
                        (accountName?.let { " (\"$it\")" } ?: "") +
                        "; a transfer must move between two different accounts. Check your account mappings.",
                )
            }

            // Detect a personal counterparty (resolved from the target mapping regardless of any
            // account flip — the counterparty is the same account on whichever side it ends up). A
            // pass-through merchant / conversion counterparty is never a person, so skip it there.
            val personalCounterpartyName =
                if (passThroughMatch != null || conversionDetection != null) {
                    null
                } else {
                    resolvePersonalCounterparty(targetMapping, values)
                }

            // Create Money with absolute value (direction is indicated by source/target)
            val amount = Money.fromDisplayValue(rawAmount.abs(), currency)

            // A fee is modelled as its own movement (linked to this transfer), not folded into the amount.
            val feeMagnitude = parseFeeMagnitude(amountMapping, values)
            val feeAmount =
                if (feeMagnitude > BigDecimal.ZERO) Money.fromDisplayValue(feeMagnitude, currency) else null

            // Placeholder ID - real ID generated by database
            val transfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = effectiveSourceAccountId,
                    targetAccountId = effectiveTargetAccountId,
                    amount = amount,
                )

            // Extract attributes from mapped columns (original, pre-swap values)
            val attributes = extractAttributes(originalValues)

            // Check for duplicates using unique identifiers if configured, otherwise check by all fields
            val (importStatus, existingTransferId) =
                if (uniqueIdentifierColumns.isNotEmpty()) {
                    checkForDuplicateByUniqueId(originalValues, transfer, attributes)
                } else {
                    checkForDuplicateByAllFields(transfer, attributes)
                }

            // Determine which new accounts need to be created and capture mapping info.
            // The source side participates only when it is resolved per-row (no UI override). For a
            // pass-through row the counterparty side is replaced by the conduit + merchant accounts (so
            // the raw "CRV*…" junk account is never discovered), and no account mapping is auto-captured.
            val discoveries =
                buildList {
                    if (sourceAccountOverride == null) {
                        // Discover the source under the same persisted-mapping rule its id was resolved with:
                        // if the source leg was re-resolved without persisted mappings (collision above), a
                        // genuinely new source account must still be discovered/created rather than suppressed
                        // by a persisted mapping — otherwise it would dangle unresolved.
                        strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]?.let {
                            add(discoverNewAccount(it, values, applyPersistedMappings = sourceUsedPersistedMappings))
                        }
                    }
                    // A conversion leg's counterparty is the shared conversion account (added below), not
                    // the description-derived account the target mapping would discover, so skip it here.
                    if (passThroughMatch == null && conversionDetection == null) add(discoverNewAccount(targetMapping, values))
                }
            val targetCategoryId =
                when (targetMapping) {
                    is AccountLookupMapping -> targetMapping.defaultCategoryId
                    is RegexAccountMapping -> targetMapping.defaultCategoryId
                    is TemplateAccountMapping -> targetMapping.defaultCategoryId
                    else -> Category.UNCATEGORIZED_ID
                }
            val newAccounts =
                buildList {
                    addAll(discoveries.mapNotNull { it?.first })
                    // Create the shared conversion counterparty account on demand.
                    if (conversionDetection != null && !accountExists(conversionDetection.accountName)) {
                        add(NewAccount(conversionDetection.accountName, Category.UNCATEGORIZED_ID))
                    }
                    if (passThrough != null) {
                        passThrough.conduitNames
                            .filterNot { accountExists(it) }
                            .forEach { add(NewAccount(it, targetCategoryId)) }
                        if (passThrough.merchantAccountId == null) add(NewAccount(passThrough.merchantName, targetCategoryId))
                    }
                }
            val discoveredMappings = discoveries.mapNotNull { it?.second }

            MappingResult.Success(
                transfer = transfer,
                newAccounts = newAccounts,
                attributes = attributes,
                importStatus = importStatus,
                existingTransferId = existingTransferId,
                discoveredMappings = discoveredMappings,
                feeAmount = feeAmount,
                tradeTo = tradeTo,
                personalCounterpartyName = personalCounterpartyName,
                passThrough = passThrough,
                conversionLeg =
                    conversionDetection?.let { ConversionLegInfo(side = it.side, pairingKey = it.pairingKey) },
            )
        } catch (expected: Exception) {
            MappingResult.Error(row.rowIndex, expected.message ?: "Unknown error")
        }
    }

    /**
     * Extracts attribute values from CSV row based on strategy.attributeMappings.
     * Skips attributes with blank values.
     */
    private fun extractAttributes(values: List<String>): List<Pair<String, String>> =
        strategy.attributeMappings.mapNotNull { mapping ->
            val value = getColumnValueOrNull(mapping.columnName, values)?.trim()
            if (value.isNullOrBlank()) {
                return@mapNotNull null
            }
            val extraction = mapping.extraction
            // Unique-identifier columns always use the whole column value so the dedup key (which reads
            // the raw column directly) and the stored attribute value never diverge.
            if (extraction == null || mapping.isUniqueIdentifier) {
                mapping.attributeTypeName to value
            } else {
                val extracted = applyExtraction(value, extraction) ?: return@mapNotNull null
                mapping.attributeTypeName to (mapping.emitWhenMatched ?: extracted)
            }
        }

    /**
     * Gets a column value by name, returning null if the column doesn't exist.
     */
    private fun getColumnValueOrNull(
        columnName: String,
        values: List<String>,
    ): String? {
        val index = columnIndexByName[columnName] ?: return null
        return values.getOrNull(index)
    }

    /** A detected conversion leg: which side, the counterparty account name, and the pairing key. */
    private data class ConversionDetection(
        val side: ConversionSide,
        val accountName: String,
        val pairingKey: String,
    )

    /**
     * Detects whether [values] is a leg of an asset conversion per [CsvImportStrategy.conversionConfig].
     * Returns null when the strategy has no conversion config, the signal column doesn't match a
     * debit/credit pattern, or no counterparty account can be resolved.
     */
    private fun detectConversionLeg(values: List<String>): ConversionDetection? {
        val config = strategy.conversionConfig ?: return null
        val signal = getColumnValueOrNull(config.signalColumn, values)?.trim().orEmpty()
        if (signal.isEmpty()) return null
        val side =
            when {
                conversionDebitRegex?.containsMatchIn(signal) == true -> ConversionSide.DEBIT
                conversionCreditRegex?.containsMatchIn(signal) == true -> ConversionSide.CREDIT
                else -> return null
            }
        val accountName =
            conversionAccountRuleRegexes
                .firstOrNull { (regex, rule) ->
                    getColumnValueOrNull(rule.column, values)?.let { regex.containsMatchIn(it) } == true
                }?.second
                ?.accountName
                ?: config.conversionAccountName
                ?: return null
        val base =
            conversionPairingKeyRegex
                ?.find(signal)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        val extra = config.pairingKeyColumns.joinToString(PAIRING_KEY_SEPARATOR) { getColumnValueOrNull(it, values)?.trim().orEmpty() }
        return ConversionDetection(side, accountName, "$base$PAIRING_KEY_SEPARATOR$extra")
    }

    private fun parseAmount(
        amountMapping: FieldMapping,
        values: List<String>,
    ): BigDecimal {
        val mapping = amountMapping as AmountParsingMapping
        val baseAmount =
            when (mapping.mode) {
                AmountMode.SINGLE_COLUMN -> {
                    val columnName =
                        mapping.amountColumnName
                            ?: error("amountColumnName required for SINGLE_COLUMN mode")
                    val value = getColumnValue(columnName, values)
                    if (mapping.negateValues) -parseBigDecimal(value) else parseBigDecimal(value)
                }
                AmountMode.CREDIT_DEBIT_COLUMNS -> {
                    val creditColumnName =
                        mapping.creditColumnName
                            ?: error("creditColumnName required")
                    val debitColumnName =
                        mapping.debitColumnName
                            ?: error("debitColumnName required")
                    val creditValue = getColumnValue(creditColumnName, values)
                    val debitValue = getColumnValue(debitColumnName, values)
                    val credit = if (creditValue.isNotBlank()) parseBigDecimal(creditValue) else BigDecimal.ZERO
                    val debit = if (debitValue.isNotBlank()) parseBigDecimal(debitValue) else BigDecimal.ZERO
                    credit - debit
                }
            }
        return baseAmount
    }

    /**
     * Returns the fee magnitude (unsigned) for a row, modelled as its own movement out of the
     * transaction's source account rather than folded into the transaction amount. Zero when no fee
     * column is configured, the value is blank, or the conditions don't hold.
     */
    private fun parseFeeMagnitude(
        amountMapping: FieldMapping,
        values: List<String>,
    ): BigDecimal {
        val mapping = amountMapping as? AmountParsingMapping ?: return BigDecimal.ZERO
        val feeColumnName = mapping.feeColumnName ?: return BigDecimal.ZERO
        if (!mapping.feeConditions.all { evaluateCondition(it, values) }) return BigDecimal.ZERO
        val feeValue = getColumnValueOrNull(feeColumnName, values)?.trim()
        if (feeValue.isNullOrBlank()) return BigDecimal.ZERO
        return parseBigDecimal(feeValue).abs()
    }

    /**
     * Applies the strategy's row preprocessing rules to the raw row values.
     * Returns the (possibly column-swapped) values and whether source/target accounts must flip.
     */
    private fun applyRowPreprocessing(values: List<String>): Pair<List<String>, Boolean> {
        var effective = values
        var flip = false
        for (rule in strategy.rowPreprocessingRules) {
            if (rule.conditions.all { evaluateCondition(it, effective) }) {
                effective = applyColumnSwaps(rule.columnSwaps, effective)
                if (rule.flipSourceAndTarget) flip = !flip
            }
        }
        return effective to flip
    }

    private fun applyColumnSwaps(
        swaps: List<ColumnPairSwap>,
        values: List<String>,
    ): List<String> {
        if (swaps.isEmpty()) return values
        val mutable = values.toMutableList()
        for (swap in swaps) {
            val firstIndex = columnIndexByName[swap.firstColumn]
            val secondIndex = columnIndexByName[swap.secondColumn]
            if (firstIndex != null && secondIndex != null) {
                // Rows may have fewer values than columns; pad so both indices are addressable
                while (mutable.size <= maxOf(firstIndex, secondIndex)) {
                    mutable.add("")
                }
                val temp = mutable[firstIndex]
                mutable[firstIndex] = mutable[secondIndex]
                mutable[secondIndex] = temp
            }
        }
        return mutable
    }

    private fun evaluateCondition(
        condition: RowCondition,
        values: List<String>,
    ): Boolean {
        val value = getColumnValueOrNull(condition.columnName, values)?.trim().orEmpty()
        return when (condition.operator) {
            RowConditionOperator.EQUALS_VALUE -> value == condition.value?.trim().orEmpty()
            RowConditionOperator.EQUALS_COLUMN -> value == otherColumnValue(condition, values)
            RowConditionOperator.NOT_EQUALS_COLUMN -> value != otherColumnValue(condition, values)
            RowConditionOperator.IS_BLANK -> value.isBlank()
            RowConditionOperator.IS_NOT_BLANK -> value.isNotBlank()
        }
    }

    private fun otherColumnValue(
        condition: RowCondition,
        values: List<String>,
    ): String {
        val otherColumn =
            condition.otherColumnName
                ?: error("otherColumnName required for ${condition.operator}")
        return getColumnValueOrNull(otherColumn, values)?.trim().orEmpty()
    }

    /**
     * Resolves a ConditionalAccountMapping to its active branch for the given row.
     */
    private fun resolveConditional(
        mapping: ConditionalAccountMapping,
        values: List<String>,
    ): FieldMapping =
        if (mapping.conditions.all { evaluateCondition(it, values) }) {
            mapping.whenTrue
        } else {
            mapping.whenFalse
        }

    /**
     * Builds the templated account name for a TemplateAccountMapping,
     * or an empty string when the column value is blank.
     */
    private fun templatedAccountName(
        mapping: TemplateAccountMapping,
        csvValue: String,
    ): String = if (csvValue.isBlank()) "" else mapping.prefix + csvValue + mapping.suffix

    /**
     * Resolves a field mapping to an account id. Persisted account mappings (the user's "map this CSV
     * value to that account" choices) are consulted first — they redirect a **counterparty** and handle
     * renamed accounts. [applyPersistedMappings] can be set false to bypass them when re-resolving the
     * **source** leg after a persisted counterparty mapping hijacked it into a self-transfer (both legs
     * read the same column, so a merchant mapping can match the source too); see [mapRow].
     */
    private fun parseAccount(
        mapping: FieldMapping,
        values: List<String>,
        applyPersistedMappings: Boolean = true,
    ): AccountId {
        // For hardcoded accounts, return immediately
        if (mapping is HardCodedAccountMapping) {
            return mapping.accountId
        }

        return when (mapping) {
            is TemplateAccountMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values).trim()

                // Check persisted mappings FIRST - this handles renamed accounts
                if (applyPersistedMappings) {
                    findPersistedMapping(csvValue)?.let { return it }
                }

                val name = templatedAccountName(mapping, csvValue)
                resolveExistingAccountId(name)
                    ?: UNRESOLVED_ACCOUNT_ID // Placeholder for new accounts
            }
            is ConditionalAccountMapping -> parseAccount(resolveConditional(mapping, values), values, applyPersistedMappings)
            is AccountLookupMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values)

                // Check persisted mappings FIRST - this handles renamed accounts
                if (applyPersistedMappings) {
                    findPersistedMapping(csvValue)?.let { return it }
                }

                // Fall back to lookup by name (current, then historical for renamed accounts)
                val name = getAccountName(mapping, values)
                resolveExistingAccountId(name)
                    ?: UNRESOLVED_ACCOUNT_ID // Placeholder for new accounts
            }
            is RegexAccountMapping -> {
                // For RegexAccountMapping, we need to determine which column/value
                // will actually be used (could be fallback column)
                val result = getAccountNameFromRegexWithPattern(mapping, values)

                // Check persisted mappings using the ACTUAL value that was resolved
                if (applyPersistedMappings) {
                    findPersistedMapping(result.sourceColumnValue)?.let { return it }
                }

                // Fall back to lookup by name (current, then historical for renamed accounts)
                resolveExistingAccountId(result.accountName)
                    ?: UNRESOLVED_ACCOUNT_ID // Placeholder for new accounts
            }
            else -> throw IllegalArgumentException("Invalid account mapping type: ${mapping::class}")
        }
    }

    /**
     * Finds a persisted account mapping whose pattern matches the given account source value
     * (the value the strategy's account field-mapping resolved for this row). First matching
     * mapping wins: strategy-scoped mappings are tried before global ones, then id order — so
     * mappings that share a pattern resolve deterministically.
     *
     * @param value The value to match against
     * @return The mapped AccountId, or null if no match found
     */
    private fun findPersistedMapping(value: String): AccountId? {
        if (value in persistedMappingCache) return persistedMappingCache[value]
        val result = scopedAccountMappings.firstOrNull { it.valuePattern.containsMatchIn(value) }?.accountId
        persistedMappingCache[value] = result
        return result
    }

    /**
     * Determines whether resolving [mapping] for this row requires creating a new account.
     * Returns the account to create together with the discovered mapping for auto-capture,
     * or null when no new account is needed (existing account, persisted mapping, or blank value).
     */
    private fun discoverNewAccount(
        mapping: FieldMapping,
        values: List<String>,
        applyPersistedMappings: Boolean = true,
    ): Pair<NewAccount, DiscoveredAccountMapping>? =
        when (mapping) {
            is AccountLookupMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values)
                // If a persisted mapping matched, don't create a new account
                if (applyPersistedMappings && findPersistedMapping(csvValue) != null) {
                    null
                } else {
                    val name = getAccountName(mapping, values)
                    if (name.isNotBlank() && !accountExists(name)) {
                        // For AccountLookupMapping, csvValue == name (account name)
                        NewAccount(name, mapping.defaultCategoryId) to
                            DiscoveredAccountMapping(csvValue, name)
                    } else {
                        null
                    }
                }
            }
            is RegexAccountMapping -> {
                val result = getAccountNameFromRegexWithPattern(mapping, values)
                // If a persisted mapping matched, don't create a new account
                if (applyPersistedMappings && findPersistedMapping(result.sourceColumnValue) != null) {
                    null
                } else if (result.accountName.isNotBlank() && !accountExists(result.accountName)) {
                    // For RegexAccountMapping, accountName differs from sourceColumnValue
                    // (e.g., sourceColumnValue="Paxos Technology LTD", accountName="Paxos")
                    NewAccount(result.accountName, mapping.defaultCategoryId) to
                        DiscoveredAccountMapping(
                            csvValue = result.sourceColumnValue,
                            targetAccountName = result.accountName,
                            matchedPattern = result.matchedPattern,
                        )
                } else {
                    null
                }
            }
            is TemplateAccountMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values).trim()
                val name = templatedAccountName(mapping, csvValue)
                if ((applyPersistedMappings && findPersistedMapping(csvValue) != null) ||
                    name.isBlank() ||
                    accountExists(name)
                ) {
                    null
                } else {
                    NewAccount(name, mapping.defaultCategoryId) to
                        DiscoveredAccountMapping(csvValue, name)
                }
            }
            is ConditionalAccountMapping -> discoverNewAccount(resolveConditional(mapping, values), values, applyPersistedMappings)
            else -> null
        }

    /**
     * Gets the effective account name from an AccountLookupMapping,
     * trying the primary column first, then fallbacks in order.
     */
    private fun getAccountName(
        mapping: AccountLookupMapping,
        values: List<String>,
    ): String =
        mapping.allColumns
            .map { getColumnValue(it, values) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    /**
     * Result of resolving a RegexAccountMapping to an account name.
     */
    private data class RegexAccountResult(
        val accountName: String,
        val sourceColumnName: String,
        val sourceColumnValue: String,
        val matchedPattern: String?,
        val counterpartyIsPerson: Boolean = false,
    )

    /**
     * Gets the effective account name and the matched pattern from a RegexAccountMapping.
     * Returns a pair of (accountName, matchedPattern) where matchedPattern is null if
     * no regex rule matched (fallback logic was used).
     */
    private fun getAccountNameFromRegexWithPattern(
        mapping: RegexAccountMapping,
        values: List<String>,
    ): RegexAccountResult {
        val primaryValue = getColumnValue(mapping.columnName, values)

        // Try each rule in order; first match wins
        if (primaryValue.isNotBlank()) {
            for (rule in mapping.rules) {
                val match = compiledPattern(rule.pattern).find(primaryValue)
                if (match != null) {
                    // When a template is configured, derive the name from the matched text via
                    // capture-group substitution; otherwise use the fixed account name (legacy behaviour).
                    val accountName =
                        rule.accountNameTemplate
                            ?.let { substituteTemplate(it, match).replace(WHITESPACE_RUN_REGEX, " ").trim() }
                            ?.takeIf { it.isNotBlank() }
                            ?: rule.accountName
                    return RegexAccountResult(
                        accountName = accountName,
                        sourceColumnName = mapping.columnName,
                        sourceColumnValue = primaryValue,
                        matchedPattern = rule.pattern,
                        counterpartyIsPerson = rule.counterpartyIsPerson,
                    )
                }
            }
        }

        // No rules matched - use fallback logic (try columns in order for non-empty value)
        // Track which column provided the value
        for (columnName in mapping.allColumns) {
            val columnValue = getColumnValue(columnName, values)
            if (columnValue.isNotBlank()) {
                return RegexAccountResult(
                    accountName = columnValue,
                    sourceColumnName = columnName,
                    sourceColumnValue = columnValue,
                    matchedPattern = null,
                )
            }
        }

        // No value found in any column
        return RegexAccountResult(
            accountName = "",
            sourceColumnName = mapping.columnName,
            sourceColumnValue = "",
            matchedPattern = null,
        )
    }

    private fun parseTimestamp(
        mapping: DateTimeParsingMapping,
        values: List<String>,
        timezone: TimeZone,
    ): Instant? {
        val dateValue = getColumnValue(mapping.dateColumnName, values)
        val timeValue =
            mapping.timeColumnName?.let { getColumnValue(it, values) }
                ?: mapping.defaultTime

        val dateTimeFormat = mapping.dateTimeFormat
        return try {
            if (dateTimeFormat != null) {
                // Single column holding a combined date+time value
                cachedDateTimeFormat(dateTimeFormat)
                    .parse(dateValue.trim())
                    .toInstant(timezone)
            } else {
                // Parse date and time using the specified formats
                parseDateTimeString(dateValue, mapping.dateFormat, timeValue, mapping.timeFormat, timezone)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDateTimeString(
        dateValue: String,
        dateFormat: String,
        timeValue: String,
        timeFormat: String?,
        timezone: TimeZone,
    ): Instant {
        val date = cachedDateFormat(dateFormat).parse(dateValue.trim())

        val time =
            if (timeValue.isBlank()) {
                LocalTime(12, 0, 0)
            } else {
                cachedTimeFormat(timeFormat ?: "HH:mm[:ss]").parse(timeValue.trim())
            }

        return LocalDateTime(date, time).toInstant(timezone)
    }

    private fun parseTimezone(
        mapping: FieldMapping?,
        values: List<String>,
    ): TimeZone =
        when (mapping) {
            is HardCodedTimezoneMapping -> TimeZone.of(mapping.timezoneId)
            is TimezoneLookupMapping -> {
                val tzId = getColumnValue(mapping.columnName, values).trim()
                if (tzId.isNotBlank()) TimeZone.of(tzId) else TimeZone.currentSystemDefault()
            }
            else -> TimeZone.currentSystemDefault()
        }

    private fun parseDescription(
        mapping: FieldMapping,
        values: List<String>,
    ): String =
        when (mapping) {
            is DirectColumnMapping -> getDirectColumnValue(mapping, values)
            else -> throw IllegalArgumentException("Invalid description mapping type: ${mapping::class}")
        }

    /**
     * Gets the effective value from a DirectColumnMapping,
     * trying the primary column first, then fallbacks in order.
     * When an [DirectColumnMapping.extraction] is configured, the resolved value is cleaned through it
     * (falling back to the raw value if the pattern doesn't match, so nothing is lost).
     */
    private fun getDirectColumnValue(
        mapping: DirectColumnMapping,
        values: List<String>,
    ): String {
        val raw =
            mapping.allColumns
                .map { getColumnValue(it, values) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        val extraction = mapping.extraction ?: return raw
        return applyExtraction(raw, extraction) ?: raw
    }

    /**
     * Resolves whether the counterparty (target) account for this row is a person, returning its
     * resolved account name when so (drives Person + ownership creation), or null otherwise.
     */
    private fun resolvePersonalCounterparty(
        mapping: FieldMapping,
        values: List<String>,
    ): String? =
        when (mapping) {
            is RegexAccountMapping -> {
                val result = getAccountNameFromRegexWithPattern(mapping, values)
                when {
                    !result.counterpartyIsPerson || result.accountName.isBlank() -> null
                    // A persisted account mapping intentionally remaps this counterparty (e.g. onto an
                    // existing account), so don't auto-create a Person/ownership from the regex name.
                    findPersistedMapping(result.sourceColumnValue) != null -> null
                    else -> result.accountName
                }
            }
            is ConditionalAccountMapping -> resolvePersonalCounterparty(resolveConditional(mapping, values), values)
            else -> null
        }

    /**
     * Runs [extraction]'s regex (case-insensitively) against [value] and substitutes its
     * [ColumnExtraction.outputTemplate] from the match. Returns null when the pattern doesn't match.
     */
    private fun applyExtraction(
        value: String,
        extraction: ColumnExtraction,
    ): String? {
        val match = compiledPattern(extraction.pattern).find(value) ?: return null
        return substituteTemplate(extraction.outputTemplate, match)
    }

    /**
     * Substitutes capture-group references in [template] from [match]. Supports `$0` (whole match),
     * `$1`..`$9` (numbered groups) and `${name}` (named groups). Unknown/absent groups become "".
     */
    private fun substituteTemplate(
        template: String,
        match: MatchResult,
    ): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val token = parseTemplateToken(template, i, match)
            if (token == null) {
                out.append(template[i])
                i++
            } else {
                out.append(token.first)
                i += token.second
            }
        }
        return out.toString()
    }

    /**
     * Parses a `$`-token at [start] in [template], returning (substituted value, characters consumed)
     * for `${name}` and `$0`..`$9`, or null when there is no token to substitute at this position.
     */
    private fun parseTemplateToken(
        template: String,
        start: Int,
        match: MatchResult,
    ): Pair<String, Int>? {
        if (template[start] != '$' || start + 1 >= template.length) return null
        val next = template[start + 1]
        if (next == '{') {
            val end = template.indexOf('}', startIndex = start + 2)
            if (end == -1) return null
            val value = (match.groups as? MatchNamedGroupCollection)?.get(template.substring(start + 2, end))?.value.orEmpty()
            return value to (end - start + 1)
        }
        if (next.isDigit()) return match.groupValues.getOrNull(next - '0').orEmpty() to 2
        return null
    }

    private fun parseCurrency(
        mapping: FieldMapping,
        values: List<String>,
    ): Asset? =
        when (mapping) {
            is HardCodedCurrencyMapping -> existingCurrencies[mapping.currencyId]
            is CurrencyLookupMapping -> {
                val code = getColumnValue(mapping.columnName, values).trim().uppercase()
                // Fiat first; fall back to crypto so a strategy can denominate a leg in a crypto asset.
                existingCurrenciesByCode[code] ?: existingCryptoByCode[code]
            }
            else -> throw IllegalArgumentException("Invalid currency mapping type: ${mapping::class}")
        }

    private fun getColumnValue(
        columnName: String,
        values: List<String>,
    ): String {
        val index =
            columnIndexByName[columnName]
                ?: throw IllegalArgumentException("Column not found: $columnName")
        return values.getOrNull(index).orEmpty()
    }

    private fun accountExists(name: String): Boolean = name in existingAccounts || name.lowercase() in historicalAccountNames

    /**
     * Resolves an account name to an id: the current account by name first, then a renamed account via
     * its former name (audit history). Returns null when neither matches (a new account is needed).
     */
    private fun resolveExistingAccountId(name: String): AccountId? = existingAccounts[name]?.id ?: historicalAccountNames[name.lowercase()]

    private fun parseBigDecimal(value: String): BigDecimal {
        val cleaned =
            value
                .trim()
                .replace(",", "") // Remove thousand separators
                .replace(" ", "")
                .replace("$", "")
                .replace("€", "")
                .replace("£", "")
        return BigDecimal(cleaned)
    }

    /**
     * Checks if a transfer is a duplicate or update of an existing transfer using unique identifiers.
     *
     * @param values CSV row values
     * @param transfer The newly mapped transfer
     * @param attributes The newly mapped attributes
     * @return Pair of (import status, existing transfer ID if found)
     */
    private fun checkForDuplicateByUniqueId(
        values: List<String>,
        transfer: Transfer,
        attributes: List<Pair<String, String>>,
    ): Pair<ImportStatus, TransferId?> {
        // Extract unique identifier values from current row
        val uniqueIdValues =
            uniqueIdentifierColumns.associateWith { columnName ->
                getColumnValueOrNull(columnName, values)?.trim().orEmpty()
            }

        // Look up existing transfer by unique identifiers
        val existingInfo =
            existingTransfersByUniqueId[uniqueIdValues]
                ?: return ImportStatus.IMPORTED to null

        // Found a match - now determine if it's identical (DUPLICATE) or different (UPDATED)
        val isIdentical = transfersAreIdentical(transfer, attributes, existingInfo.transfer, existingInfo.attributes)

        return if (isIdentical) {
            ImportStatus.DUPLICATE to existingInfo.transferId
        } else {
            ImportStatus.UPDATED to existingInfo.transferId
        }
    }

    /**
     * Checks if a transfer is a duplicate or update by comparing all fields against existing transfers.
     * Used when no unique identifiers are configured.
     *
     * @param transfer The newly mapped transfer
     * @param attributes The newly mapped attributes
     * @return Pair of (import status, existing transfer ID if found)
     */
    private fun checkForDuplicateByAllFields(
        transfer: Transfer,
        attributes: List<Pair<String, String>>,
    ): Pair<ImportStatus, TransferId?> {
        // First pass: an exact core-field match preserves the existing DUPLICATE/UPDATED distinction.
        for (existingInfo in existingTransfers) {
            val coreFieldsMatch =
                transfer.timestamp == existingInfo.transfer.timestamp &&
                    transfer.sourceAccountId == existingInfo.transfer.sourceAccountId &&
                    transfer.targetAccountId == existingInfo.transfer.targetAccountId &&
                    transfer.amount == existingInfo.transfer.amount &&
                    transfer.description == existingInfo.transfer.description

            if (coreFieldsMatch) {
                val attributesMatch = attributesAreIdentical(attributes, existingInfo.attributes)
                return if (attributesMatch) {
                    ImportStatus.DUPLICATE to existingInfo.transferId
                } else {
                    ImportStatus.UPDATED to existingInfo.transferId
                }
            }
        }

        // Second pass: tolerate the formatting drift in bank re-exports (different trailing text, a
        // posting date shifted by a day or two, and a different counterparty account derived from the
        // varying payee). Same amount + a shared account + close date + similar description = the same
        // transaction, so skip it as a duplicate rather than re-creating it.
        for (existingInfo in existingTransfers) {
            if (isFuzzyDuplicate(transfer, existingInfo.transfer)) {
                return ImportStatus.DUPLICATE to existingInfo.transferId
            }
        }

        // No match found
        return ImportStatus.IMPORTED to null
    }

    private fun isFuzzyDuplicate(
        transfer: Transfer,
        existing: Transfer,
    ): Boolean {
        if (transfer.amount != existing.amount) return false
        val sharesAccount =
            transfer.sourceAccountId == existing.sourceAccountId ||
                transfer.targetAccountId == existing.targetAccountId
        if (!sharesAccount) return false
        val withinDateTolerance =
            (transfer.timestamp - existing.timestamp).absoluteValue <= DUPLICATE_DATE_TOLERANCE
        if (!withinDateTolerance) return false
        return StringSimilarity.similarity(transfer.description, existing.description) >=
            DESCRIPTION_SIMILARITY_THRESHOLD
    }

    /**
     * Compares two transfers and their attributes to determine if they are identical.
     *
     * @param newTransfer The newly mapped transfer
     * @param newAttributes The newly mapped attributes
     * @param existingTransfer The existing transfer from database
     * @param existingAttributes The existing attributes from database
     * @return true if all fields and attributes match
     */
    private fun transfersAreIdentical(
        newTransfer: Transfer,
        newAttributes: List<Pair<String, String>>,
        existingTransfer: Transfer,
        existingAttributes: List<Pair<String, String>>,
    ): Boolean {
        // Compare all transfer fields (excluding ID which will always differ)
        if (newTransfer.timestamp != existingTransfer.timestamp) return false
        if (newTransfer.description != existingTransfer.description) return false
        if (newTransfer.sourceAccountId != existingTransfer.sourceAccountId) return false
        if (newTransfer.targetAccountId != existingTransfer.targetAccountId) return false
        if (newTransfer.amount != existingTransfer.amount) return false

        return attributesAreIdentical(newAttributes, existingAttributes)
    }

    /**
     * Compares two attribute lists to determine if they are identical (order-independent).
     */
    private fun attributesAreIdentical(
        newAttributes: List<Pair<String, String>>,
        existingAttributes: List<Pair<String, String>>,
    ): Boolean {
        val newAttrMap = newAttributes.toMap()
        val existingAttrMap = existingAttributes.toMap()

        if (newAttrMap.size != existingAttrMap.size) return false
        if (newAttrMap.keys != existingAttrMap.keys) return false

        return newAttrMap.all { (key, value) -> existingAttrMap[key] == value }
    }

    private companion object {
        /** Posting dates of the same transaction can drift between bank exports by a day or two. */
        val DUPLICATE_DATE_TOLERANCE: Duration = 3.days

        /** Collapses whitespace runs in template-derived account names. */
        val WHITESPACE_RUN_REGEX = Regex("\\s+")
    }
}
