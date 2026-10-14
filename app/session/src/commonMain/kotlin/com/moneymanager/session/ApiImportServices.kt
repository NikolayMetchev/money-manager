package com.moneymanager.session

import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.database.di.AppServices

/** The [ApiImportService] over a session's read services and engine, taking API secrets from [credentialVault]. */
fun AppServices.apiImportService(credentialVault: CredentialVault): ApiImportService =
    ApiImportService(
        apiSessionRepository = imports.apiSessionRepository,
        apiImportStrategyRepository = imports.apiImportStrategyRepository,
        accountRepository = accounts.accountRepository,
        accountAttributeRepository = accounts.accountAttributeRepository,
        currencyRepository = accounts.currencyRepository,
        cryptoRepository = accounts.cryptoRepository,
        passThroughAccountRepository = imports.passThroughAccountRepository,
        maintenance = imports.maintenance,
        importEngine = transactions.importEngine,
        credentialVault = credentialVault,
        deviceId = deviceId,
    )
