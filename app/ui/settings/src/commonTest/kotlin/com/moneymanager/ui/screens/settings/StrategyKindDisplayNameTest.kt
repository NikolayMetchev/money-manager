package com.moneymanager.ui.screens.settings

import com.moneymanager.domain.strategy.StrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals

class StrategyKindDisplayNameTest {
    @Test
    fun theAssetListAndADatabasesOwnAssetsReadDifferently() {
        assertEquals("Currencies, crypto assets and their aliases", StrategyKind.ASSET_LIST.displayName())
        assertEquals("Your own assets and aliases", StrategyKind.ASSETS.displayName())
        assertEquals(
            StrategyKind.entries.size,
            StrategyKind.entries
                .map { it.displayName() }
                .toSet()
                .size,
        )
    }
}
