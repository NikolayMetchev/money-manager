@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.audit
import app.cash.sqldelight.db.QueryResult
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createCategory
import com.moneymanager.test.database.createOwnership
import com.moneymanager.test.database.createPerson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Contract test: every entity-level audit entry must have source attribution.
 *
 * [allEntityAuditEntriesHaveSource] creates one instance of each known entity type, records its
 * source, then asserts that every audit entry returned by [AuditRepository] has a non-null
 * `source`. It also verifies the built-in seeded entities (currencies, Uncategorized category,
 * Monzo strategy).
 *
 * At the end of the test the set of tables explicitly covered is compared against
 * `discoverAuditTables() - ATTRIBUTE_AUDIT_TABLES`. If a new `*_audit` table appears in the
 * schema without being covered here, **this test fails automatically** with a clear message
 * naming the uncovered table.
 *
 * To add coverage for a new entity type:
 * 1. Create one instance via the repository, record its source.
 * 2. Fetch its audit history via [AuditRepository] and call [assertAllHaveSource].
 * 3. Add its audit table name to `coveredTables`.
 */
class AuditSourceCoverageTest : DbTest() {
    companion object {
        // Attribute sub-tables track field-level changes; provenance is inherited from the
        // parent entity's revision and is not stored per-row. Excluded from the source check.
        private val ATTRIBUTE_AUDIT_TABLES =
            setOf(
                "transfer_attribute_audit",
                "account_attribute_audit",
                "person_attribute_audit",
            )

        // Well-known UUID seeded by DatabaseConfig for the built-in Monzo strategy.
        private val MONZO_STRATEGY_ID =
            ApiImportStrategyId(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun allEntityAuditEntriesHaveSource() =
        runTest {
            // Entities created below record their source automatically inside the repository (the
            // create methods require an EntityProvenance), so this test also proves that contract.
            val now = Clock.System.now()
            val coveredTables = mutableSetOf<String>()

            // --- Built-in seeded entities ---

            // Uncategorized category (id = -1) is inserted during database initialisation.
            val uncategorizedAudit = repositories.auditRepository.getAuditHistoryForCategory(-1L)
            assertAllHaveSource("Uncategorized category", uncategorizedAudit) { it.source }
            coveredTables.add("category_audit")

            // Monzo strategy is inserted with a fixed UUID during database initialisation.
            val strategyAudit =
                repositories.auditRepository.getAuditHistoryForApiImportStrategy(MONZO_STRATEGY_ID)
            assertAllHaveSource("Monzo strategy", strategyAudit) { it.source }
            coveredTables.add("api_import_strategy_audit")

            // All seeded currencies must have source.
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            assertNotNull(currencies.firstOrNull(), "Expected at least one seeded currency")
            currencies.forEach { currency ->
                val currencyAudit =
                    repositories.auditRepository.getAuditHistoryForCurrency(currency.id)
                assertAllHaveSource("currency ${currency.code}", currencyAudit) { it.source }
            }
            coveredTables.add("currency_audit")

            // --- User-created entities ---

            // Accounts
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )
            assertAllHaveSource(
                "account",
                repositories.auditRepository.getAuditHistoryForAccount(sourceAccountId),
            ) { it.source }
            coveredTables.add("account_audit")

            // Category
            val categoryId =
                repositories.categoryRepository.createCategory(
                    Category(id = -1, name = "Test Category"),
                )
            assertAllHaveSource(
                "user category",
                repositories.auditRepository.getAuditHistoryForCategory(categoryId),
            ) { it.source }
            // category_audit already added above

            // Person
            val personId =
                repositories.personRepository.createPerson(
                    Person(id = PersonId(0), firstName = "Test", middleName = null, lastName = "User"),
                )
            assertAllHaveSource(
                "person",
                repositories.auditRepository.getAuditHistoryForPerson(personId),
            ) { it.source }
            coveredTables.add("person_audit")

            // PersonAccountOwnership
            val ownershipId =
                repositories.personAccountOwnershipRepository.createOwnership(personId, sourceAccountId)
            assertAllHaveSource(
                "person-account ownership",
                repositories.auditRepository.getAuditHistoryForPersonAccountOwnership(ownershipId),
            ) { it.source }
            coveredTables.add("person_account_ownership_audit")

            // Transfer (createTransfer in DbTest attaches a SampleGenerator source automatically)
            val usd = repositories.currencyRepository.getCurrencyByCode("USD").first()
            assertNotNull(usd, "USD currency should be seeded")
            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0),
                        timestamp = now,
                        description = "Coverage test transfer",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue("1.00", usd),
                    ),
                )
            assertAllHaveSource(
                "transfer",
                repositories.auditRepository.getAuditHistoryForTransfer(transfer.id),
            ) { it.source }
            coveredTables.add("transfer_audit")

            // ---------------------------------------------------------------
            // Structural check: every entity audit table in the schema must be covered above.
            // This fails automatically when a new *_audit table appears without test coverage.
            // ---------------------------------------------------------------
            val entityAuditTables = discoverAuditTables() - ATTRIBUTE_AUDIT_TABLES
            assertEquals(
                entityAuditTables,
                coveredTables,
                "Audit tables in the schema that are not covered by this test: " +
                    "${entityAuditTables - coveredTables}. " +
                    "Add source attribution for the new entity type and add coverage here.",
            )
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun <T> assertAllHaveSource(
        entityLabel: String,
        entries: List<T>,
        sourceOf: (T) -> Any?,
    ) {
        assertNotNull(
            entries.firstOrNull(),
            "Expected at least one audit entry for $entityLabel but the list is empty",
        )
        entries.forEachIndexed { index, entry ->
            assertNotNull(
                sourceOf(entry),
                "$entityLabel: audit entry at index $index (of ${entries.size}) has null source",
            )
        }
    }

    private fun discoverAuditTables(): Set<String> {
        val tables = mutableSetOf<String>()
        database.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%_audit'",
            { cursor ->
                while (cursor.next().value) {
                    tables.add(cursor.getString(0)!!)
                }
                QueryResult.Unit
            },
            0,
        )
        return tables
    }
}
