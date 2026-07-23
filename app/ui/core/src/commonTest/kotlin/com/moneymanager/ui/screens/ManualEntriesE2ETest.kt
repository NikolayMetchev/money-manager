package com.moneymanager.ui.screens
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.hasDisplayValue
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.upsertCurrencyByCode
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end test for the Manual Entries tab: a Wise assets fee (matched by the seeded
 * companion transaction rule) is listed as missing its interest transfer, and entering
 * an amount creates the mirrored companion linked via the wise-interest-for attribute.
 */
@OptIn(ExperimentalTestApi::class)
class ManualEntriesE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun manualEntries_createsCompanionInterestTransferForAssetsFee() {
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()
            lateinit var databaseComponent: DatabaseComponent
            var wiseEurOrNull: AccountId? = null
            var transferwiseOrNull: AccountId? = null
            val feeTimestamp = Instant.fromEpochMilliseconds(1717340000000L)

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                databaseComponent = DatabaseComponent.create(db)
                databaseComponent.installBuiltInCsvStrategies()

                val wiseEur =
                    databaseComponent.accountRepository.createAccount(
                        Account(id = AccountId(0), name = "Wise: EUR", openingDate = feeTimestamp),
                    )
                val transferwise =
                    databaseComponent.accountRepository.createAccount(
                        Account(id = AccountId(0), name = "TransferWise", openingDate = feeTimestamp),
                    )
                wiseEurOrNull = wiseEur
                transferwiseOrNull = transferwise
                val currencyId = databaseComponent.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
                val currency = databaseComponent.currencyRepository.getCurrencyById(currencyId).first()!!

                // An imported Wise assets fee that still lacks its interest companion
                val fee =
                    Transfer(
                        id = TransferId(0L),
                        timestamp = feeTimestamp,
                        description = "Assets fee 18326272",
                        sourceAccountId = wiseEur,
                        targetAccountId = transferwise,
                        amount = Money.fromDisplayValue("0.06", currency),
                    )
                val wiseIdType = databaseComponent.attributeTypeRepository.getOrCreate("wise-id")
                databaseComponent.transactionRepository.createTransfers(
                    transfers = listOf(fee),
                    newAttributes = mapOf(fee.id to listOf(NewAttribute(wiseIdType, "ACCRUAL_CHARGE-18326272"))),
                    sources = listOf(Source.SampleGenerator),
                )
            }

            val testDatabaseManager =
                ManualEntriesTestDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerTestApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)

            // Navigate to the "Misc" tab; Manual Entries is its default sub-tab, so its content shows
            // immediately (no need to click the sub-tab, whose label would also match the screen header).
            waitUntilAtLeastOneExists(hasText("Imports"), timeoutMillis = 10000)
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Misc"), timeoutMillis = 15000)
            onNodeWithText("Misc", useUnmergedTree = true).performClick()
            waitForIdle()

            // The assets fee is listed under the seeded Wise companion rule
            waitUntilAtLeastOneExists(hasText("Wise CSV — Interest earned"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(hasText("Assets fee 18326272"), timeoutMillis = 10000)

            // Entering an amount enables the create button
            onNodeWithText("Amount (EUR)").performTextInput("1.23")
            waitUntilAtLeastOneExists(hasText("Create 1 transaction") and isEnabled(), timeoutMillis = 10000)
            onNodeWithText("Create 1 transaction").performClick()

            // The fee disappears once its companion exists
            waitUntilDoesNotExist(hasText("Assets fee 18326272"), timeoutMillis = 30000)
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("No pending manual entries."), timeoutMillis = 15000)

            // The companion mirrors the fee and is linked via wise-interest-for
            runBlocking {
                val wiseEur = assertNotNull(wiseEurOrNull)
                val transferwise = assertNotNull(transferwiseOrNull)
                val companion =
                    databaseComponent.transactionRepository
                        .getTransactionsByAccount(wiseEur)
                        .first()
                        .singleOrNull { it.description == "Interest earned" }
                assertNotNull(companion, "Companion interest transfer is created")
                assertEquals(transferwise, companion.sourceAccountId)
                assertEquals(wiseEur, companion.targetAccountId)
                assertEquals(feeTimestamp.toEpochMilliseconds(), companion.timestamp.toEpochMilliseconds())
                assertEquals("EUR", companion.amount.asset.code)
                assertTrue(companion.amount.hasDisplayValue("1.23"))
                val link = companion.attributes.single { it.attributeType.name == "wise-interest-for" }
                assertEquals("ACCRUAL_CHARGE-18326272", link.value)

                assertTrue(
                    databaseComponent.transactionRepository
                        .getTransfersMissingCompanion("wise-id", "ACCRUAL_CHARGE-%", "wise-interest-for")
                        .first()
                        .isEmpty(),
                    "No assets fee is missing its companion any more",
                )
            }
        }
    }
}

/**
 * Simple DatabaseManager wrapper that only overrides getDefaultLocation.
 */
private class ManualEntriesTestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
