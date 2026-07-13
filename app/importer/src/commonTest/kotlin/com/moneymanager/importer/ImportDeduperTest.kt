@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ImportDeduperTest {
    private val currency = Currency(id = CurrencyId(1L), code = "GBP", name = "British Pound")
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
                DedupePolicy.UniqueIdentifier,
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
                DedupePolicy.UniqueIdentifier,
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
                DedupePolicy.UniqueIdentifier,
                existing = listOf(existing(7, uniqueKey = mapOf("txid" to "abc"))),
            )
        val result = deduper.classify(listOf(importTransfer(0, uniqueKey = mapOf("txid" to "zzz")))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
    }

    @Test
    fun uniqueId_dedupesWithinBatch() {
        val key = mapOf("txid" to "dup")
        val deduper = ImportDeduper(DedupePolicy.UniqueIdentifier, existing = emptyList())
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

    private val reconcilingPolicy =
        DedupePolicy.ApiMultiKey(
            reconcileWindow = 5.minutes,
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
        )

    @Test
    fun apiMultiKey_reconcilesCrossSourceMirrorWithinWindow() {
        // An existing transfer from a different source (apiId == null) with the same source+target+amount
        // and a near (within-window) timestamp: keep the incoming record but tag it excluded + linked.
        // existing(9) defaults apiId to null: a transfer from a different source.
        val deduper = ImportDeduper(reconcilingPolicy, existing = listOf(existing(9)))
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "from other bank", apiId = "monzo-1", timestamp = baseTime + 1.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        // The duplicate keeps a plain exclusion attribute (no embedded id) so balances still exclude it...
        assertEquals(
            NewAttribute(AttributeTypeId(-1), "reconciled"),
            result.transfer.attributes.single { it.typeId == AttributeTypeId(-1) },
        )
        // ...and the link to the existing transfer lives in a reconciled relationship instead.
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(9), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun apiMultiKey_doesNotReconcileSameProviderRepeat() {
        // The existing transfer is from THIS provider (apiId set), so a genuine repeat with a different
        // id is imported as a normal new transfer, never reconciled away.
        val deduper = ImportDeduper(reconcilingPolicy, existing = listOf(existing(9, apiId = "monzo-0")))
        val result =
            deduper.classify(listOf(importTransfer(0, apiId = "monzo-1", timestamp = baseTime + 1.minutes))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun apiMultiKey_doesNotReconcileOutsideWindow() {
        // existing(9) defaults apiId to null: a transfer from a different source.
        val deduper = ImportDeduper(reconcilingPolicy, existing = listOf(existing(9)))
        val result =
            deduper.classify(listOf(importTransfer(0, apiId = "monzo-1", timestamp = baseTime + 10.minutes))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
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
            reconcileWindow = 60.minutes,
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
        )

    @Test
    fun fuzzy_reconcilesCrossSourceMirrorWithinWindow() {
        // The crypto.com case: the same top-up recorded as "GBP Deposit" (card CSV, already imported)
        // and "Top Up Card" (fiat CSV, incoming). Descriptions are too different for the fuzzy pass,
        // but the account pair + amount match within the window: import it excluded + linked.
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(existing(9, description = "GBP Deposit")))
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "Top Up Card", timestamp = baseTime + 1.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            NewAttribute(AttributeTypeId(-1), "reconciled"),
            result.transfer.attributes.single { it.typeId == AttributeTypeId(-1) },
        )
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(9), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun fuzzy_exactMatchWinsOverReconciliation() {
        // Re-importing the same file must stay a plain DUPLICATE, never an excluded+linked copy.
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(existing(3)))
        val result = deduper.classify(listOf(importTransfer(0))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(3), result.existing)
    }

    @Test
    fun fuzzy_doesNotReconcileOutsideWindow() {
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(existing(9, description = "GBP Deposit")))
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "Top Up Card", timestamp = baseTime + 90.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun fuzzy_doesNotReconcileOppositeDirection() {
        // Direction-sensitive: a Cash->Card top-up must not reconcile against a Card->Cash movement.
        val deduper =
            ImportDeduper(
                reconcilingFuzzyPolicy,
                existing = listOf(existing(9, description = "GBP Deposit", src = target, tgt = source)),
            )
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "Top Up Card", timestamp = baseTime + 1.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun fuzzy_withoutReconcileConfigNeverReconciles() {
        // Defaults keep the pre-existing behavior: a same-pair same-amount row with a different
        // description within the window is simply imported.
        val deduper = ImportDeduper(DedupePolicy.FuzzyAllFields(), existing = listOf(existing(9, description = "GBP Deposit")))
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "Top Up Card", timestamp = baseTime + 1.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.isEmpty())
        assertTrue(result.transfer.relationships.isEmpty())
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
