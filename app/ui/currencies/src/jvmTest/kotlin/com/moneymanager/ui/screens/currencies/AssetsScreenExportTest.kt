@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.currencies

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.database.service.AssetExportService
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * Exporting opens the desktop save dialog, which a headless test run can't show: the failure is what the screen
 * reports, so this runs on the JVM alone (an Android device would open its system picker).
 */
class AssetsScreenExportTest {
    @Test
    fun exportingWithoutADialogReportsTheFailure() =
        runMoneyManagerComposeUiTest {
            val currencies: CurrencyReadRepository = mock(MockMode.autoUnit) { every { getAllCurrencies() } returns flowOf(emptyList()) }
            val crypto: CryptoReadRepository =
                mock(MockMode.autoUnit) {
                    every { getAllCryptoAssets() } returns flowOf(listOf(CryptoAsset(id = CryptoId(1L), code = "LUNA", name = "Terra")))
                    every { getAssetAliases() } returns flowOf(emptyList())
                }
            val engine: ImportEngine = mock(MockMode.autoUnit)
            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides engine) {
                        AssetsScreen(
                            currencyRepository = currencies,
                            cryptoRepository = crypto,
                            assetExportService = AssetExportService(currencies, crypto, engine),
                            appVersion = AppVersion("test"),
                        )
                    }
                }
            }

            onNodeWithContentDescription("Export assets").performClick()

            waitUntilAtLeastOneExists(hasText("Failed to export", substring = true), timeoutMillis = 5_000)
        }
}
