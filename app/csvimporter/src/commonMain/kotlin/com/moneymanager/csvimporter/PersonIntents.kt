package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class PersonIntents(
    val people: List<ImportPersonIntent>,
    val ownerships: List<ImportOwnershipIntent>,
)

/**
 * Personal counterparties (resolved via person-flagged account namings in the strategy's
 * rules) become People with an ownership link to their counterparty account, in
 * addition to the account itself. The engine dedups people by name key and ownerships by
 * (person, account), so repeats across rows/files and re-imports collapse. Mirrors the QIF importer,
 * which reuses this same CsvTransferMapper-computed signal.
 *
 * The counterparty account id is read from the resolved transfer (the side that isn't the source
 * account) rather than by name lookup, so a counterparty that resolved to an existing account with
 * different casing/name still gets its Person + ownership. The value is paired with the first CSV
 * row that referenced the person, so the person's audit source links back to that exact row.
 */
internal fun personIntentsFor(
    validTransfers: List<CsvTransferWithAttributes>,
    selectedSourceAccountId: AccountId?,
    accountsByName: Map<String, Account>,
    csvImportId: CsvImportId,
): PersonIntents {
    val personLinks = LinkedHashMap<String, Pair<AccountId, Long>>()
    validTransfers.forEach { row ->
        val name = row.personalCounterpartyName?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val counterpartyAccountId =
            when (selectedSourceAccountId) {
                row.transfer.sourceAccountId -> row.transfer.targetAccountId
                row.transfer.targetAccountId -> row.transfer.sourceAccountId
                else -> accountsByName[name]?.id
            } ?: return@forEach
        val previous = personLinks[name]
        personLinks[name] =
            (previous?.first ?: counterpartyAccountId) to minOf(previous?.second ?: row.rowIndex, row.rowIndex)
    }
    val peopleToCreate =
        personLinks.map { (name, link) ->
            val parts = name.split(Regex("\\s+"))
            ImportPersonIntent(
                key = LocalPersonKey(name),
                match = PersonMatchKey.ByNameKey(normalizeNameKey(name)),
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" ").ifBlank { null },
                source = Source.Csv(csvImportId, link.second),
            )
        }
    val personOwnerships =
        personLinks.map { (name, link) ->
            ImportOwnershipIntent(
                personKey = LocalPersonKey(name),
                account = AccountRef.Existing(link.first),
                source = Source.Csv(csvImportId, link.second),
            )
        }
    return PersonIntents(peopleToCreate, personOwnerships)
}
