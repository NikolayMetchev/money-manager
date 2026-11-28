package com.moneymanager.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.localsettings.MemoryLocalSettings
import com.moneymanager.localsettings.UiStateStore
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.foundation.LocalUiStateStore
import com.moneymanager.ui.navigation.ImportTab
import com.moneymanager.ui.navigation.Screen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class AccountMappingDialogTest : DbTest() {
    @BeforeTest
    fun openDatabase() = runTest { openTestDatabase() }

    @Test
    fun theTargetAccountIsSearchedAndTheAccountsTheMappingTakesOverAreListed() {
        runMoneyManagerComposeUiTest {
            val opened = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val services = repositories.toAppServices(repositories.importEngine)
            val tesco =
                runBlocking {
                    repositories.settingsRepository.setSetupWizardCompleted(true)
                    repositories.settingsRepository.setDefaultCurrencyId(
                        repositories.currencyRepository
                            .getAllCurrencies()
                            .first()
                            .first()
                            .id,
                    )
                    listOf("TESCO STORES 3297", "Sainsbury's").forEach {
                        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = it, openingDate = opened))
                    }
                    repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Tesco", openingDate = opened))
                }
            val uiState =
                UiStateStore(MemoryLocalSettings()).apply {
                    put("lastScreen", Screen.serializer(), Screen.Imports(ImportTab.MISC))
                }
            setContent {
                CompositionLocalProvider(LocalUiStateStore provides uiState) {
                    MoneyManagerApp(
                        appVersion = AppVersion("test"),
                        databaseLocation = DbLocation("test"),
                        services = services,
                        onRequestSwitchDatabase = {},
                    )
                }
            }

            waitUntilAtLeastOneExists(hasText("Account Mappings"), timeoutMillis = 10_000)
            onNodeWithText("Account Mappings").performClick()
            waitUntilAtLeastOneExists(hasContentDescription("Add mapping"), timeoutMillis = 10_000)
            onNodeWithContentDescription("Add mapping").performClick()
            waitUntilAtLeastOneExists(hasText("Add Account Mapping"))
            onNodeWithText("Value Pattern (Regex)").performTextInput("tesco")

            onNodeWithText("Select...").performClick()
            waitUntilAtLeastOneExists(hasText("Sainsbury's"))
            onNodeWithText("Target Account").performTextInput("tes")
            waitUntilDoesNotExist(hasText("Sainsbury's"))
            onNodeWithText("TESCO STORES 3297").assertExists()
            onNodeWithText("Tesco").performClick()

            waitUntilAtLeastOneExists(hasText("Takes over 1 existing account", substring = true))
            onNodeWithText("• TESCO STORES 3297").assertExists()

            onNodeWithText("Add").performClick()
            waitUntilDoesNotExist(hasText("Add Account Mapping"))
            waitForIdle()
            // No file booked onto the captured account, so there is nothing to offer to re-import.
            onNodeWithText("Re-import previous imports?").assertDoesNotExist()
            val mapping =
                runBlocking {
                    repositories.accountMappingRepository
                        .getAllMappings()
                        .first()
                        .single()
                }
            assertEquals(tesco, mapping.accountId)
        }
    }
}
