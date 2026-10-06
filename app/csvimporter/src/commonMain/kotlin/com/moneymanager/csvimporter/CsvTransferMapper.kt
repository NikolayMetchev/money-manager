@file:OptIn(
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
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegSide
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.substituteTemplate
import com.moneymanager.importengineapi.DESCRIPTION_SIMILARITY_THRESHOLD
import com.moneymanager.importengineapi.ImportConversion
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

/** Which side of its event a leg is on (see `LegGroupRule`). */
enum class GroupLegSide { DEBIT, CREDIT }

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
    /** The fee transfer's description; null uses a generic one. */
    val feeDescription: String? = null,
    /** Credited leg of a cross-asset conversion; when set the row is imported as a `trade`. */
    val tradeTo: Money? = null,
    /** Counterparty account name when it is a person (drives Person + ownership creation); else null. */
    val personalCounterpartyName: String? = null,
    /** Pass-through (conduit) routing for the row (e.g. Curve); null for ordinary rows. */
    val passThrough: CsvPassThrough? = null,
    /** Set when the row is one leg of a multi-row movement (see `LegGroupRule`); null otherwise. */
    val groupLeg: GroupLeg? = null,
    /**
     * Raw value of the strategy's [CsvStrategyConfig.fundingAttributeMatch] column for this row (e.g. a
     * card's last-4 like "7721"); null when the strategy declares no funding match or the cell is blank.
     * The applier matches it against the funding attribute type to resolve the funding account.
     */
    val fundingMatchValue: String? = null,
    /**
     * Counterparty account the strategy could not identify (see
     * `ImportTransfer.unidentifiedCounterpartyAccountId`); null when it resolved a rule, a persisted
     * mapping or an identity for it.
     */
    val unidentifiedCounterpartyAccountId: AccountId? = null,
    /**
     * The statement account's conversion, when [transfer] is booked in the foreign currency it paid or
     * received (see `AmountParsingMapping.foreignAmount`); null otherwise.
     */
    val conversion: ImportConversion? = null,
)

/**
 * Result of preparing an import batch.
 *
 * @property validTransfers List of transfers to import with their status
 * @property errorRows Rows that failed to parse
 * @property newAccounts New accounts that need to be created
 * @property statusCounts Count of transfers by import status
 */
