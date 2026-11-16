package com.moneymanager.database.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

// Test names in this package are dexed for the Android device test run: no commas or apostrophes.
class SourceObservationsDbTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now), Source.SampleGenerator)

    private suspend fun currency(code: String): Currency {
        val id = repositories.currencyRepository.upsertCurrencyByCode(code, code)
        return repositories.currencyRepository.getCurrencyById(id).first()!!
    }

    private suspend fun observationsOf(id: TransactionId) = repositories.observationRepository.getObservationsOfTransaction(id)

    private suspend fun book(transfer: ImportTransfer): TransferId =
        repositories.importEngine
            .import(ImportBatch.manualEdits(transfers = listOf(transfer)))
            .createdTransferIds
            .values
            .single()

    private suspend fun delete(id: TransferId) {
        repositories.importEngine.import(
            ImportBatch.manualEdits(
                transfers = listOf(ImportTransfer(source = Source.Manual, operation = ImportOperation.DELETE, existingId = id)),
            ),
        )
    }

    private suspend fun apiSession(): Pair<ApiSessionId, ApiRequestId> {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession(deviceId, Clock.System.now(), null)
        val requestId =
            repositories.apiSessionRepository.insertRequest(
                sessionId = sessionId,
                method = "GET",
                url = "https://example.test/transactions",
                headers = emptyMap(),
            )
        return sessionId to requestId
    }

    @Test
    fun `a manual transfer is observed by its creation and the observation goes with it`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val id =
                book(
                    ImportTransfer(
                        source = Source.Manual,
                        rowKey = ImportRowKey.Manual(0),
                        fromAccount = AccountRef.Existing(bank),
                        toAccount = AccountRef.Existing(shop),
                        timestamp = now,
                        description = "Coffee",
                        amount = Money(350, gbp),
                    ),
                )

            val observation = observationsOf(id).single()
            assertIs<ObservationOwner.Manual>(observation.owner)
            assertEquals(ObservationSlot.MAIN, observation.slot)
            val values = assertIs<ObservedValues.Transfer>(observation.content.values)
            assertEquals(bank, values.sourceAccountId)
            assertEquals(shop, values.targetAccountId)
            assertEquals("Coffee", values.description)

            delete(id)
            assertTrue(observationsOf(id).isEmpty())
        }

    @Test
    fun `an api transfer is observed by its item record`() =
        runDbTest {
            val gbp = currency("GBP")
            val bank = account("Bank")
            val shop = account("Shop")
            val (sessionId, requestId) = apiSession()
            val id =
                book(
                    ImportTransfer(
                        source = Source.Api(sessionId, requestId, JsonPath("$.items[0]"), statementAccountId = bank),
                        rowKey = ImportRowKey.ApiJsonPath(requestId, "$.items[0]"),
                        fromAccount = AccountRef.Existing(bank),
                        toAccount = AccountRef.Existing(shop),
                        timestamp = now,
                        description = "Lunch",
                        amount = Money(1200, gbp),
                    ),
                )

            val observation = observationsOf(id).single()
            val owner = assertIs<ObservationOwner.Record>(observation.owner)
            val record = repositories.importRecordRepository.getRecord(owner.recordId)!!
            assertEquals(ImportRecordKey.ApiItem(sessionId, requestId, "$.items[0]"), record.key)
            assertEquals(ObservationEnd.SOURCE, observation.content.facts.ownEnd)
            assertEquals(listOf(record), repositories.importRecordRepository.getApiSessionRecords(sessionId))

            delete(id)
            assertTrue(observationsOf(id).isEmpty())
        }

    @Test
    fun `deleting a session drops the item records it holds`() =
        runDbTest {
            val (sessionId, requestId) = apiSession()
            repositories.importRecordRepository.insertRecord(ImportRecordKey.ApiItem(sessionId, requestId, "$.items[0]"), "h")

            repositories.importEngine.import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.DeleteSession(sessionId))))
            assertTrue(repositories.importRecordRepository.getApiSessionRecords(sessionId).isEmpty())
        }

    @Test
    fun `an api trade is observed by the item its owner names`() =
        runDbTest {
            val gbp = currency("GBP")
            val eur = currency("EUR")
            val exchange = account("Exchange")
            val (sessionId, requestId) = apiSession()
            val itemKey = ImportRecordKey.ApiItem(sessionId, requestId, "$.trades[0]")
            val key = LocalTradeKey("t")
            val result =
                repositories.importEngine.import(
                    ImportBatch(
                        trades =
                            listOf(
                                ImportTradeIntent(
                                    key = key,
                                    source = Source.Api(sessionId, requestId, JsonPath("$.trades[0]")),
                                    timestamp = now,
                                    description = "Buy EUR",
                                    fromAccountId = exchange,
                                    fromAmount = Money(1000, gbp),
                                    toAccountId = exchange,
                                    toAmount = Money(1150, eur),
                                    owner = ObservationOwnerKey.Record(itemKey),
                                ),
                            ),
                    ),
                )
            val tradeId = result.createdTradeIds.getValue(key)

            val observation = observationsOf(tradeId).single()
            assertEquals(ObservationSlot.TRADE, observation.slot)
            val owner = assertIs<ObservationOwner.Record>(observation.owner)
            assertEquals(itemKey, repositories.importRecordRepository.getRecord(owner.recordId)!!.key)
            assertIs<ObservedValues.Trade>(observation.content.values)
        }
}
