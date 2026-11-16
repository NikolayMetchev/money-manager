package com.moneymanager.database.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// Test names in this package are dexed for the Android device test run: no commas or apostrophes.
class PersonOverridesDbTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val overrides get() = repositories.personOverrideRepository

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now), Source.SampleGenerator)

    private suspend fun gbp(): Currency {
        val id = repositories.currencyRepository.upsertCurrencyByCode("GBP", "GBP")
        return repositories.currencyRepository.getCurrencyById(id).first()!!
    }

    private suspend fun book(
        from: AccountId,
        to: AccountId,
        amount: Money,
    ): TransferId =
        repositories.importEngine
            .import(
                ImportBatch.manualEdits(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                rowKey = ImportRowKey.Manual(0),
                                fromAccount = AccountRef.Existing(from),
                                toAccount = AccountRef.Existing(to),
                                timestamp = now,
                                description = "Coffee",
                                amount = amount,
                            ),
                        ),
                ),
            ).createdTransferIds
            .values
            .single()

    @Test
    fun `an edit records the fields and attributes it changed`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val cafe = account("Cafe")
            val note = repositories.importEngine.getOrCreateAttributeType("note")
            val id = book(bank, shop, Money(350, gbp))

            repositories.importEngine.import(
                ImportBatch.manualEdits(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = id,
                                fromAccount = AccountRef.Existing(bank),
                                toAccount = AccountRef.Existing(cafe),
                                timestamp = now,
                                description = "Flat white",
                                amount = Money(350, gbp),
                                attributes = listOf(NewAttribute(note, "work")),
                            ),
                        ),
                ),
            )

            assertEquals(
                mapOf(TransferField.DESCRIPTION to "Flat white", TransferField.TARGET_ACCOUNT to cafe.id.toString()),
                overrides.getTransferOverrides(id),
            )
            assertEquals(listOf(AttributeOverride.Set(note, "", "work")), overrides.getTransferAttributeOverrides(id))
        }

    @Test
    fun `a trade attribute set and removed by hand is recorded`() =
        runDbTest {
            val gbp = gbp()
            val eur =
                repositories.currencyRepository.upsertCurrencyByCode("EUR", "EUR").let {
                    repositories.currencyRepository.getCurrencyById(it).first()!!
                }
            val exchange = account("Exchange")
            val note = repositories.importEngine.getOrCreateAttributeType("note")
            val key = LocalTradeKey("t")
            val tradeId =
                repositories.importEngine
                    .import(
                        ImportBatch(
                            trades =
                                listOf(
                                    ImportTradeIntent(
                                        key = key,
                                        source = Source.Manual,
                                        timestamp = now,
                                        description = "Buy EUR",
                                        fromAccountId = exchange,
                                        fromAmount = Money(1000, gbp),
                                        toAccountId = exchange,
                                        toAmount = Money(1150, eur),
                                    ),
                                ),
                        ),
                    ).createdTradeIds
                    .getValue(key)

            repositories.importEngine.import(
                ImportBatch(tradeAttributeMutations = listOf(TradeAttributeMutation.Set(tradeId, note, "holiday"))),
            )
            assertEquals(listOf(AttributeOverride.Set(note, "", "holiday")), overrides.getTradeAttributeOverrides(tradeId))

            repositories.importEngine.import(ImportBatch(tradeAttributeMutations = listOf(TradeAttributeMutation.Remove(tradeId, note))))
            assertEquals(listOf(AttributeOverride.Removed(note, "")), overrides.getTradeAttributeOverrides(tradeId))
        }

    @Test
    fun `merge and unmerge move an account a person set`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val store = account("Store")
            val id = book(bank, store, Money(350, gbp))
            overrides.setTransferOverride(id, TransferField.TARGET_ACCOUNT, shop.id.toString())
            overrides.setTransferOverride(id, TransferField.SOURCE_ACCOUNT, bank.id.toString())

            val mergeId = repositories.accountRepository.mergeAccounts(deletedAccount = shop, survivingAccount = store)
            assertEquals(store.id.toString(), overrides.getTransferOverrides(id)[TransferField.TARGET_ACCOUNT])

            repositories.accountRepository.unmergeAccount(mergeId)
            assertEquals(
                mapOf(TransferField.TARGET_ACCOUNT to shop.id.toString(), TransferField.SOURCE_ACCOUNT to bank.id.toString()),
                overrides.getTransferOverrides(id),
            )
        }
}
