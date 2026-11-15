package com.moneymanager.database.repository

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.importengineapi.createTrade
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// Test names in this package are dexed for the Android device test run: no commas or apostrophes.
class ObservationRepositoryImplTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val lastModified = Instant.fromEpochMilliseconds(1_690_000_000_000L)

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))

    private suspend fun currency(code: String): Currency {
        val id = repositories.currencyRepository.upsertCurrencyByCode(code, code)
        return repositories.currencyRepository.getCurrencyById(id).first()!!
    }

    private suspend fun transfer(
        source: AccountId,
        target: AccountId,
        gbp: Currency,
        description: String = "Coffee",
    ): Transfer =
        createTransfer(
            Transfer(
                id = TransferId(0),
                timestamp = now,
                description = description,
                sourceAccountId = source,
                targetAccountId = target,
                amount = Money.fromDisplayValue(BigDecimal("3.50"), gbp),
            ),
        )

    private suspend fun csvImport(
        rows: Int,
        checksum: String = "checksum-$rows",
    ): CsvImportId =
        repositories.csvImportRepository.createImport(
            fileName = "statement.csv",
            headers = listOf("Date", "Amount"),
            rows = (1..rows).map { listOf("2024-01-0$it", "$it.00") },
            fileChecksum = checksum,
            fileLastModified = lastModified,
        )

    private suspend fun apiSession(): Pair<ApiSessionId, ApiRequestId> {
        val sessions = repositories.apiSessionRepository
        val sessionId = sessions.createSession(repositories.deviceId, now, null)
        return sessionId to sessions.insertRequest(sessionId, "GET", "https://example.test/items", emptyMap())
    }

    private fun transferValues(
        source: AccountId,
        target: AccountId,
        gbp: Currency,
        amount: String = "3500000000000000000",
    ) = ObservedValues.Transfer(
        timestamp = now,
        description = "Coffee",
        sourceAccountId = source,
        targetAccountId = target,
        assetId = ObservedAssetId(gbp.id.id),
        amount = BigInteger(amount),
    )

    @Test
    fun `csv and api records and groups round trip`() =
        runDbTest {
            val records = repositories.importRecordRepository
            val importId = csvImport(rows = 2)
            val first = records.insertRecord(ImportRecordKey.CsvRow(importId, 1), "hash-1")
            val second = records.insertRecord(ImportRecordKey.CsvRow(importId, 2), "hash-2")

            assertEquals(ImportRecordKey.CsvRow(importId, 1), records.getRecord(first)!!.key)
            assertEquals(listOf(first, second), records.getCsvImportRecords(importId).map { it.id })
            assertFailsWith<Exception> { records.insertRecord(ImportRecordKey.CsvRow(importId, 1), "again") }

            records.updateContentHash(first, "hash-1b")
            assertEquals("hash-1b", records.getRecord(first)!!.contentHash)

            val key = ImportGroupKey("strategy", "trade", "order-7")
            val group = records.getOrCreateGroup(key)
            assertEquals(group, records.getOrCreateGroup(key))
            records.setGroupMembers(group, setOf(first, second))
            records.setGroupMembers(group, setOf(second))
            assertEquals(setOf(second), records.getGroup(key)!!.members)
            assertEquals(listOf(key), records.getGroupsOfStrategy("strategy").map { it.key })

            records.deleteRecord(second)
            assertEquals(emptySet(), records.getGroup(key)!!.members)
            records.deleteGroup(group)
            assertNull(records.getGroup(key))
            assertNull(records.getRecord(ImportRecordId(-1)))
        }

    @Test
    fun `api records are keyed by request and json path`() =
        runDbTest {
            val session = apiSession()
            val records = repositories.importRecordRepository
            val key = ImportRecordKey.ApiItem(session.first, session.second, "$.items[0]")
            val id = records.insertRecord(key, "hash")

            assertEquals(key, records.getRecord(id)!!.key)
            assertEquals(listOf(id), records.getApiSessionRecords(session.first).map { it.id })
        }

    @Test
    fun `a transfer observation round trips with its attributes and facts`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val tx = transfer(bank, shop, gbp)
            val record = repositories.importRecordRepository.insertRecord(ImportRecordKey.CsvRow(csvImport(rows = 1), 1), "h")
            val content =
                ObservationContent(
                    values = transferValues(bank, shop, gbp),
                    attributes = setOf(ObservationAttribute(AttributeTypeId(-1), "", "ref-1")),
                    facts = ObservationFacts(approximateTime = true, placeholderEnds = setOf(ObservationEnd.TARGET), grossAmount = true),
                )
            val observations = repositories.observationRepository
            val id = observations.insertObservation(ObservationOwner.Record(record), "transfer", tx.id, content)

            val read = observations.getObservation(id)!!
            assertEquals(ObservationOwner.Record(record), read.owner)
            assertEquals("transfer", read.slot)
            assertEquals(tx.id, read.transactionId)
            assertEquals(content, read.content)
            assertEquals(listOf(id), observations.getObservationsOf(ObservationOwner.Record(record)).map { it.id })
            assertEquals(listOf(id), observations.getObservationsOfTransaction(tx.id).map { it.id })
            assertFailsWith<Exception> {
                observations.insertObservation(ObservationOwner.Record(record), "transfer", tx.id, content)
            }

            val updated = ObservationContent(transferValues(shop, bank, gbp, amount = "1"))
            observations.updateContent(id, updated)
            assertEquals(updated, observations.getObservation(id)!!.content)

            val other = transfer(bank, shop, gbp, description = "Tea")
            observations.assignTransaction(id, other.id)
            assertEquals(other.id, observations.getObservation(id)!!.transactionId)

            observations.deleteObservation(id)
            assertNull(observations.getObservation(id))
        }

    @Test
    fun `a trade observation round trips and keeps its kind`() =
        runDbTest {
            val gbp = currency("GBP")
            val usd = currency("USD")
            val exchange = account("Exchange")
            val tradeId =
                repositories.importEngine.createTrade(
                    timestamp = now,
                    description = "Sell GBP",
                    fromAccountId = exchange,
                    fromAmount = Money.fromDisplayValue(BigDecimal("100"), gbp),
                    toAccountId = exchange,
                    toAmount = Money.fromDisplayValue(BigDecimal("125"), usd),
                )
            val group = repositories.importRecordRepository.getOrCreateGroup(ImportGroupKey("s", "trade", "k"))
            val values =
                ObservedValues.Trade(
                    timestamp = now,
                    description = "Sell GBP",
                    fromAccountId = exchange,
                    fromAssetId = ObservedAssetId(gbp.id.id),
                    fromAmount = BigInteger("100"),
                    toAccountId = exchange,
                    toAssetId = ObservedAssetId(usd.id.id),
                    toAmount = BigInteger("125"),
                )
            val observations = repositories.observationRepository
            val id = observations.insertObservation(ObservationOwner.Group(group), "trade", tradeId, ObservationContent(values))

            val read = observations.getObservation(id)!!
            assertEquals(tradeId, read.transactionId)
            assertTrue(read.transactionId is TradeId)
            assertEquals(values, read.content.values)

            val bank = account("Bank")
            assertFailsWith<IllegalArgumentException> {
                observations.updateContent(id, ObservationContent(transferValues(bank, exchange, gbp)))
            }
        }

    @Test
    fun `restaging moves records with their rows and drops those of removed rows`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val tx = transfer(bank, shop, gbp)
            val importId = csvImport(rows = 3)
            val records = repositories.importRecordRepository
            val row1 = records.insertRecord(ImportRecordKey.CsvRow(importId, 1), "a")
            val row2 = records.insertRecord(ImportRecordKey.CsvRow(importId, 2), "b")
            val row3 = records.insertRecord(ImportRecordKey.CsvRow(importId, 3), "c")
            val dropped =
                repositories.observationRepository.insertObservation(
                    ObservationOwner.Record(row2),
                    "transfer",
                    tx.id,
                    ObservationContent(transferValues(bank, shop, gbp)),
                )

            // The new file puts a fresh row first, keeps rows 1 and 3, and no longer has row 2.
            repositories.csvImportRepository.repopulateImport(
                id = importId,
                headers = listOf("Date", "Amount"),
                rows = listOf(listOf("2023-12-31", "0.50"), listOf("2024-01-01", "1.00"), listOf("2024-01-03", "3.00")),
                fileChecksum = "checksum-restaged",
                fileLastModified = lastModified,
                carriedRows = emptyList(),
                rowIndexRemap = mapOf(1L to 2L, 3L to 3L),
                xlsxBytes = null,
                pdf = null,
            )

            assertEquals(
                listOf(row1 to 2L, row3 to 3L),
                records.getCsvImportRecords(importId).map { it.id to (it.key as ImportRecordKey.CsvRow).rowIndex },
            )
            assertNull(records.getRecord(row2))
            assertNull(repositories.observationRepository.getObservation(dropped))

            repositories.csvImportRepository.deleteImport(importId)
            assertEquals(emptyList(), records.getCsvImportRecords(importId))
            assertNull(records.getRecord(row1))
        }

    @Test
    fun `account references move under a merge and move back`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val shopCopy = account("Shop copy")
            val spare = account("Spare")
            val tx = transfer(bank, shop, gbp)
            val record = repositories.importRecordRepository.insertRecord(ImportRecordKey.CsvRow(csvImport(rows = 1), 1), "h")
            val observations = repositories.observationRepository
            val id =
                observations.insertObservation(
                    ObservationOwner.Record(record),
                    "transfer",
                    tx.id,
                    ObservationContent(transferValues(bank, shopCopy, gbp)),
                )
            val mergeId = repositories.accountRepository.mergeAccounts(deletedAccount = spare, survivingAccount = bank)

            observations.moveAccountReferences(mergeId, deletedAccountId = shopCopy, survivingAccountId = shop)
            assertEquals(shop, (observations.getObservation(id)!!.content.values as ObservedValues.Transfer).targetAccountId)

            observations.restoreAccountReferences(mergeId, restoredAccountId = shopCopy)
            val values = observations.getObservation(id)!!.content.values as ObservedValues.Transfer
            assertEquals(bank, values.sourceAccountId)
            assertEquals(shopCopy, values.targetAccountId)
        }

    @Test
    fun `person overrides pins and splits round trip`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val tx = transfer(bank, shop, gbp)
            val overrides = repositories.personOverrideRepository

            overrides.setTransferOverride(tx.id, TransferField.DESCRIPTION, "Flat white")
            overrides.setTransferOverride(tx.id, TransferField.AMOUNT, "4")
            overrides.clearTransferOverride(tx.id, TransferField.AMOUNT)
            assertEquals(mapOf(TransferField.DESCRIPTION to "Flat white"), overrides.getTransferOverrides(tx.id))

            val type = AttributeTypeId(-1)
            overrides.setTransferAttributeOverride(tx.id, AttributeOverride.Set(type, "", "a"))
            overrides.setTransferAttributeOverride(tx.id, AttributeOverride.Removed(type, ""))
            overrides.setTransferAttributeOverride(tx.id, AttributeOverride.Set(type, "g", "b"))
            assertEquals(
                listOf(AttributeOverride.Removed(type, ""), AttributeOverride.Set(type, "g", "b")),
                overrides.getTransferAttributeOverrides(tx.id),
            )
            overrides.clearTransferAttributeOverride(tx.id, type, "")
            assertEquals(listOf(AttributeOverride.Set(type, "g", "b")), overrides.getTransferAttributeOverrides(tx.id))

            val usd = currency("USD")
            val tradeId =
                repositories.importEngine.createTrade(
                    timestamp = now,
                    description = "Sell GBP",
                    fromAccountId = bank,
                    fromAmount = Money.fromDisplayValue(BigDecimal("100"), gbp),
                    toAccountId = bank,
                    toAmount = Money.fromDisplayValue(BigDecimal("125"), usd),
                )
            overrides.setTradeAttributeOverride(tradeId, AttributeOverride.Removed(type, ""))
            assertEquals(listOf(AttributeOverride.Removed(type, "")), overrides.getTradeAttributeOverrides(tradeId))
            assertFailsWith<IllegalArgumentException> {
                overrides.setTradeAttributeOverride(
                    tradeId,
                    AttributeOverride.Set(type, "g", "x"),
                )
            }
            overrides.clearTradeAttributeOverride(tradeId, type)
            assertEquals(emptyList(), overrides.getTradeAttributeOverrides(tradeId))

            val record = repositories.importRecordRepository.insertRecord(ImportRecordKey.CsvRow(csvImport(rows = 2), 1), "h")
            val content = ObservationContent(transferValues(bank, shop, gbp))
            val a = repositories.observationRepository.insertObservation(ObservationOwner.Record(record), "one", tx.id, content)
            val b = repositories.observationRepository.insertObservation(ObservationOwner.Record(record), "two", tx.id, content)

            overrides.pin(ObservationPin(a, tx.id))
            assertEquals(listOf(ObservationPin(a, tx.id)), overrides.getPins(listOf(a, b)))
            overrides.unpin(a)
            assertEquals(emptyList(), overrides.getPins(listOf(a)))

            overrides.split(ObservationSplit.of(b, a))
            assertEquals(setOf(ObservationSplit(a, b)), overrides.getSplits(listOf(b)))
            overrides.unsplit(ObservationSplit(a, b))
            assertEquals(emptySet(), overrides.getSplits(listOf(a, b)))
            assertEquals(emptyList(), overrides.getPins(emptyList<ObservationId>()))
        }
}
