package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ObservationExpanderTest {
    private val gbp = Currency(CurrencyId(1), code = "GBP", name = "Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val usd = Currency(CurrencyId(2), code = "USD", name = "Dollar", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val importId = CsvImportId(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    private val at = Instant.parse("2026-01-02T03:04:05Z")
    private val feeType = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID)
    private val passThroughType = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID)
    private val reconciledType = RelationshipTypeId(99)
    private val excluded = NewAttribute(AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), "deleted")

    private fun account(id: Long) = AccountRef.Existing(AccountId(id))

    private fun gbp(value: String) = Money.fromDisplayValue(value, gbp)

    private fun row(
        index: Long,
        fee: ImportFee? = null,
        passThrough: ImportPassThrough? = null,
        attributes: List<NewAttribute> = emptyList(),
        batchRelationships: List<BatchRelationship> = emptyList(),
        conversion: ImportConversion? = null,
    ) = ImportTransfer(
        source = Source.Csv(importId),
        rowKey = ImportRowKey.CsvRow(index),
        fromAccount = account(1),
        toAccount = account(2),
        timestamp = at,
        description = "row $index",
        amount = gbp("10"),
        attributes = attributes,
        batchRelationships = batchRelationships,
        fee = fee,
        passThrough = passThrough,
        conversion = conversion,
    )

    private fun owner(index: Long) = ObservationOwnerKey.Record(ImportRecordKey.CsvRow(importId, index))

    private fun key(
        index: Long,
        slot: String,
    ) = ObservationKey(owner(index), slot)

    private val fee = ImportFee(account(1), account(9), gbp("1"), "fee", feeType)

    private val chain =
        ImportPassThrough(
            conduits = listOf(account(3), account(4)),
            merchantTarget = account(5),
            amount = gbp("10"),
            spendDescriptions = listOf("via 3", "via 4"),
            relationshipTypeId = passThroughType,
        )

    private fun expand(vararg rows: ExpansionRow) = ObservationExpander.expandTransfers(rows.toList(), mapOf("link" to reconciledType))

    @Test
    fun a_row_fills_main_then_fee_then_one_conduit_slot_per_conduit() {
        val expanded = expand(ExpansionRow(row(7, fee = fee, passThrough = chain))).single()

        assertEquals(
            listOf(
                key(7, ObservationSlot.MAIN),
                key(7, ObservationSlot.FEE),
                key(7, "conduit/0"),
                key(7, "conduit/1"),
            ),
            expanded.legs.map { it.key },
        )
        assertEquals(
            listOf(AccountId(1) to AccountId(2), AccountId(1) to AccountId(9), AccountId(3) to AccountId(4), AccountId(4) to AccountId(5)),
            expanded.legs.map { it.sourceAccountId to it.targetAccountId },
        )
        assertEquals(Source.Csv(importId, rowIndex = 7), expanded.main.provenance)
    }

    @Test
    fun the_main_leg_links_its_fee_and_first_spend_leg_and_each_spend_leg_the_next() {
        val legs = expand(ExpansionRow(row(7, fee = fee, passThrough = chain))).single().legs

        assertEquals(
            listOf(
                LegLink(LegLinkTarget.Expanded(key(7, ObservationSlot.FEE)), feeType),
                LegLink(LegLinkTarget.Expanded(key(7, "conduit/0")), passThroughType),
            ),
            legs[0].links,
        )
        assertEquals(emptyList(), legs[1].links)
        assertEquals(listOf(LegLink(LegLinkTarget.Expanded(key(7, "conduit/1")), passThroughType)), legs[2].links)
        assertEquals(emptyList(), legs[3].links)
    }

    @Test
    fun an_excluded_row_excludes_its_fee_and_spend_legs() {
        val excludedRow = row(7, fee = fee, passThrough = chain, attributes = listOf(excluded))

        val legs = expand(ExpansionRow(excludedRow)).single().legs
        assertEquals(List(4) { listOf(excluded) }, legs.map { it.attributes })
    }

    @Test
    fun a_reversal_links_its_spend_leg_to_the_leg_it_reverses() {
        val reverses = LegLink(LegLinkTarget.Expanded(key(3, "conduit/0")), reconciledType)

        val legs =
            expand(
                ExpansionRow(row(3, passThrough = chain)),
                ExpansionRow(
                    row(8, passThrough = chain),
                    reversals =
                        mapOf(0 to reverses),
                ),
            )[1].legs

        assertEquals(reverses, legs[1].links.last())
    }

    @Test
    fun a_batch_relationship_links_to_the_related_row_main_leg() {
        val rows =
            expand(
                ExpansionRow(row(1)),
                ExpansionRow(row(2, batchRelationships = listOf(BatchRelationship(ImportRowKey.CsvRow(1), "link")))),
            )

        assertEquals(listOf(LegLink(LegLinkTarget.Expanded(key(1, ObservationSlot.MAIN)), reconciledType)), rows[1].main.links)
    }

    @Test
    fun keys_are_the_same_in_every_order_and_on_every_run() {
        val rows = listOf(ExpansionRow(row(1, fee = fee)), ExpansionRow(row(2, passThrough = chain)), ExpansionRow(row(3)))

        fun keysOf(order: List<ExpansionRow>) = expand(*order.toTypedArray()).flatMap { it.legs }.associate { it.key to it.description }

        val expected = keysOf(rows)
        assertEquals(expected, keysOf(rows))
        assertEquals(expected, keysOf(rows.reversed()))
        assertEquals(expected, keysOf(listOf(rows[1], rows[2], rows[0])))
    }

    @Test
    fun a_group_owns_the_transfers_it_assembles() {
        val group = ImportGroupKey("strategy", "dust", "BNB|1700000000000")

        val expanded = expand(ExpansionRow(row(1).copy(group = group))).single()

        assertEquals(ObservationKey(ObservationOwnerKey.Group(group), ObservationSlot.MAIN), expanded.main.key)
    }

    @Test
    fun two_transfers_of_one_slot_are_refused() {
        assertFailsWith<IllegalArgumentException> { expand(ExpansionRow(row(1)), ExpansionRow(row(1))) }
    }

    @Test
    fun a_settled_foreign_amount_expands_into_the_trade_slot() {
        val settled = Money.fromDisplayValue("13", usd)

        val outgoing = ObservationExpander.expandConversion(row(4, conversion = ImportConversion(settled, incoming = false)))!!
        assertEquals(key(4, ObservationSlot.TRADE), outgoing.key)
        assertEquals(AccountId(1), outgoing.accountId)
        assertEquals(settled to gbp("10"), outgoing.fromAmount to outgoing.toAmount)
        assertEquals("Convert USD to GBP", outgoing.description)

        val incoming = ObservationExpander.expandConversion(row(4, conversion = ImportConversion(settled, incoming = true)))!!
        assertEquals(AccountId(2), incoming.accountId)
        assertEquals(gbp("10") to settled, incoming.fromAmount to incoming.toAmount)

        assertNull(ObservationExpander.expandConversion(row(4)))
    }
}
