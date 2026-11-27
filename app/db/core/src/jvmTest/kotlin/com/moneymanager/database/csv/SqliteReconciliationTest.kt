package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.importFileChecksum
import com.moneymanager.csvimporter.sqliteTablePage
import com.moneymanager.csvimporter.sqliteTables
import com.moneymanager.csvimporter.stageImportFile
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SqliteRowRef
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import org.sqlite.JDBC
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqliteReconciliationTest : DbTest() {
    override val installBuiltInStrategies = true

    private fun snapshot(vararg sql: String): ByteArray =
        JDBC.createConnection("jdbc:sqlite::memory:", Properties()).use { db ->
            db.createStatement().use { statement -> sql.forEach { statement.execute(it) } }
            db.serialize("main")
        }

    @Test
    fun aGnuCashTransferLinksBackToTheOriginalTransactionAndBothPostings() =
        runDbTest {
            val bytes =
                snapshot(
                    "CREATE TABLE accounts(guid TEXT, parent_guid TEXT, name TEXT, commodity_guid TEXT)",
                    "CREATE TABLE commodities(guid TEXT, mnemonic TEXT)",
                    "CREATE TABLE transactions(guid TEXT, post_date TEXT, description TEXT)",
                    "CREATE TABLE splits(guid TEXT, tx_guid TEXT, account_guid TEXT, quantity_num INTEGER, quantity_denom INTEGER)",
                    "INSERT INTO commodities VALUES ('c','GBP')",
                    "INSERT INTO accounts VALUES ('root',NULL,'Root',NULL), ('a','root','Cash','c'), ('b','root','Food','c')",
                    "INSERT INTO transactions VALUES ('tx','2024-01-02 12:00:00','Lunch')",
                    "INSERT INTO splits VALUES ('d','tx','a',-1085,100), ('r','tx','b',1085,100)",
                )
            val reads = repositories.csvImportRepository
            val engine = repositories.importEngine
            val id =
                engine.stageImportFile(
                    ImportFileKind.SQLITE,
                    "ledger.gnucash",
                    bytes,
                    importFileChecksum(ImportFileKind.SQLITE, bytes),
                    Instant.fromEpochMilliseconds(0),
                    reads,
                )
            val sources = repositories.reconciliationLinkRepository.getSources()
            val sqliteSourceNames = setOf("GnuCash", "Money Manager")
            assertEquals(emptySet(), sources.first().map { it.name }.toSet() intersect sqliteSourceNames)
            val original = assertNotNull(reads.getSqliteFile(id))
            assertContentEquals(bytes, original.bytes)
            assertEquals(listOf("accounts", "commodities", "splits", "transactions"), sqliteTables(original.bytes))
            val result =
                bulkApplyCsv(
                    imports = listOf(assertNotNull(reads.getImport(id).first())),
                    sourceAccountOverride = null,
                    strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                    currencies = repositories.currencyRepository.getAllCurrencies().first(),
                    accountMappingRepository = repositories.accountMappingRepository,
                    accountRepository = repositories.accountRepository,
                    csvImportRepository = reads,
                    maintenance = repositories.maintenanceService,
                    importEngine = engine,
                    onProgress = {},
                    cryptoRepository = repositories.cryptoRepository,
                    tradeRepository = repositories.tradeRepository,
                )
            assertEquals(1, result.transfersCreated, result.files.toString())
            assertEquals(setOf("GnuCash"), sources.first().map { it.name }.toSet() intersect sqliteSourceNames)
            val accounts = repositories.accountRepository.getAllAccounts().first()
            assertEquals(setOf("GnuCash · Cash", "GnuCash · Food"), accounts.map { it.name }.toSet())
            assertEquals(
                accounts.map { it.id }.toSet(),
                repositories.accountRepository.getAccountIdsByAttribute(
                    WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID,
                    "GnuCash",
                ),
            )
            val transfer =
                repositories.transactionRepository
                    .getTransactionsByAccount(accounts.first().id)
                    .first()
                    .single()
            assertEquals("10.85", transfer.amount.toDisplayValue().toString())
            val source =
                assertIs<Source.Sqlite>(
                    repositories.transferSourceRepository
                        .getSourcesForTransaction(transfer.id)
                        .single()
                        .source,
                )
            assertEquals(id, source.importId)
            assertEquals(3, source.records.size)
            val debit = SqliteRowRef("splits", mapOf("guid" to "d"))
            assertTrue(debit in source.records)
            val raw = sqliteTablePage(original.bytes, debit.table, 0, 10, debit)
            assertEquals("-1085", raw.rows.single()[raw.headers.indexOf("quantity_num")])
        }

    @Test
    fun anUnknownSchemaIsBrowsableWithoutAnyStrategy() =
        runDbTest {
            val bytes = snapshot("CREATE TABLE \"a b\"(code TEXT PRIMARY KEY) WITHOUT ROWID", "INSERT INTO \"a b\" VALUES ('x''y')")
            val id =
                repositories.importEngine.stageImportFile(
                    ImportFileKind.SQLITE,
                    "unknown.db",
                    bytes,
                    "checksum",
                    Instant.fromEpochMilliseconds(0),
                    repositories.csvImportRepository,
                )
            val file = assertNotNull(repositories.csvImportRepository.getSqliteFile(id))
            assertEquals(listOf("a b"), sqliteTables(file.bytes))
            assertEquals(listOf(listOf("x'y")), sqliteTablePage(file.bytes, "a b", 0, 10, SqliteRowRef("a b", mapOf("code" to "x'y"))).rows)
        }
}
