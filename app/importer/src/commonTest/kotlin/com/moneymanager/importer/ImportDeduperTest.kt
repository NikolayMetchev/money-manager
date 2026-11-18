package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ImportDeduperTest {
    private val currency =
        Currency(id = CurrencyId(1L), code = "GBP", name = "British Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val source = AccountId(1)
    private val target = AccountId(2)
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun money(major: Long) = Money(amount = major * 100, asset = currency)

    private fun importTransfer(
        rowIndex: Long,
        description: String = "Coffee",
        amount: Long = 5,
        timestamp: Instant = baseTime,
        uniqueKey: Map<String, String>? = null,
        attributes: List<NewAttribute> = emptyList(),
        apiId: String? = null,
        src: AccountId = source,
        tgt: AccountId = target,
    ) = ImportTransfer(
        rowKey = ImportRowKey.CsvRow(rowIndex),
        fromAccount = AccountRef.Existing(src),
        toAccount = AccountRef.Existing(tgt),
        // Provenance is irrelevant to dedupe classification; any source works here.
        source = Source.SampleGenerator,
        timestamp = timestamp,
        description = description,
        amount = money(amount),
        attributes = attributes,
        uniqueKey = uniqueKey,
        apiId = apiId,
    )

    private fun existing(
        id: Long,
        description: String = "Coffee",
        amount: Long = 5,
        timestamp: Instant = baseTime,
        uniqueKey: Map<String, String> = emptyMap(),
        attributes: Map<AttributeTypeId, String> = emptyMap(),
        apiId: String? = null,
        src: AccountId = source,
        tgt: AccountId = target,
    ) = ExistingTransferInfo(
        transferId = TransferId(id),
        transfer =
            Transfer(
                id = TransferId(id),
                timestamp = timestamp,
                description = description,
                sourceAccountId = src,
                targetAccountId = tgt,
                amount = money(amount),
            ),
        attributes = attributes,
        uniqueKey = uniqueKey,
        apiId = apiId,
    )

    @Test
    fun none_importsEverything() {
        val deduper = ImportDeduper(DedupePolicy.None, existing = listOf(existing(1)))
        val result = deduper.classify(listOf(importTransfer(0)))
        assertEquals(ImportStatus.IMPORTED, result.single().status)
    }

    @Test
    fun uniqueId_identicalIsDuplicate() {
        val key = mapOf("txid" to "abc")
        val deduper =
            ImportDeduper(
                DedupePolicy.UniqueIdentifier(),
                existing = listOf(existing(7, uniqueKey = key)),
            )
        val result = deduper.classify(listOf(importTransfer(0, uniqueKey = key))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(7), result.existing)
    }

    @Test
    fun uniqueId_sameKeyDifferentFieldsIsUpdated() {
        val key = mapOf("txid" to "abc")
        val deduper =
            ImportDeduper(
                DedupePolicy.UniqueIdentifier(),
                existing = listOf(existing(7, description = "Old", uniqueKey = key)),
            )
        val result = deduper.classify(listOf(importTransfer(0, description = "New", uniqueKey = key))).single()
        assertEquals(ImportStatus.UPDATED, result.status)
        assertEquals(TransferId(7), result.existing)
    }

    @Test
    fun uniqueId_newKeyIsImported() {
        val deduper =
            ImportDeduper(
                DedupePolicy.UniqueIdentifier(),
                existing = listOf(existing(7, uniqueKey = mapOf("txid" to "abc"))),
            )
        val result = deduper.classify(listOf(importTransfer(0, uniqueKey = mapOf("txid" to "zzz")))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
    }

    @Test
    fun uniqueId_dedupesWithinBatch() {
        val key = mapOf("txid" to "dup")
        val deduper = ImportDeduper(DedupePolicy.UniqueIdentifier(), existing = emptyList())
        val result =
            deduper.classify(
                listOf(
                    importTransfer(0, uniqueKey = key),
                    importTransfer(1, uniqueKey = key),
                ),
            )
        assertEquals(ImportStatus.IMPORTED, result[0].status)
        assertEquals(ImportStatus.DUPLICATE, result[1].status)
    }

    @Test
    fun fuzzy_exactMatchIsDuplicate() {
        val deduper = ImportDeduper(DedupePolicy.FuzzyAllFields(), existing = listOf(existing(3)))
        val result = deduper.classify(listOf(importTransfer(0))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(3), result.existing)
    }

    @Test
    fun fuzzy_trailingDriftAndOneDayShiftIsDuplicate() {
        val longDescription = "CASH WITHDRAWAL AT NATIONWIDE BUILDING SOCIETY ATM WIMBLEDON HILL, WIMBLEDON,20.00"
        val deduper =
            ImportDeduper(
                DedupePolicy.FuzzyAllFields(),
                existing = listOf(existing(3, description = longDescription)),
            )
        val incoming =
            importTransfer(0, description = "$longDescription GBP", timestamp = baseTime + 1.days)
        val result = deduper.classify(listOf(incoming)).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
    }

    @Test
    fun fuzzy_differentPayeeIsImported() {
        val deduper =
            ImportDeduper(
                DedupePolicy.FuzzyAllFields(),
                existing = listOf(existing(3, description = "THAMES WATER REF 0183323799")),
            )
        val incoming = importTransfer(0, description = "BRITISH GAS REF 9981112223")
        val result = deduper.classify(listOf(incoming)).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
    }

    @Test
    fun fuzzy_zeroToleranceImportsTheSameRowOnALaterDay() {
        val deduper =
            ImportDeduper(DedupePolicy.FuzzyAllFields(dateTolerance = Duration.ZERO), existing = listOf(existing(3)))
        val incoming = importTransfer(0, timestamp = baseTime + 1.days)
        assertEquals(ImportStatus.IMPORTED, deduper.classify(listOf(incoming)).single().status)
    }

    @Test
    fun fuzzy_outsideDateToleranceIsImported() {
        val deduper = ImportDeduper(DedupePolicy.FuzzyAllFields(), existing = listOf(existing(3)))
        val incoming = importTransfer(0, timestamp = baseTime + 5.days)
        val result = deduper.classify(listOf(incoming)).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
    }

    @Test
    fun apiMultiKey_apiIdMatchIsDuplicate() {
        val deduper = ImportDeduper(DedupePolicy.ApiMultiKey(), existing = listOf(existing(9, apiId = "feed-1")))
        val result = deduper.classify(listOf(importTransfer(0, description = "different", apiId = "feed-1"))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(9), result.existing)
    }

    @Test
    fun apiMultiKey_uniqueKeyMatchIsDuplicate() {
        val key = mapOf("starling-id" to "abc")
        val deduper = ImportDeduper(DedupePolicy.ApiMultiKey(), existing = listOf(existing(9, uniqueKey = key)))
        val result = deduper.classify(listOf(importTransfer(0, uniqueKey = key))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
    }

    @Test
    fun apiMultiKey_fieldMatchBidirectionalIsDuplicate() {
        // Same timestamp + amount, accounts swapped -> still a duplicate.
        val deduper =
            ImportDeduper(DedupePolicy.ApiMultiKey(), existing = listOf(existing(9, src = target, tgt = source)))
        val result = deduper.classify(listOf(importTransfer(0, description = "anything"))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
    }

    @Test
    fun apiMultiKey_newIsImportedAndDedupesInBatch() {
        val deduper = ImportDeduper(DedupePolicy.ApiMultiKey(), existing = emptyList())
        val result =
            deduper.classify(
                listOf(
                    importTransfer(0, apiId = "feed-1"),
                    importTransfer(1, apiId = "feed-1"),
                    // Distinct core fields so the field-match tier doesn't flag it against row 0.
                    importTransfer(2, apiId = "feed-2", amount = 99, timestamp = baseTime + 10.days),
                ),
            )
        assertEquals(ImportStatus.IMPORTED, result[0].status)
        assertEquals(ImportStatus.DUPLICATE, result[1].status)
        assertEquals(ImportStatus.IMPORTED, result[2].status)
    }

    private val reconcilingFuzzyPolicy =
        DedupePolicy.FuzzyAllFields(
            reconcileWindow = SettlementWindow.Elapsed(60 * 60L),
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
        )

    @Test
    fun fuzzy_neverTakesTheRecordALaterRowOfTheBatchMatchesExactly() {
        // A statement listing two like £1,000 payments three days apart, newest first, after an earlier statement
        // booked only the first. The later payment is within the fuzzy tolerance of the booked one, but the
        // booked one is the earlier row's own record, so the later payment is a movement of its own.
        val payment = "BILL PAYMENT VIA FASTER PAYMENT TO COINBASE REFERENCE CBAGBPUALDWLZXEXC , MANDATE NO 91"
        val deduper =
            ImportDeduper(DedupePolicy.FuzzyAllFields(), existing = listOf(existing(9, description = payment, amount = 1000)))
        val result =
            deduper.classify(
                listOf(
                    importTransfer(0, description = payment, amount = 1000, timestamp = baseTime + 3.days),
                    importTransfer(1, description = payment, amount = 1000),
                ),
            )
        assertEquals(ImportStatus.IMPORTED, result[0].status, "the later payment is not the earlier one again")
        assertEquals(ImportStatus.DUPLICATE, result[1].status)
        assertEquals(TransferId(9), result[1].existing)
    }

    // Unidentified-counterparty reconcile: the crypto.com/Monzo case. The wallet's own export records a
    // deposit as "GBP Deposit (via FPS)" against a placeholder account, while the bank's feed names both
    // ends (it resolves the wallet by sort code + account number). Only the owned account, the direction
    // and the amount are common to the two records.
    private val wallet = AccountId(30)

    private val unidentifiedPolicy =
        DedupePolicy.FuzzyAllFields(
            reconcileWindow = SettlementWindow.Elapsed(60 * 60L),
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
            unidentifiedCounterpartyAttributeTypeId = AttributeTypeId(-9),
        )

    private val payee = AccountId(33)

    @Test
    fun fuzzy_prefersTheNearestOfSeveralCandidates() {
        val deduper =
            ImportDeduper(
                reconcilingFuzzyPolicy,
                existing = listOf(existing(9, timestamp = baseTime + 2.days), existing(10, timestamp = baseTime + 1.hours)),
            )
        val result = deduper.classify(listOf(importTransfer(0))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(10), result.existing)
    }

    @Test
    fun fuzzyPass_neverDropsARowAsTheDuplicateOfAnExcludedLeg() {
        // Another export's copy of the payment ("Gerardfarnoll") is already excluded as reconciled. PayPal's
        // own "gerard farnolle" row is similar enough to be its fuzzy duplicate, but dropping it would leave
        // the payment counted nowhere.
        val excludedCopy =
            existing(
                41,
                description = "Gerardfarnoll",
                src = wallet,
                tgt = payee,
                attributes = mapOf(AttributeTypeId(-1) to "reconciled"),
            )
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(excludedCopy))
        val result = deduper.classify(listOf(importTransfer(0, description = "gerard farnolle", src = wallet, tgt = payee))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun fuzzyPass_stillMatchesAnExcludedLegOfTheSameFile() {
        // Re-running a row whose earlier import was excluded as reconciled: that leg is this file's own record
        // of the row, which stays excluded, so the row is its duplicate rather than a second, counted copy.
        val earlierImport =
            existing(
                41,
                description = "gerard farnolle",
                src = wallet,
                tgt = payee,
                attributes = mapOf(AttributeTypeId(-1) to "reconciled"),
            )
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(earlierImport), ownSourceLegs = setOf(TransferId(41)))
        val row = importTransfer(0, description = "gerard farnolle", timestamp = baseTime + 2.days, src = wallet, tgt = payee)
        assertEquals(ImportStatus.DUPLICATE, deduper.classify(listOf(row)).single().status)
    }

    @Test
    fun fuzzy_sameCoreDifferentAttributesIsUpdated() {
        val typeId = AttributeTypeId(42)
        val deduper =
            ImportDeduper(
                DedupePolicy.FuzzyAllFields(),
                existing = listOf(existing(3, attributes = mapOf(typeId to "old"))),
            )
        val incoming = importTransfer(0, attributes = listOf(NewAttribute(typeId, "new")))
        val result = deduper.classify(listOf(incoming)).single()
        assertEquals(ImportStatus.UPDATED, result.status)
    }
}
