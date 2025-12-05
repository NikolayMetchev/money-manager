@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseConfig
import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.repository.CurrencyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrencyRepositoryImplTest {
    private lateinit var repository: CurrencyRepository
    private lateinit var testDbLocation: com.moneymanager.database.DbLocation

    @BeforeTest
    fun setup() =
        runTest {
            // Create temporary database file
            testDbLocation = createTestDatabaseLocation()

            // Create app component
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager

            // Open file-based database for testing
            val database = databaseManager.openDatabase(testDbLocation)
            val repositories = RepositorySet(database)

            repository = repositories.currencyRepository
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    // Number of seeded currencies from DatabaseConfig
    private val seededCurrencyCount = DatabaseConfig.defaultCurrencies.size

    // UPSERT TESTS

    @Test
    fun `upsertCurrencyByCode should create new currency when code does not exist`() =
        runTest {
            // When - use a currency not in the seeded list
            val currencyId = repository.upsertCurrencyByCode("CHF", "Swiss Franc")

            // Then
            assertNotNull(currencyId)

            val currency = repository.getCurrencyById(currencyId).first()
            assertNotNull(currency)
            assertEquals("CHF", currency.code)
            assertEquals("Swiss Franc", currency.name)
        }

    @Test
    fun `upsertCurrencyByCode should return existing ID when currency already exists`() =
        runTest {
            // Given - EUR is already seeded
            val initialCurrencies = repository.getAllCurrencies().first()
            val initialCount = initialCurrencies.size

            // When - upsert an already seeded currency
            val eurId = repository.upsertCurrencyByCode("EUR", "Euro")

            // Then
            val currenciesAfter = repository.getAllCurrencies().first()
            assertEquals(initialCount, currenciesAfter.size, "Should not create duplicate currency")

            // Upsert again should return same ID
            val secondEurId = repository.upsertCurrencyByCode("EUR", "Euro")
            assertEquals(eurId, secondEurId, "Should return same ID for existing currency")
        }

    @Test
    fun `upsertCurrencyByCode should not modify existing currency data`() =
        runTest {
            // Given
            val originalId = repository.upsertCurrencyByCode("GBP", "British Pound")
            val originalCurrency = repository.getCurrencyById(originalId).first()
            assertNotNull(originalCurrency)

            // When - upsert the same code again
            val newId = repository.upsertCurrencyByCode("GBP", "British Pound Sterling")

            // Then
            assertEquals(originalId, newId, "ID should not change")

            val currencyAfterUpsert = repository.getCurrencyById(originalId).first()
            assertNotNull(currencyAfterUpsert)
            assertEquals(originalCurrency.code, currencyAfterUpsert.code, "Code should not change")
            assertEquals(originalCurrency.id, currencyAfterUpsert.id, "ID should not change")
        }

    @Test
    fun `upsertCurrencyByCode should handle multiple different currencies`() =
        runTest {
            // When - upsert seeded currencies (should return existing IDs)
            val usdId = repository.upsertCurrencyByCode("USD", "US Dollar")
            val eurId = repository.upsertCurrencyByCode("EUR", "Euro")
            val gbpId = repository.upsertCurrencyByCode("GBP", "British Pound")

            // Then - should have at least the seeded currencies
            val currencies = repository.getAllCurrencies().first()
            assertEquals(seededCurrencyCount, currencies.size)

            val ids = currencies.map { it.id }.toSet()
            assertTrue(ids.contains(usdId))
            assertTrue(ids.contains(eurId))
            assertTrue(ids.contains(gbpId))

            val codes = currencies.map { it.code }.toSet()
            assertTrue(codes.containsAll(setOf("USD", "EUR", "GBP")))
        }

    @Test
    fun `upsertCurrencyByCode should be case sensitive`() =
        runTest {
            // Given - USD is already seeded
            val initialCount = repository.getAllCurrencies().first().size

            // When
            val lowercaseId = repository.upsertCurrencyByCode("usd", "US Dollar Lowercase")
            val uppercaseId = repository.upsertCurrencyByCode("USD", "US Dollar")

            // Then - lowercase should be new, uppercase should be existing
            val currencies = repository.getAllCurrencies().first()
            assertEquals(
                initialCount + 1,
                currencies.size,
                "Should create one new currency for lowercase (USD already exists)",
            )
            assertTrue(lowercaseId != uppercaseId, "IDs should be different")
        }

    @Test
    fun `upsertCurrencyByCode should handle rapid consecutive calls for same code`() =
        runTest {
            // Given
            val initialCount = repository.getAllCurrencies().first().size

            // When - simulate rapid upserts for a new currency
            val id1 = repository.upsertCurrencyByCode("BTC", "Bitcoin")
            val id2 = repository.upsertCurrencyByCode("BTC", "Bitcoin")
            val id3 = repository.upsertCurrencyByCode("BTC", "Bitcoin")

            // Then
            assertEquals(id1, id2)
            assertEquals(id2, id3)

            val currencies = repository.getAllCurrencies().first()
            assertEquals(
                initialCount + 1,
                currencies.size,
                "Should only add one new currency despite multiple upserts",
            )
        }

    // GET ALL CURRENCIES TESTS

    @Test
    fun `getAllCurrencies should return seeded currencies for new database`() =
        runTest {
            val currencies = repository.getAllCurrencies().first()
            assertEquals(seededCurrencyCount, currencies.size)

            val codes = currencies.map { it.code }.toSet()
            val seededCodes = DatabaseConfig.defaultCurrencies.map { it.first }.toSet()
            assertEquals(seededCodes, codes)
        }

    @Test
    fun `getAllCurrencies should return all currencies including seeded and new`() =
        runTest {
            // Given - add a new currency not in seeded list
            repository.upsertCurrencyByCode("CHF", "Swiss Franc")

            // When
            val currencies = repository.getAllCurrencies().first()

            // Then - should have seeded currencies plus the new one
            assertEquals(seededCurrencyCount + 1, currencies.size)
            val codes = currencies.map { it.code }.toSet()
            val seededCodes = DatabaseConfig.defaultCurrencies.map { it.first }.toSet()
            assertTrue(codes.containsAll(seededCodes))
            assertTrue(codes.contains("CHF"))
        }
}
