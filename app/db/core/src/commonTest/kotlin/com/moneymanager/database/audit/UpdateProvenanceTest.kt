@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.audit

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Regression tests proving the non-import UPDATE paths record provenance: every revision an update
 * produces must have a source row, mirroring the create paths. Before [Auditable] wiring these
 * updates bumped the revision without recording a [Source], leaving audit rows with a null source.
 */
class UpdateProvenanceTest : DbTest() {
    @Test
    fun updatePersonRecordsSource() =
        runTest {
            val personId =
                repositories.personRepository.createPerson(
                    Person(id = PersonId(0), firstName = "Ada", middleName = null, lastName = "Lovelace"),
                    Source.SampleGenerator,
                )
            repositories.personRepository.updatePerson(
                Person(id = personId, firstName = "Ada", middleName = null, lastName = "Byron"),
                Source.Manual,
            )

            val history = repositories.auditRepository.getAuditHistoryForPerson(personId)
            assertTrue(history.size >= 2, "Expected create + update audit entries, got ${history.size}")
            history.forEach { entry ->
                assertNotNull(entry.source, "person audit revision ${entry.revisionId} has null source")
            }
            // The update's distinct source proves the update path recorded provenance (not just create).
            assertTrue(history.any { it.source?.source == Source.Manual }, "update source not recorded")
        }

    @Test
    fun updateCurrencyRecordsSource() =
        runTest {
            val currencyId =
                repositories.currencyRepository.upsertCurrencyByCode("ZAR", "South African Rand", Source.SampleGenerator)
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!
            repositories.currencyRepository.updateCurrency(currency.copy(name = "Rand"), Source.Manual)

            val history = repositories.auditRepository.getAuditHistoryForCurrency(currencyId)
            assertTrue(history.size >= 2, "Expected create + update audit entries, got ${history.size}")
            history.forEach { entry ->
                assertNotNull(entry.source, "currency audit revision ${entry.revisionId} has null source")
            }
            assertTrue(history.any { it.source?.source == Source.Manual }, "update source not recorded")
        }

    @Test
    fun updateTransferRecordsSource() =
        runTest {
            val now = Clock.System.now()
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(AccountId(0), name = "A", openingDate = now),
                    Source.Manual,
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(AccountId(0), name = "B", openingDate = now),
                    Source.Manual,
                )
            val usd = repositories.currencyRepository.getCurrencyByCode("USD").first()!!
            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0),
                        timestamp = now,
                        description = "Original",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue("1.00", usd),
                    ),
                )

            repositories.transactionRepository.updateTransfer(
                transfer = transfer.copy(description = "Edited"),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer.id,
                source = Source.Manual,
            )

            val history = repositories.auditRepository.getAuditHistoryForTransfer(transfer.id)
            assertTrue(history.size >= 2, "Expected create + update audit entries, got ${history.size}")
            history.forEach { entry ->
                assertNotNull(entry.source, "transfer audit revision ${entry.revisionId} has null source")
            }
            // createTransfer used SampleGenerator; the Manual source proves the update path recorded provenance.
            assertTrue(history.any { it.source?.source == Source.Manual }, "update source not recorded")
        }

    @Test
    fun updateCsvImportStrategyRecordsSource() =
        runTest {
            val now = Clock.System.now()
            val strategy =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "Prov CSV Strategy",
                    identificationColumns = setOf("Date", "Amount"),
                    fieldMappings = emptyMap(),
                    createdAt = now,
                    updatedAt = now,
                )
            repositories.csvImportStrategyRepository.createStrategy(strategy, Source.SampleGenerator)
            repositories.csvImportStrategyRepository.updateStrategy(strategy.copy(name = "Renamed"), Source.Manual)

            val history = repositories.auditRepository.getAuditHistoryForCsvImportStrategy(strategy.id)
            assertTrue(history.size >= 2, "Expected create + update audit entries, got ${history.size}")
            history.forEach { entry ->
                assertNotNull(entry.source, "csv strategy audit revision ${entry.revisionId} has null source")
            }
            assertTrue(history.any { it.source?.source == Source.Manual }, "update source not recorded")
        }
}
