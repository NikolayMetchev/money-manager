package com.moneymanager.di.database

import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importer.ImportEngineImpl

/**
 * Builds an [ImportEngine] bound to [editGate] from the database component's write repositories. This
 * lives in di/core (which legitimately holds the write repositories) so the UI layer never has to —
 * the UI receives only the constructed [ImportEngine] plus read repositories. The session passes its
 * own [editGate] (e.g. a cloud-sync lock) here rather than relying on the always-writable DI binding.
 */
fun DatabaseComponent.createImportEngine(editGate: EditGate): ImportEngine =
    ImportEngineImpl(
        transactionRepository = transactionRepository,
        accountRepository = accountRepository,
        accountAttributeRepository = accountAttributeRepository,
        personRepository = personRepository,
        personAttributeRepository = personAttributeRepository,
        ownershipRepository = personAccountOwnershipRepository,
        categoryRepository = categoryRepository,
        currencyRepository = currencyRepository,
        attributeTypeRepository = attributeTypeRepository,
        relationshipTypeRepository = relationshipTypeRepository,
        csvImportStrategyRepository = csvImportStrategyRepository,
        apiImportStrategyRepository = apiImportStrategyRepository,
        accountMappingRepository = accountMappingRepository,
        csvImportRepository = csvImportRepository,
        qifImportRepository = qifImportRepository,
        apiSessionRepository = apiSessionRepository,
        settingsRepository = settingsRepository,
        importDirectoryRepository = importDirectoryRepository,
        passThroughAccountRepository = passThroughAccountRepository,
        editGate = editGate,
    )
