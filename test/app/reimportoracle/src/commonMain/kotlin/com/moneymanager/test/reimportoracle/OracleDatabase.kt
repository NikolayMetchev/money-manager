package com.moneymanager.test.reimportoracle

import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.executeApiReimport
import com.moneymanager.apiimporter.executeApiUnimport
import com.moneymanager.apiimporter.importApiSession
import com.moneymanager.apiimporter.planApiReimport
import com.moneymanager.apiimporter.planApiUnimport
import com.moneymanager.cryptodata.BundledCryptoCatalog
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvUnimport
import com.moneymanager.csvimporter.planCsvUnimport
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.reconciliation.IGNORED_IN_RECONCILIATION
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.importengineapi.AccountAttributeEdit
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.ManualEntryInput
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.createManualEntries
import com.moneymanager.importengineapi.createReconciliationMatch
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.insertApiRequest
import com.moneymanager.importengineapi.insertApiResponse
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.importengineapi.setAccountAttributeValues
import com.moneymanager.importengineapi.setTransferExcluded
import com.moneymanager.importengineapi.updateApiStrategy
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInApiStrategies
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.installBuiltInPassThroughs
import com.moneymanager.test.database.reimportFile
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.runPlan
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Every file of the corpus claims this modification time, so nothing a run writes depends on the clock. */
private val FILE_TIME = Instant.parse("2026-01-01T00:00:00Z")

/** The note a hand edit adds, and the attribute type it's stored under. */
const val ORACLE_NOTE_TYPE = "note"
const val ORACLE_NOTE = "checked by hand"

/** The attribute the strategy change of assertion (c) adds to every record of the changed strategy. */
const val ORACLE_STRATEGY_ATTRIBUTE = "oracle-strategy"

/**
 * A fresh database for one run of a scenario, with the built-in strategies installed and the scenario's accounts and
 * cards set up by hand, as a person would before pointing the import folders at their files.
 */
