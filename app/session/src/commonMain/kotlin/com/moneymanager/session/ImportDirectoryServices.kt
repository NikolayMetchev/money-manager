package com.moneymanager.session

import com.moneymanager.database.di.AppServices
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importfilesource.ImportFileSourceFactory
import kotlinx.coroutines.sync.Mutex

/**
 * The [ImportDirectoryService] over a session's read services and engine. Build one per session and
 * share it. [writeLock] keeps concurrent runs from interleaving engine writes: pass the same lock to
 * [apiImportService], so directory and API imports running at once take turns too.
 */
fun AppServices.importDirectoryService(
    fileSourceFactory: ImportFileSourceFactory?,
    writeLock: Mutex,
): ImportDirectoryService =
    ImportDirectoryService(
        importDirectoryRepository = imports.importDirectoryRepository,
        csvImportRepository = imports.csvImportRepository,
        qifImportRepository = imports.qifImportRepository,
        csvImportStrategyRepository = imports.csvImportStrategyRepository,
        accountMappingRepository = imports.accountMappingRepository,
        accountRepository = accounts.accountRepository,
        accountAttributeRepository = accounts.accountAttributeRepository,
        currencyRepository = accounts.currencyRepository,
        cryptoRepository = accounts.cryptoRepository,
        tradeRepository = transactions.tradeRepository,
        passThroughAccountRepository = imports.passThroughAccountRepository,
        settingsRepository = settings.settingsRepository,
        transactionRepository = transactions.transactionRepository,
        transferRelationshipRepository = transactions.transferRelationshipRepository,
        transferSourceRepository = transactions.transferSourceRepository,
        auditRepository = audit.auditRepository,
        maintenance = imports.maintenance,
        importEngine = transactions.importEngine,
        fileSourceFactory = fileSourceFactory,
        deviceId = deviceId,
        writeLock = writeLock,
    )
