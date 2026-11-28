package com.moneymanager.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.localsettings.MemoryLocalSettings
import com.moneymanager.localsettings.UiStateStore
import com.moneymanager.strategycatalog.CatalogEntry
import com.moneymanager.strategycatalog.CatalogManifest
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.strategycatalog.StrategyCatalogSource
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.foundation.LocalUiStateStore
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class StrategyUpdateReimportTest : DbTest() {
    @BeforeTest
    fun openDatabase() = runTest { openTestDatabase() }

    @Test
    fun editorAndCatalogUpdatesOfferToReimportOnlyPreviouslyImportedSessions() =
        runMoneyManagerComposeUiTest {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val engine = repositories.importEngine
            val services = repositories.toAppServices(engine)
            val (imported, downloadedOnly) =
                runBlocking {
                    repositories.settingsRepository.setSetupWizardCompleted(true)
                    repositories.settingsRepository.setDefaultCurrencyId(
                        repositories.currencyRepository
                            .getAllCurrencies()
                            .first()
                            .first()
                            .id,
                    )
                    val id =
                        engine.createApiStrategy(
                            ApiImportStrategy(
                                id = ApiImportStrategyId(Uuid.random()),
                                name = "Example API",
                                config =
                                    ApiStrategyConfig(
                                        baseUrl = "https://example.invalid",
                                        accounts = ApiAccountsSource.Single("Example account", "example"),
                                    ),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    val credential = engine.ensureApiCredential(id, now)
                    val applied = engine.createApiSession(services.deviceId, now, credential)
                    engine.markApiSessionImported(applied, 1, now)
                    applied to engine.createApiSession(services.deviceId, now, credential)
                }
            val uiState = UiStateStore(MemoryLocalSettings()).apply { put("lastScreen", Screen.serializer(), Screen.ApiStrategies) }
            val version = AppVersion("test")
            val library = services.imports.strategyLibrary
            val updateKey = StrategyKey(StrategyKind.API, "Renamed API")
            val catalogJson =
                runBlocking { library.listLocal(version).single { it.key == updateKey.copy(name = "Example API") }.json }
                    .replace("Example API", updateKey.name)
                    .replace("https://example.invalid", "https://updated.invalid")
            val catalogEntry =
                CatalogEntry(updateKey.name, updateKey.kind, "strategy.api.json", library.canonicalHash(updateKey, catalogJson))
            val catalog =
                StrategyCatalogController(
                    remoteSource =
                        object : StrategyCatalogSource {
                            override suspend fun fetchManifest(): CatalogManifest = CatalogManifest(listOf(catalogEntry))

                            override suspend fun fetchArtifact(fileName: String): String = catalogJson
                        },
                    localDirectorySourceFactory = { error("No local catalog in this test") },
                    localSettings = MemoryLocalSettings(),
                )
            setContent {
                CompositionLocalProvider(LocalUiStateStore provides uiState) {
                    MoneyManagerApp(
                        appVersion = version,
                        databaseLocation = DbLocation("test"),
                        services = services,
                        strategyCatalogController = catalog,
                        onRequestSwitchDatabase = {},
                    )
                }
            }

            val prompt = hasText("Re-import previous imports?")
            waitUntilAtLeastOneExists(hasText("Edit"))
            onNodeWithText("Edit").performClick()
            waitUntilAtLeastOneExists(hasText("Strategy name"))
            onNodeWithText("Save").performClick()
            waitUntilAtLeastOneExists(hasText("Edit"))
            waitForIdle()
            onNodeWithText("Re-import previous imports?").assertDoesNotExist()

            onNodeWithText("Edit").performClick()
            waitUntilAtLeastOneExists(hasText("Strategy name"))
            onNodeWithText("Strategy name").performTextReplacement("Renamed API")
            onNodeWithText("Save").performClick()
            waitUntilAtLeastOneExists(prompt)
            onNodeWithText("1 API session", substring = true).assertExists()
            onNodeWithText("Not now").performClick()
            waitUntilDoesNotExist(prompt)
            waitForIdle()
            assertEquals(listOf(1L), runBlocking { repositories.apiSessionRepository.getImportedSessionRevisions().map { it.revisionId } })

            onNodeWithText("Browse catalog").performClick()
            waitUntilAtLeastOneExists(hasText("Update"))
            onNodeWithText("Update").performClick()
            waitUntilAtLeastOneExists(hasText("Overwrite installed strategies?"))
            onNodeWithText("Overwrite").performClick()
            waitUntilAtLeastOneExists(prompt)
            onNodeWithText("Re-import all").performClick()
            waitUntil(timeoutMillis = 10_000) { runBlocking { repositories.apiSessionRepository.getImportedSessionRevisions().size } == 2 }
            val revisions = runBlocking { repositories.apiSessionRepository.getImportedSessionRevisions() }
            assertEquals(setOf(imported), revisions.map { it.sessionId }.toSet())
            assertEquals(true, revisions.none { it.sessionId == downloadedOnly })
            assertEquals(
                "https://updated.invalid",
                runBlocking {
                    repositories.apiImportStrategyRepository
                        .getAllStrategies()
                        .first()
                        .single()
                        .config.baseUrl
                },
            )
            waitForIdle()
        }
}
