@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseConfig
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.test.database.createTestAppComponentParams
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrencyRepositoryImplTest: DbTest() {
    // Number of seeded currencies from DatabaseConfig
    private val seededCurrencyCount = DatabaseConfig.allCurrencies.size

    // UPSERT TESTS

    @Test
    fun `upsertCurrencyByCode should create new currency when code does not exist`() =
        runTest {
            // When - use a currency not in the seeded list (non-ISO code)
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("XYZ", "Test Currency")

            // Then
            assertNotNull(currencyId)

            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()
            assertNotNull(currency)
            assertEquals("XYZ", currency.code)
            assertEquals("Test Currency", currency.name)
        }

    @Test
    fun `upsertCurrencyByCode should return existing ID when currency already exists`() =
        runTest {
            // Given - EUR is already seeded
            val initialCurrencies = repositories.currencyRepository.getAllCurrencies().first()
            val initialCount = initialCurrencies.size

            // When - upsert an already seeded currency
            val eurId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")

            // Then
            val currenciesAfter = repositories.currencyRepository.getAllCurrencies().first()
            assertEquals(initialCount, currenciesAfter.size, "Should not create duplicate currency")

            // Upsert again should return same ID
            val secondEurId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            assertEquals(eurId, secondEurId, "Should return same ID for existing currency")
        }

    @Test
    fun `upsertCurrencyByCode should not modify existing currency data`() =
        runTest {
            // Given
            val originalId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val originalCurrency = repositories.currencyRepository.getCurrencyById(originalId).first()
            assertNotNull(originalCurrency)

            // When - upsert the same code again
            val newId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound Sterling")

            // Then
            assertEquals(originalId, newId, "ID should not change")

            val currencyAfterUpsert = repositories.currencyRepository.getCurrencyById(originalId).first()
            assertNotNull(currencyAfterUpsert)
            assertEquals(originalCurrency.code, currencyAfterUpsert.code, "Code should not change")
            assertEquals(originalCurrency.id, currencyAfterUpsert.id, "ID should not change")
        }

    @Test
    fun `upsertCurrencyByCode should handle multiple different currencies`() =
        runTest {
            // When - upsert seeded currencies (should return existing IDs)
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val eurId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val gbpId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")

            // Then - should have at least the seeded currencies
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
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
            val initialCount = repositories.currencyRepository.getAllCurrencies().first().size

            // When
            val lowercaseId = repositories.currencyRepository.upsertCurrencyByCode("usd", "US Dollar Lowercase")
            val uppercaseId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Then - lowercase should be new, uppercase should be existing
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
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
            val initialCount = repositories.currencyRepository.getAllCurrencies().first().size

            // When - simulate rapid upserts for a new currency
            val id1 = repositories.currencyRepository.upsertCurrencyByCode("BTC", "Bitcoin")
            val id2 = repositories.currencyRepository.upsertCurrencyByCode("BTC", "Bitcoin")
            val id3 = repositories.currencyRepository.upsertCurrencyByCode("BTC", "Bitcoin")

            // Then
            assertEquals(id1, id2)
            assertEquals(id2, id3)

            val currencies = repositories.currencyRepository.getAllCurrencies().first()
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
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            assertEquals(seededCurrencyCount, currencies.size)

            val codes = currencies.map { it.code }.toSet()
            val seededCodes = DatabaseConfig.allCurrencies.map { it.code }.toSet()
            assertEquals(seededCodes, codes)
        }

    @Test
    fun `getAllCurrencies should return all currencies including seeded and new`() =
        runTest {
            // Given - add a new currency not in seeded list (use a non-ISO code)
            repositories.currencyRepository.upsertCurrencyByCode("XYZ", "Test Currency")

            // When
            val currencies = repositories.currencyRepository.getAllCurrencies().first()

            // Then - should have seeded currencies plus the new one
            assertEquals(seededCurrencyCount + 1, currencies.size)
            val codes = currencies.map { it.code }.toSet()
            val seededCodes = DatabaseConfig.allCurrencies.map { it.code }.toSet()
            assertTrue(codes.containsAll(seededCodes))
            assertTrue(codes.contains("XYZ"))
        }
}