data class ImportPreparation(
    val validTransfers: List<CsvTransferWithAttributes>,
    val errorRows: List<MappingResult.Error>,
    val newAccounts: Set<NewAccount>,
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
     * resolves when a row carries its old name. See `AccountReadRepository.getPreviousAccountNames`.
     */
    historicalAccountNames: Map<String, AccountId> = emptyMap(),
    /** When set, overrides the strategy's SOURCE_ACCOUNT mapping for every row. */
    private val sourceAccountOverride: AccountId? = null,
    /** Detects pass-through (conduit) charges (e.g. Curve) from the row description; null disables it. */
    passThroughDetector: PassThroughDetector? = null,
    /**
     * Attribute-account matchers keyed by attribute-type name (see [AttributeAccountMatcher]). Consulted
     * by account rules with an `attributeTypeName` to resolve an account from a column value's regex
     * match against account attributes. The applier uses the same registry for funding reconciliation.
     */
    private val attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
) {
    private val columnIndexByName: Map<String, Int> =
        columns.associate { it.originalName to it.columnIndex }

    // A reconciliation source (see ReconciliationConfig) must only ever resolve onto its own shadow
    // accounts, which the caller passes as [existingAccounts] (see accountsVisibleTo). Persisted mappings
    // and former names pointing anywhere else are dropped so they can't route a row onto a real account,
    // and pass-through detection (which rewrites onto real conduit/merchant accounts) is off.
    private val isReconciliationSource = strategy.config.reconciliation != null
    private val visibleAccountIds: Set<AccountId> = existingAccounts.values.mapTo(mutableSetOf()) { it.id }

    private val historicalAccountNames: Map<String, AccountId> =
        if (isReconciliationSource) historicalAccountNames.filterValues { it in visibleAccountIds } else historicalAccountNames

    private val passThroughDetector: PassThroughDetector? = passThroughDetector.takeUnless { isReconciliationSource }

    // Holds the compiled-pattern cache: account rules, conditions and extractions run against every row.
    private val rules = RuleEvaluator()

    private fun compiledPattern(pattern: String): Regex = rules.regex(pattern)

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

    // Extract unique identifier column names from strategy
    private val uniqueIdentifierColumns: List<String> =
        strategy.config.attributeMappings
            .filter { it.isUniqueIdentifier }
            .map { it.columnName }

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
            .filter { !isReconciliationSource || it.accountId in visibleAccountIds }
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
                            groupLeg = result.groupLeg,
                            feeDescription = result.feeDescription,
                            fundingMatchValue = result.fundingMatchValue,
                            unidentifiedCounterpartyAccountId = result.unidentifiedCounterpartyAccountId,
                            conversion = result.conversion,
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

        return ImportPreparation(
            validTransfers = validTransfers,
            errorRows = errorRows,
            newAccounts = newAccounts,
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
                strategy.config.fieldMappings[TransferField.TARGET_ACCOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TARGET_ACCOUNT mapping")
            val timestampMapping =
                strategy.config.fieldMappings[TransferField.TIMESTAMP]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TIMESTAMP mapping")
            val descriptionMapping =
                strategy.config.fieldMappings[TransferField.DESCRIPTION]
                    ?: return MappingResult.Error(row.rowIndex, "Missing DESCRIPTION mapping")
            val amountMapping =
                strategy.config.fieldMappings[TransferField.AMOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing AMOUNT mapping")
            val currencyMapping =
                strategy.config.fieldMappings[TransferField.CURRENCY]
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
                    val toCurrencyMapping = strategy.config.fieldMappings[TransferField.TO_CURRENCY] ?: return@run null
                    val toAmountMapping = strategy.config.fieldMappings[TransferField.TO_AMOUNT] ?: return@run null
                    val toAsset = parseCurrency(toCurrencyMapping, values) ?: return@run null
                    if (toAsset.id == currency.id) return@run null
                    val toRaw = parseAmount(toAmountMapping, values).abs()
                    if (toRaw.compareTo(BigDecimal.ZERO) == 0) return@run null
                    Money.fromDisplayValue(toRaw, toAsset)
                }

            // Determine if we need to flip accounts (sign-based flip XOR preprocessing flip)
            val amountFlip = (amountMapping as? AmountParsingMapping)?.let { isIncoming(it.direction, rawAmount, values) } == true
            val flipAccounts = amountFlip xor rulesFlip

            // Resolve the source account: use override if provided, otherwise fall back to the
            // strategy's SOURCE_ACCOUNT mapping (if present), or return an error.
            val resolvedSourceAccountId: AccountId =
                if (sourceAccountOverride != null) {
                    sourceAccountOverride
                } else {
                    val sourceMapping =
                        strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]
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
            val legDetection = detectGroupLeg(values)
            val conversionDetection = legDetection?.takeIf { it.throughAccountName != null }
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
                strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]?.let {
                    sourceAccountId = parseAccount(it, values, applyPersistedMappings = false)
                    sourceUsedPersistedMappings = false
                }
            }

            // The counterparty is the account the TARGET_ACCOUNT mapping resolved, on whichever side the
            // flip below puts it.
            val counterpartyAccountId = targetAccountId

            if (flipAccounts) {
                val temp = sourceAccountId
                sourceAccountId = targetAccountId
                targetAccountId = temp
            }

            // Parse timezone (optional - defaults to system timezone)
            val timezoneMapping = strategy.config.fieldMappings[TransferField.TIMEZONE]
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

            // A counterparty the strategy could not identify — no rule matched, no persisted mapping, so
            // the account is just this row's description standing in for whoever the real other end was
            // (e.g. crypto.com's "GBP Deposit (via FPS)"). Recorded so the engine can reconcile the row
            // against a real record of the same movement instead of double-counting it. Pass-through
            // merchants, conversion legs and trades have counterparties of their own shape, so they are
            // out of scope.
            val unidentifiedCounterpartyAccountId =
                if (passThroughMatch != null || conversionDetection != null || tradeTo != null) {
                    null
                } else {
                    counterpartyAccountId.takeIf { isUnidentifiedCounterparty(targetMapping, values) }
                }

            // A fee is modelled as its own movement (linked to this transfer), not folded into the amount.
            val rowFee =
                when (val parsed = parseFee(amountMapping, values, currency)) {
                    FeeParse.UnknownCurrency -> return MappingResult.Error(row.rowIndex, "Fee currency not found")
                    is FeeParse.Charged -> parsed
                    FeeParse.None -> null
                }
            val feeAmount = rowFee?.amount
            // A gross amount already includes its fee: carve the fee out so the two sum back to it. Only a
            // fee in the row's own asset can be carved out of it.
            val netMagnitude =
                if (rowFee?.includedInAmount == true && rowFee.amount.asset.id == currency.id) {
                    (rawAmount.abs() - rowFee.magnitude).coerceAtLeast(BigDecimal.ZERO)
                } else {
                    rawAmount.abs()
                }

            // Create Money with absolute value (direction is indicated by source/target). A row the own
            // account settled in another currency moves the foreign amount; the settled one is its conversion.
            val settled = Money.fromDisplayValue(netMagnitude, currency)
            val foreign =
                if (tradeTo == null && legDetection == null) {
                    when (val parsed = parseForeignAmount(amountMapping, values, currency)) {
                        ForeignParse.UnknownCurrency -> return MappingResult.Error(row.rowIndex, "Foreign currency not found")
                        is ForeignParse.Foreign -> parsed.amount
                        ForeignParse.None -> null
                    }
                } else {
                    null
                }
            val amount = foreign ?: settled

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
                        strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]?.let {
                            add(discoverNewAccount(it, values, applyPersistedMappings = sourceUsedPersistedMappings))
                        }
                    }
                    // A conversion leg's counterparty is the shared conversion account (added below), not
                    // the description-derived account the target mapping would discover, so skip it here.
                    if (passThroughMatch == null && conversionDetection == null) add(discoverNewAccount(targetMapping, values))
                }
            val targetCategoryId = (targetMapping as? AccountRulesMapping)?.defaultCategoryId ?: Category.UNCATEGORIZED_ID
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
                groupLeg = legDetection?.leg,
                feeDescription = rowFee?.description,
                fundingMatchValue =
                    strategy.config.fundingAttributeMatch?.let {
                        getColumnValueOrNull(it.column, originalValues)?.trim()?.takeIf { v -> v.isNotBlank() }
                    },
                unidentifiedCounterpartyAccountId = unidentifiedCounterpartyAccountId,
                // The own account is the source of a payment and the target of a refund (flipped row).
                conversion = foreign?.let { ImportConversion(settled = settled, incoming = flipAccounts) },
            )
        } catch (expected: Exception) {
            MappingResult.Error(row.rowIndex, expected.message ?: "Unknown error")
        }
    }

    /**
     * Extracts attribute values from CSV row based on strategy.config.attributeMappings.
     * Skips attributes with blank values, and those whose conditions don't hold for the row.
     */
    private fun extractAttributes(values: List<String>): List<Pair<String, String>> {
        val record = ColumnRecord(values, columnIndexByName)
        return strategy.config.attributeMappings.mapNotNull { mapping ->
            if (!rules.all(mapping.conditions, record)) return@mapNotNull null
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

    private sealed interface ForeignParse {
        data object None : ForeignParse

        data object UnknownCurrency : ForeignParse

        data class Foreign(
            val amount: Money,
        ) : ForeignParse
    }

    /**
     * The row's foreign amount (see `AmountParsingMapping.foreignAmount`): [ForeignParse.None] when the
     * strategy declares none, the cells are blank, the amount is zero or the currency is the settled one.
     */
    private fun parseForeignAmount(
        amountMapping: FieldMapping,
        values: List<String>,
        settledCurrency: Asset,
    ): ForeignParse {
        val foreign = (amountMapping as? AmountParsingMapping)?.foreignAmount ?: return ForeignParse.None
        val lookup: (String) -> String? = { getColumnValueOrNull(it, values)?.trim() }
        val code = rules.resolve(foreign.currency, lookup).trim()
        val rawAmount = rules.resolve(foreign.amount, lookup).trim()
        if (code.isEmpty() || rawAmount.isEmpty()) return ForeignParse.None
        val asset = lookupAsset(code) ?: return ForeignParse.UnknownCurrency
        val magnitude = parseBigDecimal(rawAmount).abs()
        if (asset.id == settledCurrency.id || magnitude.compareTo(BigDecimal.ZERO) == 0) return ForeignParse.None
        return ForeignParse.Foreign(Money.fromDisplayValue(magnitude, asset))
    }

    /** A detected leg, plus the intermediate account a through-account leg routes via. */
    private data class LegDetection(
        val leg: GroupLeg,
        val throughAccountName: String?,
    ) {
        val accountName: String get() = checkNotNull(throughAccountName)
    }

    /**
     * The leg [values] is of the first of the strategy's `legGroups` that claims it: its leg conditions hold
     * (against trimmed cells), its side resolves, and — for a through-account rule — an account rule names
     * the intermediate account. Null when no rule claims the row.
     */
    private fun detectGroupLeg(values: List<String>): LegDetection? {
        val record = ColumnRecord(values, columnIndexByName)
        val trimmed = ColumnRecord(values.map { it.trim() }, columnIndexByName)
        return strategy.config.legGroups.withIndex().firstNotNullOfOrNull { (index, rule) ->
            if (!rules.all(rule.legWhen, trimmed)) return@firstNotNullOfOrNull null
            val side =
                when (val legSide = rule.side) {
                    is LegSide.DebitWhen -> if (rules.all(legSide.conditions, trimmed)) GroupLegSide.DEBIT else GroupLegSide.CREDIT
                    is LegSide.Sign -> {
                        val amount =
                            getColumnValueOrNull(legSide.path, values)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { parseBigDecimal(it) }.getOrNull() }
                        when {
                            amount == null -> null
                            amount < BigDecimal.ZERO -> GroupLegSide.DEBIT
                            amount > BigDecimal.ZERO -> GroupLegSide.CREDIT
                            else -> null
                        }
                    }
                } ?: return@firstNotNullOfOrNull null
            val throughAccountName =
                when (val assembly = rule.assembly) {
                    is LegAssembly.Trade -> null
                    is LegAssembly.ThroughAccount ->
                        assembly.accounts
                            .firstNotNullOfOrNull { applyAccountRule(it, record, values) }
                            ?.accountName
                            ?.takeIf { it.isNotBlank() }
                            ?: return@firstNotNullOfOrNull null
                }
            val key =
                rule.key.joinToString(
                    PAIRING_KEY_SEPARATOR,
                ) { part -> rules.resolve(part) { getColumnValueOrNull(it, values)?.trim() } }
            LegDetection(GroupLeg(index, side, key), throughAccountName)
        }
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
                    parseBigDecimal(getColumnValue(columnName, values))
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
     * Whether the row's money comes INTO the source (statement) account per [direction], which swaps
     * the source and target the mappings resolved.
     */
    private fun isIncoming(
        direction: Direction,
        rawAmount: BigDecimal,
        values: List<String>,
    ): Boolean =
        when (direction) {
            is Direction.AmountSign -> if (direction.positiveIsIncoming) rawAmount > BigDecimal.ZERO else rawAmount < BigDecimal.ZERO
            is Direction.Field -> getColumnValueOrNull(direction.path, values)?.trim() in direction.incomingValues
            Direction.Outgoing -> false
        }

    /** What a row's [com.moneymanager.domain.model.rules.FeeRule] came to. */
    private sealed interface FeeParse {
        data object None : FeeParse

        data object UnknownCurrency : FeeParse

        data class Charged(
            val magnitude: BigDecimal,
            val amount: Money,
            val description: String?,
            val includedInAmount: Boolean,
        ) : FeeParse
    }

    /**
     * The fee a row carries per the amount mapping's [com.moneymanager.domain.model.rules.FeeRule]: none when there is no rule, its conditions
     * don't hold, it is charged on another asset, or the amount is blank or zero. Its sign is ignored — a
     * fee is a movement out of the transaction's source account. The fee is in [rowCurrency] unless the
     * rule's currency reads a non-blank code of its own.
     */
    private fun parseFee(
        amountMapping: FieldMapping,
        values: List<String>,
        rowCurrency: Asset,
    ): FeeParse {
        val fee = (amountMapping as? AmountParsingMapping)?.fee ?: return FeeParse.None
        val lookup = { path: String -> getColumnValueOrNull(path, values)?.trim() }
        if (!rules.all(fee.conditions, ColumnRecord(values, columnIndexByName))) return FeeParse.None
        if (fee.chargedOnAsset?.let { !rules.resolve(it, lookup).equals(rowCurrency.code, ignoreCase = true) } == true) {
            return FeeParse.None
        }
        val feeValue = rules.resolve(fee.amount, lookup)
        if (feeValue.isBlank()) return FeeParse.None
        val magnitude = parseBigDecimal(feeValue).abs()
        if (magnitude <= BigDecimal.ZERO) return FeeParse.None
        val code = fee.currency?.let { rules.resolve(it, lookup) }.orEmpty()
        val feeCurrency = if (code.isBlank()) rowCurrency else lookupAsset(code) ?: return FeeParse.UnknownCurrency
        return FeeParse.Charged(
            magnitude = magnitude,
            amount = Money.fromDisplayValue(magnitude, feeCurrency),
            description = fee.description?.let { rules.resolve(it, lookup) }?.takeIf { it.isNotBlank() },
            includedInAmount = fee.includedInAmount,
        )
    }

    /**
     * Applies the strategy's row preprocessing rules to the raw row values.
     * Returns the (possibly column-swapped) values and whether source/target accounts must flip.
     */
    private fun applyRowPreprocessing(values: List<String>): Pair<List<String>, Boolean> {
        var effective = values
        var flip = false
        for (rule in strategy.config.rowPreprocessingRules) {
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
        condition: Condition,
        values: List<String>,
    ): Boolean = rules.matches(condition, ColumnRecord(values, columnIndexByName))

    /**
     * How one side's [AccountRulesMapping] named the account for a row: the [rule] that applied, the
     * [sourceValue] it read (what persisted account mappings match against), and the account it names —
     * [attributeAccountId] when an attribute rule matched an account, otherwise [accountName] (blank when
     * no rule applied or the value was blank).
     */
    private data class AccountResolution(
        val rule: AccountRule?,
        val sourceValue: String,
        val accountName: String,
        val attributeAccountId: AccountId? = null,
        val personName: String? = null,
    )

    // A row resolves each side several times (id, discovery, person, unidentified); the rules are
    // re-evaluated only when the row (or side) changes.
    private var lastResolution: Triple<List<String>, AccountRulesMapping, AccountResolution>? = null
    private var previousResolution: Triple<List<String>, AccountRulesMapping, AccountResolution>? = null

    private fun resolveAccount(
        mapping: AccountRulesMapping,
        values: List<String>,
    ): AccountResolution {
        for (cached in listOfNotNull(lastResolution, previousResolution)) {
            if (cached.first === values && cached.second === mapping) return cached.third
        }
        val resolution = evaluateAccountRules(mapping, values)
        previousResolution = lastResolution
        lastResolution = Triple(values, mapping, resolution)
        return resolution
    }

    private fun evaluateAccountRules(
        mapping: AccountRulesMapping,
        values: List<String>,
    ): AccountResolution {
        val record = ColumnRecord(values, columnIndexByName)
        return mapping.rules.firstNotNullOfOrNull { rule -> applyAccountRule(rule, record, values) }
            ?: AccountResolution(rule = null, sourceValue = "", accountName = "")
    }

    /** What [rule] resolves this row to, or null when the rule doesn't apply and the next one should be tried. */
    private fun applyAccountRule(
        rule: AccountRule,
        record: ColumnRecord,
        values: List<String>,
    ): AccountResolution? {
        if (!rules.all(rule.conditions, record)) return null
        val read =
            rule.value.paths
                .asSequence()
                .map { getColumnValue(it, values) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        val value = if (rule.trim) read.trim() else read
        val match = rule.pattern?.let { pattern -> value.takeIf { it.isNotBlank() }?.let { compiledPattern(pattern).find(it) } }
        if (rule.pattern != null && match == null) return null
        if (rule.attributeTypeName != null) {
            val matched =
                value.takeIf { it.isNotBlank() }?.let { attributeAccountMatchers[rule.attributeTypeName]?.match(it) } ?: return null
            return AccountResolution(rule, value, value, attributeAccountId = matched)
        }
        val accountName = renderAccountName(rule, rule.name, value, match).ifBlank { rule.fallbackName.orEmpty() }
        val personName =
            if (rule.counterpartyIsPerson) {
                rule.personName?.let { renderAccountName(rule, it, value, match) }?.takeIf { it.isNotBlank() } ?: accountName
            } else {
                null
            }
        return AccountResolution(rule, value, accountName, personName = personName)
    }

    /**
     * [template] rendered for [rule]: with a pattern, `$n`/`${name}` captures substituted (whitespace runs
     * collapsed when any were); `{value}` replaced by the value cleaned through the rule's extraction. A
     * pattern-less rule reading a blank value names nothing.
     */
    private fun renderAccountName(
        rule: AccountRule,
        template: String,
        value: String,
        match: MatchResult?,
    ): String {
        if (match == null && value.isBlank()) return ""
        val cleaned =
            (rule.value.extraction?.let { applyExtraction(value, it) } ?: value).let { if (rule.trim) it.trim() else it }
        val substituted = match?.let { substituteTemplate(template, it) } ?: template
        val named = substituted.replace(AccountRule.VALUE_PLACEHOLDER, cleaned)
        return if (match != null && '$' in template) named.replace(WHITESPACE_RUN_REGEX, " ").trim() else named
    }

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
    ): AccountId =
        when (mapping) {
            is HardCodedAccountMapping -> mapping.accountId
            is AccountRulesMapping -> {
                val resolution = resolveAccount(mapping, values)
                (if (applyPersistedMappings) findPersistedMapping(resolution.sourceValue) else null)
                    ?: resolution.attributeAccountId
                    ?: resolveExistingAccountId(resolution.accountName)
                    ?: UNRESOLVED_ACCOUNT_ID // Placeholder for new accounts
            }
            else -> throw IllegalArgumentException("Invalid account mapping type: ${mapping::class}")
        }

    /**
     * True when the rule that named [mapping]'s account for this row declares the counterparty
     * unidentified (see [AccountRule.counterpartyIsUnidentified]) and no persisted mapping claimed the
     * value — the user mapping the value to a real account is exactly what makes the counterparty known.
     * The account is then a placeholder for an unknown other end; see
     * `ImportTransfer.unidentifiedCounterpartyAccountId`.
     */
    private fun isUnidentifiedCounterparty(
        mapping: FieldMapping,
        values: List<String>,
    ): Boolean {
        val resolution = (mapping as? AccountRulesMapping)?.let { resolveAccount(it, values) } ?: return false
        return resolution.rule?.counterpartyIsUnidentified == true &&
            resolution.accountName.isNotBlank() &&
            findPersistedMapping(resolution.sourceValue) == null
    }

    /**
     * Finds a persisted account mapping whose pattern matches the given account source value
     * (the value the strategy's account rule read for this row). First matching mapping wins:
     * strategy-scoped mappings are tried before global ones, then id order — so mappings that share a
     * pattern resolve deterministically.
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
     * or null when no new account is needed (existing account, persisted mapping, attribute match, or a
     * blank name).
     */
    private fun discoverNewAccount(
        mapping: FieldMapping,
        values: List<String>,
        applyPersistedMappings: Boolean = true,
    ): Pair<NewAccount, DiscoveredAccountMapping>? {
        val rulesMapping = mapping as? AccountRulesMapping ?: return null
        val resolution = resolveAccount(rulesMapping, values)
        return when {
            applyPersistedMappings && findPersistedMapping(resolution.sourceValue) != null -> null
            resolution.attributeAccountId != null -> null
            resolution.accountName.isBlank() || accountExists(resolution.accountName) -> null
            else ->
                NewAccount(resolution.accountName, rulesMapping.defaultCategoryId) to
                    DiscoveredAccountMapping(
                        csvValue = resolution.sourceValue,
                        targetAccountName = resolution.accountName,
                        matchedPattern = resolution.rule?.pattern,
                    )
        }
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
                val value = getColumnValue(mapping.columnName, values).trim()
                val tzId =
                    mapping.aliases.entries
                        .firstOrNull { it.key.equals(value, ignoreCase = true) }
                        ?.value ?: value
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
     * The description a [DirectColumnMapping] reads (see [com.moneymanager.domain.model.rules.ValueExpr]).
     */
    private fun getDirectColumnValue(
        mapping: DirectColumnMapping,
        values: List<String>,
    ): String = rules.resolve(mapping.value) { getColumnValue(it, values) }

    /**
     * The person behind the counterparty (target) account for this row when its rule says it is one
     * (drives Person + ownership creation); null otherwise, or when a persisted account mapping
     * intentionally remaps the counterparty (e.g. onto an existing account).
     */
    private fun resolvePersonalCounterparty(
        mapping: FieldMapping,
        values: List<String>,
    ): String? {
        val resolution = (mapping as? AccountRulesMapping)?.let { resolveAccount(it, values) } ?: return null
        return resolution.personName
            ?.takeIf { it.isNotBlank() && findPersistedMapping(resolution.sourceValue) == null }
    }

    /** [extraction] applied to [value], or null when its pattern doesn't match. */
    private fun applyExtraction(
        value: String,
        extraction: Extraction,
    ): String? = rules.extract(value, extraction)

    private fun parseCurrency(
        mapping: FieldMapping,
        values: List<String>,
    ): Asset? =
        when (mapping) {
            is HardCodedCurrencyMapping -> existingCurrencies[mapping.currencyId]
            is CurrencyLookupMapping -> lookupAsset(rules.resolve(mapping.value) { getColumnValue(it, values).trim() })
            else -> throw IllegalArgumentException("Invalid currency mapping type: ${mapping::class}")
        }

    // Fiat first; fall back to crypto so a strategy can denominate a leg in a crypto asset.
    private fun lookupAsset(code: String): Asset? =
        strategy.config.assetCodes
            .canonical(code)
            .let { existingCurrenciesByCode[it] ?: existingCryptoByCode[it] }

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
        return withinDateTolerance &&
            StringSimilarity.similarity(transfer.description, existing.description) >=
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
