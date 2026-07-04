@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.test.database

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.builtin.BuiltInPassThroughs
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.createPassThroughAccount
import kotlin.time.Clock

/**
 * Installs the built-in strategy definitions a test needs into a fresh database, through the engine —
 * the same create-or-update path the catalog install uses. Fresh databases no longer seed strategies
 * (they are installed on demand from the strategy-library catalog), so tests that exercise the
 * built-ins call these from their setup.
 */
suspend fun DatabaseComponent.installBuiltInCsvStrategies() {
    for (strategy in BuiltInCsvStrategies.builtInCsvStrategies(Clock.System.now(), GBP_CURRENCY_ID)) {
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

// GBP is statically seeded with fixed id 1 (StaticSeed.sq); the QIF built-ins pre-select it.
private val GBP_CURRENCY_ID = CurrencyId(1)
