@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.currencies

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.service.AssetExportService
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.AssetAlias
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoCatalog
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetsScreenTest {
    /** Applies alias writes to [aliases], as the database would, so the screen shows what it just wrote. */
    private class FakeEngine(
        val aliases: MutableStateFlow<List<AssetAlias>>,
    ) : ImportEngine {
        val batches = mutableListOf<ImportBatch>()
        var failure: String? = null

        override suspend fun import(
            batch: ImportBatch,
            onProgress: (suspend (Progress) -> Unit)?,
            batchSize: Int,
        ): ImportResult {
            failure?.let { throw IllegalArgumentException(it) }
            batches += batch
            batch.assetAliasMutations.forEach { mutation ->
                aliases.value =
                    when (mutation) {
                        is AssetAliasMutation.Set -> aliases.value + AssetAlias(mutation.alias, mutation.assetCode)
                        is AssetAliasMutation.Remove -> aliases.value.filterNot { it.alias == mutation.alias }
                    }
            }
            return ImportResult()
        }
    }

    private val luna = CryptoAsset(id = CryptoId(1L), code = "LUNA", name = "Terra")
    private val bitcoin = CryptoAsset(id = CryptoId(2L), code = "BTC", name = "Bitcoin")
    private val euro = Currency(CurrencyId(1L), code = "EUR", name = "Euro", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)

    private fun ComposeUiTest.showAssets(engine: FakeEngine) {
        val currencies: CurrencyReadRepository =
            mock(MockMode.autoUnit) { every { getAllCurrencies() } returns flowOf(listOf(euro)) }
        val crypto: CryptoReadRepository =
            mock(MockMode.autoUnit) {
                every { getAllCryptoAssets() } returns flowOf(listOf(luna, bitcoin))
                every { getAssetAliases() } returns engine.aliases
            }
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
    }

    private fun ComposeUiTest.showCryptoTab(engine: FakeEngine) {
        showAssets(engine)
        onNodeWithText("Crypto").performClick()
        waitUntilExactlyOneExists(hasText("Your Crypto Assets"))
    }

    private fun engineWith(vararg aliases: AssetAlias) = FakeEngine(MutableStateFlow(aliases.toList()))

    @AfterTest
    fun resetCatalog() {
        CryptoRegistry.resetForTest()
    }

    @Test
    fun assetsScreen_currenciesTab_offersAliasesOfACurrency() {
        runMoneyManagerComposeUiTest {
            showAssets(engineWith(AssetAlias("ZEUR", "EUR")))

            waitUntilExactlyOneExists(hasText("ZEUR ✕"))
            onNodeWithText("EUR").assertIsDisplayed()
            onNodeWithContentDescription("Export assets").assertIsDisplayed()
        }
    }

    @Test
    fun assetsScreen_addAlias_writesItAndShowsIt() {
        runMoneyManagerComposeUiTest {
            val engine = FakeEngine(MutableStateFlow(emptyList()))
            showCryptoTab(engine)

            openAddAliasOf()
            onNodeWithText("Alias").performTextInput("LUNC")
            onNodeWithText("Add").performClick()

            waitUntilExactlyOneExists(hasText("LUNC ✕"))
            waitUntilDoesNotExist(hasText("Add an alias of LUNA"))
            waitForIdle()
            assertEquals(listOf<AssetAliasMutation>(AssetAliasMutation.Set("LUNC", "LUNA")), engine.batches.single().assetAliasMutations)
        }
    }

    @Test
    fun assetsScreen_addAlias_keepsTheDialogOpenWithTheReasonWhenRefused() {
        runMoneyManagerComposeUiTest {
            val engine = FakeEngine(MutableStateFlow(emptyList()))
            showCryptoTab(engine)
            engine.failure = "LUNA can't be an alias of itself"

            openAddAliasOf()
            onNodeWithText("Alias").performTextInput("LUNA")
            onNodeWithText("Add").performClick()

            waitUntilExactlyOneExists(hasText("LUNA can't be an alias of itself"))
            onNodeWithText("Add an alias of LUNA").assertIsDisplayed()
        }
    }

    @Test
    fun assetsScreen_addAlias_needsACodeAndCanBeCancelled() {
        runMoneyManagerComposeUiTest {
            val engine = FakeEngine(MutableStateFlow(emptyList()))
            showCryptoTab(engine)

            openAddAliasOf()
            onNodeWithText("Add").assertIsNotEnabled()
            onNodeWithText("Cancel").performClick()

            waitUntilDoesNotExist(hasText("Add an alias of LUNA"))
            waitForIdle()
            assertEquals(emptyList(), engine.batches)
        }
    }

    @Test
    fun assetsScreen_removeAlias_deletesIt() {
        runMoneyManagerComposeUiTest {
            val engine = engineWith(AssetAlias("LUNC", "LUNA"))
            showCryptoTab(engine)

            waitUntilExactlyOneExists(hasText("LUNC ✕"))
            onNodeWithText("LUNC ✕").performClick()

            waitUntilDoesNotExist(hasText("LUNC ✕"))
            waitForIdle()
            assertEquals(listOf<AssetAliasMutation>(AssetAliasMutation.Remove("LUNC")), engine.batches.single().assetAliasMutations)
        }
    }

    @Test
    fun assetsScreen_removeAlias_saysWhyWhenItFails() {
        runMoneyManagerComposeUiTest {
            val engine = engineWith(AssetAlias("LUNC", "LUNA"))
            showCryptoTab(engine)
            waitUntilExactlyOneExists(hasText("LUNC ✕"))
            engine.failure = "locked"

            onNodeWithText("LUNC ✕").performClick()

            waitUntilExactlyOneExists(hasText("Failed to remove LUNC: locked"))
            onNodeWithText("LUNC ✕").assertIsDisplayed()
        }
    }

    @Test
    fun assetsScreen_search_findsACryptoAssetByItsAlias() {
        runMoneyManagerComposeUiTest {
            showCryptoTab(engineWith(AssetAlias("LUNC", "LUNA")))
            waitUntilExactlyOneExists(hasText("LUNC ✕"))

            onNodeWithText("Search by code, name or alias").performTextInput("lunc")

            waitUntilDoesNotExist(hasText("BTC"))
            waitForIdle()
            onNodeWithText("LUNA").assertIsDisplayed()
        }
    }

    @Test
    fun assetsScreen_catalogAlias_showsButCanNotBeRemoved() {
        runMoneyManagerComposeUiTest {
            CryptoRegistry.install(
                object : CryptoCatalog {
                    override val aliases = mapOf("KNCL" to "LUNA", "BTC" to "LUNA", "LUNC" to "LUNA")

                    override fun lookup(code: String): CryptoRegistry.Entry? = null
                },
            )
            // LUNC is the person's own and BTC is an asset itself, so only KNCL is the catalog's to show.
            showCryptoTab(engineWith(AssetAlias("LUNC", "LUNA")))

            waitUntilExactlyOneExists(hasText("KNCL (catalog)"))
            onNodeWithText("KNCL (catalog)").assertIsNotEnabled()
            onNodeWithText("LUNC ✕").assertIsDisplayed()
            onNodeWithText("BTC (catalog)").assertDoesNotExist()
            onNodeWithText("LUNC (catalog)").assertDoesNotExist()
        }
    }

    @Test
    fun assetsScreen_deleteCrypto_asksFirstThenDeletes() {
        runMoneyManagerComposeUiTest {
            val engine = FakeEngine(MutableStateFlow(emptyList()))
            showCryptoTab(engine)

            onFirstDeleteButton().performClick()
            waitUntilExactlyOneExists(hasText("Delete Crypto Asset?"))
            assertEquals(emptyList(), engine.batches)
            onNodeWithText("Delete").performClick()

            waitUntilDoesNotExist(hasText("Delete Crypto Asset?"))
            waitForIdle()
            val deleted =
                engine.batches
                    .single()
                    .cryptoAssets
                    .single()
            assertEquals(ImportOperation.DELETE, deleted.operation)
            assertEquals(luna.id, deleted.existingId)
        }
    }

    private fun ComposeUiTest.openAddAliasOf() {
        waitUntilExactlyOneExists(hasText("LUNA"))
        onAllAddAliasButtons().onFirst().performClick()
        waitUntilExactlyOneExists(hasText("Add an alias of LUNA"))
    }

    private fun ComposeUiTest.onAllAddAliasButtons() = onAllNodes(hasText("Add alias"))

    private fun ComposeUiTest.onFirstDeleteButton() = onAllNodes(hasText("🗑️")).onFirst()
}
