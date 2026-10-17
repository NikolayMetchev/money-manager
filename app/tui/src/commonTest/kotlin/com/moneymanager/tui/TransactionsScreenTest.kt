package com.moneymanager.tui

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.createCurrency
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class TransactionsScreenTest {
    // One payment a day at noon UTC, 2024-01-01 to 2024-02-29, so a local date names the same day anywhere near UTC.
    private val firstDay = Instant.parse("2024-01-01T12:00:00Z")

    @Test
    fun browsesAnAccountFromTheAccountsTabWithPagingRangesJumpsAndDetail() {
        val location = createTestDatabaseLocation()
        try {
            tui(location, DEFAULT_SCREENS) { _, controller ->
                screen { it.contains("o open another database") }
                val session = checkNotNull(controller.session)
                val engine = session.importEngine
                val gbp =
                    Currency(
                        id = engine.createCurrency("GBP", "Pound Sterling"),
                        code = "GBP",
                        name = "Pound Sterling",
                        scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
                    )
                val reference = engine.getOrCreateAttributeType("reference")
                engine.import(
                    ImportBatch(
                        accountsToCreate = listOf("Bank fees", "Current", "Shop").map { newAccount(it) },
                        transfers =
                            (0 until DAYS).map { day ->
                                ImportTransfer(
                                    source = Source.Manual,
                                    rowKey = ImportRowKey.Manual(day.toLong()),
                                    fromAccount = local("Current"),
                                    toAccount = local("Shop"),
                                    timestamp = firstDay + day.days,
                                    description = "Groceries ${day + 1}",
                                    amount = Money.fromDisplayValue("1", gbp),
                                    attributes = listOf(NewAttribute(reference, "ref-${day + 1}")),
                                    fee =
                                        if (day == DAYS - 1) {
                                            ImportFee(
                                                local("Current"),
                                                local("Bank fees"),
                                                Money.fromDisplayValue("0.5", gbp),
                                                "Card fee",
                                                FEE,
                                            )
                                        } else {
                                            null
                                        },
                                )
                            },
                    ),
                )

                // Enter on an account in Accounts opens it here; the balances are refreshed on the way in.
                press("2")
                screen { it.contains("Accounts · 3 of 3") }
                press("ArrowDown")
                press("Enter")
                val newest = screen { it.contains("Transactions · Current") && it.contains("older below") }
                assertContains(newest, "Groceries 60")
                assertContains(newest, "-60.5 GBP")

                // Moving down past the loaded rows loads the rest.
                press("PageDown")
                press("PageDown")
                screen { it.contains("61 rows loaded") && !it.contains("older below") }
                press("End")
                assertContains(screen { it.contains("Groceries 1 ") }, "Groceries 2 ")

                // A range keeps only its days, both ends included.
                press("f")
                type("2024-01-10")
                press("Enter")
                type("2024-01-12")
                press("Enter")
                val ranged = screen { it.contains("3 rows loaded") }
                assertContains(ranged, "Groceries 12")
                assertContains(ranged, "Groceries 10")
                assertFalse(ranged.contains("Groceries 13"))
                press("x")
                screen { it.contains("Range: all dates") && it.contains("older below") }

                // A jump lands on the day, with the newer rows still a key away.
                press("g")
                type("2024-01-20")
                press("Enter")
                val jumped = screen { it.contains("newer above") }
                assertContains(jumped, "Groceries 20")
                assertFalse(jumped.contains("Groceries 21"))
                press("Home")
                screen { it.contains("Groceries 60") && !it.contains("newer above") }

                // The fee row is the newest; its detail links back to the payment it was charged on.
                press("Enter")
                val fee = screen { it.contains("Linked transfers") && it.contains("fee ←") }
                assertContains(fee, "Card fee")
                press("l")
                val payment = screen { it.contains("reference: ref-60") }
                assertContains(payment, "fee →")
                assertContains(payment, "manual")
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    private fun local(name: String) = AccountRef.Local(LocalAccountKey(name))

    private fun newAccount(name: String) =
        ImportAccountIntent(
            key = LocalAccountKey(name),
            source = Source.Manual,
            name = name,
            openingDate = Instant.parse("2023-12-01T00:00:00Z"),
        )

    private companion object {
        const val DAYS = 60
        val FEE = RelationshipTypeId(2)
    }
}