class OracleDatabase private constructor(
    /** The open database itself, for the queries only tests make. */
    val database: MoneyManagerDatabaseWrapper,
    val db: DatabaseComponent,
    val scenario: OracleScenario,
) {
    private val staged = mutableMapOf<OracleSource, CsvImportId>()
    private val sessions = mutableMapOf<OracleSource, ApiSessionId>()

    // Each session needs its own creation time: the engine keys a new session by it.
    private var sessionsCreated = 0

    /** Stages and applies each of [sources], one file at a time in the order given, as the import folders do. */
    suspend fun import(sources: List<OracleSource> = scenario.sources) {
        for (source in sources) {
            if (source.file is OracleFile.Api) {
                importSession(source, source.file)
                continue
            }
            val id = stage(source.file)
            staged[source] = id
            val file = requireNotNull(db.csvImportRepository.getImport(id).first())
            val result = apply(listOf(file), source.account?.let { accountId(it) })
            check(result.filesFailed == 0 && result.filesImported == 1) { "${source.file.fileName} did not import: ${result.files}" }
        }
    }

    /** Re-imports each of [sources], one at a time in the order given, as the re-import dialog does for one file. */
    suspend fun reimport(sources: List<OracleSource>) {
        val reimports = db.reimports()
        for (source in sources) {
            if (source.file is OracleFile.Api) {
                reimportSession(source, source.file)
                continue
            }
            val result = reimports.reimportFile(requireNotNull(staged[source]) { "${source.file.fileName} was never imported" })
            check(result.filesFailed == 0) { "${source.file.fileName} did not re-import: $result" }
        }
    }

    /** Re-imports every one of [sources] in one run, as "Re-import all" does. */
    suspend fun reimportAll(sources: List<OracleSource> = scenario.sources) {
        val reimports = db.reimports()
        val files =
            sources.map { source ->
                val id = requireNotNull(staged[source]) { "${source.file.fileName} was never imported" }
                requireNotNull(db.csvImportRepository.getImport(id).first())
            }
        val result = reimports.runPlan(reimports.planAll(files))
        check(result.filesFailed == 0) { "re-import all failed: $result" }
    }

    /** Unimports [source], as the Unimport button does: its records are withdrawn and what nothing else observes goes. */
    suspend fun unimport(source: OracleSource) {
        if (source.file is OracleFile.Api) {
            executeApiUnimport(
                plan =
                    planApiUnimport(
                        requireNotNull(sessions[source]) { "${source.file.fileName} was never imported" },
                        db.apiSessionRepository,
                    ),
                accountRepository = db.accountRepository,
                transactionRepository = db.transactionRepository,
                relationshipRepository = db.relationshipRepository,
                tradeRepository = db.tradeRepository,
                maintenance = db.maintenanceService,
                importEngine = db.importEngine,
                refreshViews = false,
            )
            return
        }
        val plan =
            planCsvUnimport(
                requireNotNull(staged[source]) { "${source.file.fileName} was never imported" },
                db.csvImportRepository,
                db.transferSourceRepository,
            )
        executeCsvUnimport(
            plan = plan,
            accountRepository = db.accountRepository,
            transactionRepository = db.transactionRepository,
            relationshipRepository = db.relationshipRepository,
            tradeRepository = db.tradeRepository,
            maintenance = db.maintenanceService,
            importEngine = db.importEngine,
            refreshViews = false,
        )
    }

    /** Assertion (c)'s strategy change: [OracleScenario.changedStrategy] tags every record it imports. */
    suspend fun changeStrategy() {
        val tag = StrategyRule(actions = listOf(RuleAction.Attribute(ORACLE_STRATEGY_ATTRIBUTE, RuleValue.constant("v2"))))
        val csv =
            db.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .singleOrNull { it.name == scenario.changedStrategy }
        if (csv != null) {
            db.importEngine.updateCsvStrategy(csv.copy(config = csv.config.copy(rules = csv.config.rules + tag)))
            return
        }
        // An API strategy tags what every endpoint that books transfers imports.
        val api = requireNotNull(db.apiImportStrategyRepository.getStrategyByName(scenario.changedStrategy).first())
        val endpoints =
            api.config.dataEndpoints.map { endpoint ->
                endpoint.transactionMappings?.let { endpoint.copy(transactionMappings = it.copy(rules = it.rules + tag)) } ?: endpoint
            }
        db.importEngine.updateApiStrategy(api.copy(config = api.config.copy(dataEndpoints = endpoints)))
    }

    /**
     * Assertion (d)'s hand work, chosen by value so it lands on the same records in any database holding the same
     * money: a hand edit (new description and a note), a hand-made match of two records (a reconciliation source's
     * row and a real one when the scenario has a source), an Ignore of a reconciliation source's row, and the
     * scenario's Manual Entries. Returns the transfers it edited or matched, which a re-import must keep under their ids (#1430).
     */
    suspend fun doHandWork(): Set<TransferId> {
        val accounts = db.accountRepository.getAllAccounts().first()
        val shadowIds =
            db.accountAttributeRepository
                .getAll()
                .first()
                .filter { it.attributeType.id.id == WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID }
                .map { it.accountId }
                .toSet()
        val transfers = byValue(accounts.flatMap { db.transactionRepository.getTransactionsByAccount(it.id).first() }.distinctBy { it.id })
        val counted = transfers.filter { !it.isExcluded() }
        val real = counted.filter { it.sourceAccountId !in shadowIds && it.targetAccountId !in shadowIds }
        val shadow = counted.filter { it.sourceAccountId in shadowIds || it.targetAccountId in shadowIds }

        val edited = real.first()
        val noteType = db.importEngine.getOrCreateAttributeType(ORACLE_NOTE_TYPE)
        db.importEngine.import(
            ImportBatch.manualEdits(
                transfers =
                    listOf(
                        ImportTransfer(
                            source = Source.Manual,
                            operation = ImportOperation.UPDATE,
                            existingId = edited.id,
                            fromAccount = AccountRef.Existing(edited.sourceAccountId),
                            toAccount = AccountRef.Existing(edited.targetAccountId),
                            timestamp = edited.timestamp,
                            description = "${edited.description} (edited)",
                            amount = edited.amount,
                            attributes = listOf(NewAttribute(noteType, ORACLE_NOTE)),
                        ),
                    ),
            ),
        )

        val matched = if (shadow.isNotEmpty()) listOf(shadow.first(), real.last()) else real.takeLast(2)
        if (matched.size == 2 && matched[0].id != matched[1].id) {
            db.importEngine.createReconciliationMatch(matched.map { it.id })
        }

        if (shadow.size > 1) {
            db.importEngine.setTransferExcluded(shadow.last(), IGNORED_IN_RECONCILIATION)
        }

        for (entry in scenario.manualEntries) {
            val pending = db.transactionRepository.getPendingManualEntries(entry.name, entry.relationshipType).first()
            db.importEngine.createManualEntries(
                pending.map { ManualEntryInput(it, Money.fromDisplayValue(entry.amount, it.amount.asset)) },
                description = entry.description,
                relationshipType = entry.relationshipType,
                excludedFromBalances = entry.excludedFromBalances,
            )
        }
        return setOf(edited.id) + matched.map { it.id }
    }

    private fun byValue(transfers: List<Transfer>): List<Transfer> =
        transfers.sortedWith(compareBy({ it.timestamp }, { it.description }, { it.amount.toDisplayValue().toString() }))

    private fun Transfer.isExcluded(): Boolean = attributes.any { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }

    private suspend fun stage(file: OracleFile): CsvImportId =
        when (file) {
            is OracleFile.Csv -> {
                db.csvImportRepository.createImport(
                    fileName = file.fileName,
                    headers = file.headers,
                    rows = file.rows,
                    fileChecksum = "checksum-${file.fileName}",
                    fileLastModified = FILE_TIME,
                )
            }

            is OracleFile.Qif -> {
                db.importEngine.stageQifImport(file.fileName, file.content, "checksum-${file.fileName}", FILE_TIME)
            }

            is OracleFile.Api -> {
                error("an API session is not a staged file")
            }
        }

    /** Downloads [file] as a new session of its strategy's connection (its responses as given) and imports it. */
    private suspend fun importSession(
        source: OracleSource,
        file: OracleFile.Api,
    ) {
        val strategy = apiStrategy(file)
        val credential = db.importEngine.ensureApiCredential(strategy.id, FILE_TIME)
        val sessionId = db.importEngine.createApiSession(db.deviceId, FILE_TIME + (++sessionsCreated).seconds, credential)
        for ((url, json) in file.responses) {
            val request = db.importEngine.insertApiRequest(sessionId, "GET", url, emptyMap())
            db.importEngine.insertApiResponse(request, sessionId, json)
        }
        importApiSession(
            apiReads(),
            sessionId,
            strategy,
            db.importEngine,
            passThroughAccounts = db.passThroughAccountRepository.getAll().first(),
        )
        db.importEngine.markApiSessionImported(sessionId, strategy.revisionId, FILE_TIME)
        sessions[source] = sessionId
    }

    /** Re-imports [file]'s session, as the API screen's Re-import does. */
    private suspend fun reimportSession(
        source: OracleSource,
        file: OracleFile.Api,
    ) {
        val sessionId = requireNotNull(sessions[source]) { "${file.fileName} was never imported" }
        val reimports = db.reimports()
        executeApiReimport(
            plan = planApiReimport(sessionId, db.apiSessionRepository, reimports.reads),
            session = requireNotNull(db.apiSessionRepository.getSessionById(sessionId)),
            strategy = apiStrategy(file),
            apiReads = apiReads(),
            reads = reimports.reads,
            maintenance = db.maintenanceService,
            importEngine = db.importEngine,
            cascadeRerunner = reimports,
            passThroughAccounts = db.passThroughAccountRepository.getAll().first(),
            refreshViews = false,
        )
    }

    private suspend fun apiStrategy(file: OracleFile.Api): ApiImportStrategy =
        requireNotNull(db.apiImportStrategyRepository.getStrategyByName(file.strategyName).first()) { "No ${file.strategyName} strategy" }

    private fun apiReads(): ApiImportReads =
        ApiImportReads(
            apiSessionRepository = db.apiSessionRepository,
            accountRepository = db.accountRepository,
            accountAttributeRepository = db.accountAttributeRepository,
            currencyRepository = db.currencyRepository,
            cryptoRepository = db.cryptoRepository,
            importRecordRepository = db.importRecordRepository,
        )

    /** One `bulkApplyCsv` call with every argument the import folders pass it. */
    private suspend fun apply(
        files: List<CsvImport>,
        account: AccountId?,
    ): BulkApplyResult =
        bulkApplyCsv(
            imports = files,
            sourceAccountOverride = account,
            strategies = db.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = db.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = db.accountMappingRepository,
            accountRepository = db.accountRepository,
            csvImportRepository = db.csvImportRepository,
            maintenance = db.maintenanceService,
            importEngine = db.importEngine,
            onProgress = {},
            passThroughAccounts = db.passThroughAccountRepository.getAll().first(),
            cryptoRepository = db.cryptoRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(db.accountAttributeRepository.getAll().first()),
            tradeRepository = db.tradeRepository,
            refreshViews = false,
        )

    private suspend fun accountId(name: String): AccountId =
        db.accountRepository
            .getAllAccounts()
            .first()
            .single { it.name == name }
            .id

    private suspend fun setUp() {
        for (name in scenario.accounts) {
            db.importEngine.createAccount(Account(id = AccountId(0), name = name, openingDate = FILE_TIME), Source.Manual)
        }
        if (scenario.cardLast4.isNotEmpty()) {
            db.importEngine.setAccountAttributeValues(
                scenario.cardLast4.map { (account, last4) ->
                    AccountAttributeEdit(
                        accountId = accountId(account),
                        typeId = AttributeTypeId(WellKnownIds.ACCOUNT_CARD_LAST4_ATTR_TYPE_ID),
                        value = last4,
                        existingAttributeId = null,
                    )
                },
            )
        }
    }

    companion object {
        /**
         * Runs [block] on the database at [location] (a fresh one unless given, such as a copy of a benchmark's) set up
         * for [scenario], deleted afterwards.
         */
        suspend fun <T> with(
            scenario: OracleScenario,
            location: DbLocation = createTestDatabaseLocation(),
            block: suspend OracleDatabase.() -> T,
        ): T {
            val wrapper = createTestDatabaseManager().openDatabase(location)
            try {
                val db = openDatabaseComponent(wrapper)
                // Built-ins read the catalog's aliases (Kraken's XXBT, Koinly's KNCL), as they do in the app.
                CryptoRegistry.install(BundledCryptoCatalog)
                db.installBuiltInCsvStrategies()
                db.installBuiltInApiStrategies()
                db.installBuiltInPassThroughs()
                val oracle = OracleDatabase(wrapper, db, scenario)
                oracle.setUp()
                return block(oracle)
            } finally {
                wrapper.close()
                CryptoRegistry.resetForTest()
                deleteTestDatabase(location)
            }
        }
    }
}
