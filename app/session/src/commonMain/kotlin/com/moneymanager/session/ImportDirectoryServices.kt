package com.moneymanager.session

import com.moneymanager.database.di.AppServices
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importfilesource.ImportFileSourceFactory

/**
 * The [ImportDirectoryService] over a session's read services and engine. Build one per session and
 * share it: its write lock is what keeps concurrent directory runs from interleaving engine writes.
 */
fun AppServices.importDirectoryService(fileSourceFactory: ImportFileSourceFactory?): ImportDirectoryService =
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
        maintenance = imports.maintenance,
        importEngine = transactions.importEngine,
        fileSourceFactory = fileSourceFactory,
        deviceId = deviceId,
    )
