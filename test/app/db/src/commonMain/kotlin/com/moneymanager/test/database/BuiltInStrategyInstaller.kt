package com.moneymanager.test.database

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.builtin.BuiltInPassThroughs
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.createPassThroughAccount
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Installs the built-in strategy definitions a test needs into a fresh database, through the engine —
 * the same create-or-update path the catalog install uses. Fresh databases no longer seed strategies
 * (they are installed on demand from the strategy-library catalog), so tests that exercise the
 * built-ins call these from their setup.
 */
suspend fun DatabaseComponent.installBuiltInCsvStrategies() {
    val gbp = checkNotNull(currencyRepository.getCurrencyByCode("GBP").first()) { "GBP isn't installed" }
    for (strategy in BuiltInCsvStrategies.builtInCsvStrategies(Clock.System.now(), gbp.id)) {
        importEngine.createCsvStrategy(strategy)
    }
}

suspend fun DatabaseComponent.installBuiltInApiStrategies() {
    for (strategy in BuiltInApiStrategies.builtInApiStrategies(Clock.System.now())) {
        importEngine.createApiStrategy(strategy)
    }
}

suspend fun DatabaseComponent.installBuiltInPassThroughs() {
    for (definition in BuiltInPassThroughs.builtInPassThroughs()) {
        importEngine.createPassThroughAccount(definition)
    }
}
