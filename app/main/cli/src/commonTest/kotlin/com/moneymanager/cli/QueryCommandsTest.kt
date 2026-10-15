package com.moneymanager.cli

import com.moneymanager.domain.model.DbLocation
import com.moneymanager.query.AccountBalanceJson
import com.moneymanager.query.AccountJson
import com.moneymanager.query.AssetKind
import com.moneymanager.query.Envelope
import com.moneymanager.query.ImportDirectoryJson
import com.moneymanager.query.StrategyJson
import com.moneymanager.query.StrategyKindJson
import com.moneymanager.query.TransactionJson
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class QueryCommandsTest {
    private val cli = TestCli()

    private fun withFixture(
        installStrategies: Boolean = false,
        block: suspend (DbLocation) -> Unit,
    ): TestResult =
        runTest(timeout = 2.minutes) {
            val db = cli.seedQueryFixture(installStrategies)
            try {
                block(db)
            } finally {
                deleteTestDatabase(db)
            }
        }

    private suspend fun DbLocation.mm(
        vararg argv: String,
        interactive: Boolean = false,
    ): MmRun = cli.run("--db", path, *argv, interactive = interactive)

    private inline fun <reified T> MmRun.data(): T {
        assertEquals(ExitCodes.OK, exitCode, stderr)
        return Json.decodeFromString<Envelope<T>>(stdout).data
    }

    @Test
    fun accountsGolden() =
        withFixture { db ->
            val run = db.mm("accounts")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertEquals(ACCOUNTS_GOLDEN, run.stdout.trim())
        }

    @Test
    fun peopleGolden() =
        withFixture { db ->
            assertEquals(PEOPLE_GOLDEN, db.mm("people").stdout.trim())
        }

    @Test
    fun transactionsGolden() =
        withFixture { db ->
            // "Current" is an exact name, so it wins over "Current Saver", which it is also part of.
            assertEquals(TRANSACTIONS_GOLDEN, db.mm("transactions", "--account", "Current").stdout.trim())
        }

    @Test
    fun accountsFilterByOwnerAndShowShadowOnRequest() =
        withFixture { db ->
            assertEquals(listOf("Wallet"), db.mm("accounts", "--owner", "bob").data<List<AccountJson>>().map { it.name })
            val current = db.mm("accounts", "--account", "^cur").data<List<AccountJson>>()
            assertEquals(listOf("Current", "Current Saver"), current.map { it.name })

            val withShadow = db.mm("accounts", "--include-shadow").data<List<AccountJson>>()
            assertEquals(listOf("Koinly Wallet"), withShadow.filter { it.shadow }.map { it.name })
        }

    @Test
    fun balancesFlattenPerAssetAndFilter() =
        withFixture { db ->
            val nonZeroGbp = db.mm("balances", "--asset", "gbp", "--non-zero", "--owner", "1").data<List<AccountBalanceJson>>()

            assertEquals(
                listOf(
                    AccountBalanceJson(1, "Current", "GBP", AssetKind.CURRENCY, "874.5"),
                    AccountBalanceJson(3, "Wallet", "GBP", AssetKind.CURRENCY, "100"),
                ),
                nonZeroGbp,
            )
            val btc = db.mm("balances", "--asset", "BTC").data<List<AccountBalanceJson>>()
            assertEquals(listOf("Employer" to "-0.0002469", "Wallet" to "0.00012345"), btc.map { it.account to it.amount })
        }

    @Test
    fun ambiguousOrUnknownNamesAreUsageErrorsListingTheCandidates() =
        withFixture { db ->
            val owner = db.mm("accounts", "--owner", "Sam Lee")
            assertEquals(ExitCodes.USAGE, owner.exitCode)
            assertContains(owner.stderr, "Several people are named 'Sam Lee': Sam Lee (id 3), Sam Lee (id 4)")

            val account = db.mm("transactions", "--account", "Cur")
            assertEquals(ExitCodes.USAGE, account.exitCode)
            assertContains(account.stderr, "Several accounts match 'Cur': Current (id 1), Current Saver (id 2)")

            assertEquals(ExitCodes.USAGE, db.mm("transactions", "--account", "Koinly").exitCode)
            assertEquals(ExitCodes.USAGE, db.mm("transactions", "--account", "[").exitCode)
            assertEquals(ExitCodes.USAGE, db.mm("transactions").exitCode)
            assertEquals(ExitCodes.USAGE, db.mm("transactions", "--account", "1", "--cursor", "bogus").exitCode)
        }

    @Test
    fun shadowAccountsAreReachableByIdOrWithIncludeShadow() =
        withFixture { db ->
            val byName = db.mm("transactions", "--account", "Koinly", "--include-shadow").data<List<TransactionJson>>()
            val byId = db.mm("transactions", "--account", "4").data<List<TransactionJson>>()

            assertEquals(listOf("Koinly copy"), byName.map { it.description })
            assertEquals(byName, byId)
        }

    @Test
    fun transactionsPageThroughTheCursorAndHonourTheDateRange() =
        withFixture { db ->
            val seen = mutableListOf<Long>()
            var cursor: String? = null
            do {
                val argv = listOf("transactions", "--account", "1", "--limit", "2") + cursor?.let { listOf("--cursor", it) }.orEmpty()
                val run = db.mm(*argv.toTypedArray())
                assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
                val page = Json.decodeFromString<Envelope<List<TransactionJson>>>(run.stdout)
                seen += page.data.map { it.id }
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(listOf(5L, 4, 3, 2, 1), seen)

            // --to is exclusive for an instant; --from cuts the page, and nothing older is offered next.
            val ranged = db.mm("transactions", "--account", "1", "--from", "2024-03-01T10:00:00Z", "--to", "2024-03-03T08:00:00Z")
            val page = Json.decodeFromString<Envelope<List<TransactionJson>>>(ranged.stdout)
            assertEquals(listOf("Milk", "To wallet"), page.data.map { it.description })
            assertNull(page.nextCursor)

            val cutRun = db.mm("transactions", "--account", "1", "--from", "2024-03-02T00:00:00Z", "--limit", "3")
            val cut = Json.decodeFromString<Envelope<List<TransactionJson>>>(cutRun.stdout)
            assertEquals(listOf("Unsave", "Save", "Milk"), cut.data.map { it.description })
            assertNull(cut.nextCursor)
        }

    @Test
    fun strategiesListCsvThenApiByName() =
        withFixture(installStrategies = true) { db ->
            val all = db.mm("strategies").data<List<StrategyJson>>()
            val csv = all.takeWhile { it.kind == StrategyKindJson.CSV }
            val api = all.drop(csv.size)

            assertTrue(csv.isNotEmpty() && api.isNotEmpty())
            assertTrue(api.all { it.kind == StrategyKindJson.API })
            assertEquals(csv.sortedBy { it.name.lowercase() }, csv)
            assertEquals(api.sortedBy { it.name.lowercase() }, api)
            assertEquals(api, db.mm("strategies", "--kind", "api").data<List<StrategyJson>>())
        }

    @Test
    fun importDirsListTopLevelFoldersWithTheirSubfolders() =
        withFixture { db ->
            val dirs = db.mm("import-dirs").data<List<ImportDirectoryJson>>()

            assertEquals(listOf("Drive", "2024", "Statements"), dirs.map { it.name })
            val (drive, sub, local) = dirs
            assertEquals(drive.id, sub.parentId)
            assertEquals(listOf("gdrive", "gdrive", "local"), dirs.map { it.provider })
            assertEquals(listOf(true, true, false), dirs.map { it.onThisDevice })
            assertEquals("/home/alice/statements", local.path)
            assertEquals(0, local.trackedFiles)
            assertNull(local.lastImportedAt)
        }

    @Test
    fun tablesForPeople() =
        withFixture { db ->
            val balances = db.mm("balances", "--non-zero", interactive = true)
            assertEquals(ExitCodes.OK, balances.exitCode, balances.stderr)
            assertContains(balances.stdout, "│ Current     │ GBP   │      874.5 │")

            val page = db.mm("transactions", "--account", "1", "--limit", "1", interactive = true)
            assertContains(page.stdout, "Unsave")
            assertContains(page.stderr, "More: rerun with --cursor ")
        }
}
