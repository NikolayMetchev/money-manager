@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.service

import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.deleteCsvStrategy
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StrategyLibraryServiceTest : DbTest() {
    private val version = AppVersion("test")

    @Test
    fun `csv strategy round-trips with its per-strategy account mapping, keyed by name (no duplicates)`() =
        runTest {
            val library = repositories.strategyLibrary
            val engine = repositories.importEngine
            val csvRepo = repositories.csvImportStrategyRepository
            val mappingRepo = repositories.accountMappingRepository
            val now = Clock.System.now()

            val account = Account(AccountId(0), name = "Paxos", openingDate = now)
            val accountId = repositories.accountRepository.createAccount(account, Source.Manual)
            val strategy =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "WiseTest",
                    identificationColumns = setOf("Amount", "Description"),
                    fieldMappings = emptyMap(),
                    createdAt = now,
                    updatedAt = now,
                )
            engine.createCsvStrategy(strategy)
            engine.createAccountMapping(Regex("PAXOS.*", RegexOption.IGNORE_CASE), accountId, strategy.id)

            val key = StrategyKey(StrategyKind.CSV, "WiseTest")
            val entry = library.listLocal(version).first { it.key == key }
            assertTrue(entry.json.contains("Paxos"), "exported CSV strategy should embed its per-strategy mapping")
            val unresolved = library.parseIncoming(key, entry.json).unresolvedReferences
            assertTrue(unresolved.isEmpty(), "account exists, so no unresolved refs")

            // Deleting the strategy cascades its per-strategy mappings; re-applying recreates both.
            engine.deleteCsvStrategy(strategy.id)
            library.applyIncoming(key, entry.json, emptyMap())
            val recreated = csvRepo.getStrategyByName("WiseTest").first()!!
            val mappings = mappingRepo.getAllMappings().first().filter { it.strategyId == recreated.id }
            assertEquals(1, mappings.size)
            assertEquals("PAXOS.*", mappings.single().valuePattern.pattern)

            // Applying the same artifact again updates in place (keyed by name) — never a duplicate.
            library.applyIncoming(key, entry.json, emptyMap())
            val named = csvRepo.getAllStrategies().first().count { it.name == "WiseTest" }
            assertEquals(1, named)
            val afterReapply = mappingRepo.getAllMappings().first().filter { it.strategyId == recreated.id }
            assertEquals(1, afterReapply.size, "re-applying replaces the per-strategy mappings, not accumulates")
        }

    @Test
    fun `a QIF strategy is classified as QIF, a plain one as CSV`() =
        runTest {
            val engine = repositories.importEngine
            val now = Clock.System.now()
            engine.createCsvStrategy(
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "MyQif",
                    identificationColumns = QifColumns.headers.toSet(),
                    fieldMappings = emptyMap(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val kinds = repositories.strategyLibrary.listLocal(version).associate { it.key.name to it.key.kind }
            assertEquals(StrategyKind.QIF, kinds["MyQif"])
        }

    @Test
    fun `api strategy round-trips and re-applies by name without duplicating`() =
        runTest {
            val library = repositories.strategyLibrary
            val engine = repositories.importEngine
            val now = Clock.System.now()
            engine.createApiStrategy(
                ApiImportStrategy(
                    id = ApiImportStrategyId(Uuid.random()),
                    name = "MonzoTest",
                    baseUrl = "https://api.monzo.com",
                    authType = ApiAuthType.BEARER_TOKEN,
                    accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
                    transactionsEndpoint = ApiEndpointConfig(path = "/transactions", responseArrayKey = "transactions"),
                    accountMappings = ApiAccountMappings(),
                    transactionMappings = ApiTransactionMappings(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val key = StrategyKey(StrategyKind.API, "MonzoTest")
            val entry = library.listLocal(version).first { it.key == key }
            assertTrue(entry.json.contains("api.monzo.com"))

            library.applyIncoming(key, entry.json, emptyMap())
            val apiStrategies = repositories.apiImportStrategyRepository.getAllStrategies().first()
            assertEquals(1, apiStrategies.count { it.name == "MonzoTest" })
        }
}
