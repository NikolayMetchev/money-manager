package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ImportPlanTest {
    private val gbp = Currency(CurrencyId(1), code = "GBP", name = "Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val at = Instant.parse("2026-01-02T03:04:05Z")
    private val names = mapOf(AccountId(1) to "Monzo", AccountId(2) to "Shop", AccountId(3) to "Cafe")
    private val importId = Uuid.random()

    private fun transaction(
        id: Long,
        description: String,
        to: Long = 2,
        amount: String = "5",
    ) = PlannedTransaction(
        TransferId(id),
        at,
        description,
        listOf(PlannedLeg(AccountId(1), AccountId(to), Money.fromDisplayValue(amount, gbp))),
    )

    private fun snapshot(vararg transactions: PlannedTransaction) = TransactionSnapshot(transactions.associateBy { it.id })

    @Test
    fun anIdInBothIsAnUpdateOnlyWhenAFieldDiffers_otherwiseCreatedOrRemoved() {
        val plan =
            planFrom(
                before = snapshot(transaction(1, "kept"), transaction(2, "coffee"), transaction(3, "gone")),
                after = snapshot(transaction(1, "kept"), transaction(2, "coffee", to = 3, amount = "6"), transaction(4, "new")),
            )

        assertEquals(listOf(TransferId(4)), plan.created.map { it.id })
        assertEquals(listOf(TransferId(3)), plan.removed.map { it.id })
        assertEquals(listOf(PlannedField.ACCOUNTS, PlannedField.AMOUNT), plan.updated.single().fields)
        assertEquals("1 created · 1 updated · 1 removed", plan.summary())
        assertEquals(
            listOf(
                ImportPlanSection("Created (1)", listOf("#4 $at · new · Monzo → Shop 5 GBP")),
                ImportPlanSection(
                    "Updated (1)",
                    listOf("#2 coffee: accounts Monzo → Shop 5 GBP → Monzo → Cafe 6 GBP; amount Monzo → Shop 5 GBP → Monzo → Cafe 6 GBP"),
                ),
                ImportPlanSection("Removed (1)", listOf("#3 $at · gone · Monzo → Shop 5 GBP")),
            ),
            plan.sections(accountName = { names.getValue(it) }, amount = {
                if (it ==
                    Money.fromDisplayValue("5", gbp)
                ) {
                    "5 GBP"
                } else {
                    "6 GBP"
                }
            }),
        )
    }

    @Test
    fun aTransactionReMadeUnderANewIdBySameRecordIsAnUpdate() {
        val row = {
            i: Long,
            account: Long?,
            ->
            Source.Csv(CsvImportId(importId), rowIndex = i, statementAccountId = account?.let(::AccountId))
        }
        val before =
            TransactionSnapshot(
                listOf(transaction(1, "same"), transaction(2, "coffee"), transaction(3, "gone")).associateBy { it.id },
                mapOf(TransferId(1) to row(0, 1), TransferId(2) to row(1, 1), TransferId(3) to row(2, 1)),
            )
        val after =
            TransactionSnapshot(
                listOf(transaction(11, "same"), transaction(12, "coffee", to = 3), transaction(14, "new")).associateBy { it.id },
                mapOf(TransferId(11) to row(0, null), TransferId(12) to row(1, 1), TransferId(14) to row(3, 1)),
            )

        val plan = planFrom(before, after)

        assertEquals(listOf(TransferId(14)), plan.created.map { it.id })
        assertEquals(listOf(TransferId(3)), plan.removed.map { it.id })
        assertEquals(listOf(TransferId(2) to TransferId(12)), plan.updated.map { it.before.id to it.after.id })
        assertTrue(
            plan
                .sections({
                    names.getValue(it)
                })
                .single { it.title == "Updated (1)" }
                .lines
                .single()
                .startsWith("#2 → #12 coffee: accounts"),
        )
    }

    @Test
    fun aConflictNeedsAttention_andNothingChangedSaysSo() {
        assertEquals("no changes", ImportPlan().summary())
        assertTrue(ImportPlan().sections({ "" }).isEmpty())

        val conflict =
            MaterializationConflict(7, MaterializationConflict.Subject.Field(TransferField.DESCRIPTION), "Rent", "RENT", "RENT LTD")
        val section = ImportPlan(conflicts = listOf(conflict)).sections({ "" }).single()

        assertTrue(section.needsAttention)
        assertEquals(listOf("#7 description: kept \"Rent\"; the import now says \"RENT LTD\" (was \"RENT\")"), section.lines)
    }
}
