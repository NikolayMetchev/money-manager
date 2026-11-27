package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
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
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.importengineapi.ImportConversion
import com.moneymanager.importengineapi.IncludedFee
import com.moneymanager.importengineapi.PassThroughDetector

class TransferMapper(
    private val strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    private val existingAccounts: Map<String, Account>,
    existingCurrencies: Map<CurrencyId, Currency>,
    existingCurrenciesByCode: Map<String, Currency>,
    /** Crypto assets keyed by uppercased ticker; consulted when a code isn't a known fiat currency. */
    existingCryptoByCode: Map<String, CryptoAsset> = emptyMap(),
    /** The database's own asset aliases, as alias to asset code; see [resolveAssetCode]. */
    assetAliases: Map<String, String> = emptyMap(),
    existingTransfers: List<ExistingTransferInfo> = emptyList(),
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
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    /** The file the rows came from, which rules read as `FILE_NAME_COLUMN`. */
    private val fileName: String? = null,
    /**
     * The full names of each account's owners (see `AccountReadRepository.getAccountOwnerNames`). A personal
     * counterparty who owns the row's own account is its holder paying themselves, from or to an account the row
     * can't name, so the counterparty is a placeholder (see [StatementAccounts.isOwnHolder]).
     */
    accountOwnerNames: Map<AccountId, List<String>> = emptyMap(),
) {
    // The file's columns, then any a rule sets that the file lacks (see CsvStrategyConfig.ruleColumns): those read
    // as absent until a rule sets them, and the mappings read them like any other.
    private val columnIndexByName: Map<String, Int> = columnIndexWithRuleColumns(columns, strategy.config)
    private val passThroughDetector: PassThroughDetector? = passThroughDetector.takeUnless { strategy.config.reconciliation != null }

    // Holds the compiled-pattern cache: the strategy's rules, conditions and extractions run against every row.
    private val engine = RuleEngine()
    private val rules = engine.evaluator
    private val amounts =
        StatementAmounts(strategy.config, rules, existingCurrencies, existingCurrenciesByCode, existingCryptoByCode, assetAliases)
    private val dates = StatementDates(rules)
    private val duplicates = DuplicateTransfers(strategy.config, existingTransfers)
    private val accounts =
        StatementAccounts(
            strategy,
            existingAccounts,
            historicalAccountNames,
            accountMappings,
            accountOwnerNames,
            attributeAccountMatchers,
            rules,
        )

    fun prepareImport(rows: List<CsvRow>): ImportPreparation {
        val validTransfers = mutableListOf<MappingResult.Success>()
        val errorRows = mutableListOf<MappingResult.Error>()
        val skippedRowIndexes = mutableListOf<Long>()
        val newAccounts = mutableSetOf<NewAccount>()
        val statusCounts = mutableMapOf<ImportStatus, Int>()

        for (row in rows) {
            when (val result = mapRow(row)) {
                is MappingResult.Success -> {
                    validTransfers.add(result)
                    statusCounts[result.importStatus] = (statusCounts[result.importStatus] ?: 0) + 1

                    newAccounts.addAll(result.newAccounts)
                }

                is MappingResult.Error -> {
                    errorRows.add(result)
                }

                is MappingResult.Skipped -> {
                    skippedRowIndexes.add(result.rowIndex)
                }
            }
        }

        return ImportPreparation(
            validTransfers = validTransfers,
            errorRows = errorRows,
            newAccounts = newAccounts,
            statusCounts = statusCounts,
            skippedRowIndexes = skippedRowIndexes,
        )
    }

    private fun requiredMapping(field: TransferField) = strategy.config.fieldMappings[field] ?: error("Missing $field mapping")

    fun mapRow(row: CsvRow): MappingResult {
        return try {
            // The rules see the row as the CSV has it; field parsing reads the row as they left it (cells
            // swapped or set). Attributes are claimed by whichever rule tags them, so a strategy that wants
            // the original cells tags them before rewriting any.
            val originalValues = row.values.withFileName(columnIndexByName, fileName)
            val outcome = engine.evaluate(strategy.config.rules, ColumnRecord(originalValues, columnIndexByName))
            if (outcome.skipped) return MappingResult.Skipped(row.rowIndex)
            val values =
                RowValues(outcome.rewrittenValues(originalValues, columnIndexByName), columnIndexByName, strategy.config.optionalColumns)
            val targetMapping = requiredMapping(TransferField.TARGET_ACCOUNT)
            val timestampMapping = requiredMapping(TransferField.TIMESTAMP)
            val descriptionMapping = requiredMapping(TransferField.DESCRIPTION)
            val amountMapping = requiredMapping(TransferField.AMOUNT)
            val currencyMapping = requiredMapping(TransferField.CURRENCY)

            val rawAmount = amounts.parseAmount(amountMapping, values)

            val currency =
                amounts.parseCurrency(currencyMapping, values)
                    ?: return MappingResult.Error(row.rowIndex, "Currency not found")

            val rowFee = amounts.parseFee(outcome.fees, values, currency)
            val includedFee =
                IncludedFee(
                    rawAmount,
                    rowFee?.magnitude?.takeIf {
                        rowFee.includedInAmount &&
                            rowFee.amount.asset.id == currency.id
                    },
                )
            val wholeFee = includedFee.isWhole
            val tradeTo = amounts.tradeTo(currencyMapping, currency, rawAmount, values)
            // An incoming row swaps the source and target the mappings resolved; a rule's flip toggles that.
            val flipAccounts = (outcome.incoming == true) xor outcome.flipped

            val sides =
                RowAccounts(
                    accounts,
                    strategy.config,
                    outcome,
                    values,
                    rules,
                    sourceAccountOverride,
                    targetMapping,
                    tradeTo != null,
                    wholeFee,
                )
            val legDetection = sides.group
            val timezoneMapping = strategy.config.fieldMappings[TransferField.TIMEZONE]
            val timezone = dates.parseTimezone(timezoneMapping, values)

            val timestamp =
                dates.parseTimestamp(timestampMapping as DateTimeParsingMapping, values, timezone)
                    ?: return MappingResult.Error(row.rowIndex, "Failed to parse timestamp")
            require(descriptionMapping is DirectColumnMapping) { "Invalid description mapping type: ${descriptionMapping::class}" }
            val description = rules.resolve(descriptionMapping.value) { values.required(it) }
            val passThroughMatch = passThroughDetector?.detect(description)
            // A row a rule runs through a conduit (RuleAction.ThroughConduit): the row's counterparty becomes
            // the merchant at the end of the chain and the conduit takes its place on the transfer, ahead of
            // any conduits the description itself names.
            val standalone = tradeTo == null && legDetection == null
            val strategyConduit = outcome.conduitAccountName?.takeIf { standalone }
            val passThrough =
                accounts.passThrough(passThroughMatch, strategyConduit, description, sides, flipAccounts)
            val otherAccount =
                passThrough?.let { accounts.resolveExistingAccountId(it.conduitName) ?: UNRESOLVED_ACCOUNT_ID } ?: sides.counterpartyId
            val effectiveSourceAccountId = if (flipAccounts) otherAccount else sides.ownId
            val effectiveTargetAccountId = if (flipAccounts) sides.ownId else otherAccount
            accounts.requireVisible(effectiveSourceAccountId)
            accounts.requireVisible(effectiveTargetAccountId)
            // Single-account trades and uncreated accounts can legitimately share an ID.
            val tradeLegOfOneAccount = legDetection?.leg?.let { strategy.config.legGroups[it.ruleIndex].isCrossFileTrade() } == true
            if (tradeTo == null &&
                !tradeLegOfOneAccount &&
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

            val detectedConduit = passThroughMatch != null
            val personalCounterpartyName = sides.personalCounterparty(detectedConduit)
            val unidentifiedCounterpartyAccountId =
                sides.unidentifiedCounterparty(
                    strategyConduit,
                    detectedConduit,
                    personalCounterpartyName,
                )
            val settled = Money.fromDisplayValue(includedFee.netMagnitude, currency)
            val foreign =
                if (standalone) {
                    amounts.parseForeignAmount(outcome.foreignAmount, values, currency)
                } else {
                    null
                }
            val amount = foreign ?: settled

            val transfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = effectiveSourceAccountId,
                    targetAccountId = effectiveTargetAccountId,
                    amount = amount,
                )

            val attributes =
                outcome.attributes.map { it.typeName to it.value } +
                    listOfNotNull(outcome.excludedValue?.let { EXCLUDED_ATTRIBUTE_TYPE_NAME to it })
            val (importStatus, existingTransferId) = duplicates.classify(transfer, attributes)
            val (newAccounts, discoveredMappings) = sides.discover(passThrough, detectedConduit)

            MappingResult.Success(
                rowIndex = row.rowIndex,
                transfer = transfer,
                newAccounts = newAccounts,
                attributes = attributes,
                importStatus = importStatus,
                existingTransferId = existingTransferId,
                discoveredMappings = discoveredMappings,
                feeAmount = rowFee?.amount?.takeUnless { wholeFee },
                tradeTo = tradeTo,
                personalCounterpartyName = personalCounterpartyName,
                passThrough = passThrough,
                groupLeg = legDetection?.leg,
                feeDescription = rowFee?.description?.takeUnless { wholeFee },
                fundingMatchValue =
                    strategy.config.fundingAttributeMatch?.let {
                        values.record
                            .text(it.column)
                            ?.trim()
                            ?.takeIf { v -> v.isNotBlank() }
                    },
                unidentifiedCounterpartyAccountId = unidentifiedCounterpartyAccountId,
                conversion = foreign?.let { ImportConversion(settled = settled, incoming = flipAccounts) },
                statementAccountId = sides.ownId,
                reportedBalance = amounts.parseBalance(outcome.balance, values, currency),
                reportedBalanceAt =
                    outcome.balance?.at?.let { at ->
                        dates.parseBalanceAt(at, timestampMapping, values, timezone)
                    },
            )
        } catch (expected: Exception) {
            MappingResult.Error(row.rowIndex, expected.message ?: "Unknown error")
        }
    }
}
