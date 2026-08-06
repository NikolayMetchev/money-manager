package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigDecimal
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
import com.moneymanager.importengineapi.AccountBridge
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

    private val reconcilingUniqueIdPolicy =
        DedupePolicy.UniqueIdentifier(
            reconcileWindow = 5.minutes,
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
        )

    @Test
    fun uniqueId_reconcilesCrossSourceMirrorWithinWindow() {
        // The Monzo case: a personal->joint transfer imported from the joint account's own CSV export
        // carries a different Transaction ID than the mirror leg already imported from the personal
        // account's export, but same accounts+amount within the window: keep it, tagged excluded+linked.
        val deduper = ImportDeduper(reconcilingUniqueIdPolicy, existing = listOf(existing(9, uniqueKey = mapOf("txid" to "personal-side"))))
        val result =
            deduper
                .classify(listOf(importTransfer(0, uniqueKey = mapOf("txid" to "joint-side"), timestamp = baseTime + 1.minutes)))
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
    fun uniqueId_sameKeyStillWinsOverReconciliation() {
        // A genuine re-import (same unique key) must stay a plain DUPLICATE, never an excluded+linked copy.
        val key = mapOf("txid" to "abc")
        val deduper = ImportDeduper(reconcilingUniqueIdPolicy, existing = listOf(existing(7, uniqueKey = key)))
        val result = deduper.classify(listOf(importTransfer(0, uniqueKey = key))).single()
        assertEquals(ImportStatus.DUPLICATE, result.status)
        assertEquals(TransferId(7), result.existing)
    }

    @Test
    fun uniqueId_doesNotReconcileOutsideWindow() {
        val deduper = ImportDeduper(reconcilingUniqueIdPolicy, existing = listOf(existing(9, uniqueKey = mapOf("txid" to "personal-side"))))
        val result =
            deduper
                .classify(listOf(importTransfer(0, uniqueKey = mapOf("txid" to "joint-side"), timestamp = baseTime + 10.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun uniqueId_inBatchDuplicateStaysDuplicateEvenWhenItAlsoMatchesAReconcileCandidate() {
        // A literal repeat of the same unique key within one batch must stay a plain in-batch DUPLICATE,
        // never get imported twice as separate "reconciled" copies just because it also happens to match
        // a cross-source reconcile candidate.
        val key = mapOf("txid" to "dup")
        val deduper = ImportDeduper(reconcilingUniqueIdPolicy, existing = listOf(existing(9, uniqueKey = mapOf("txid" to "personal-side"))))
        val result =
            deduper.classify(
                listOf(
                    importTransfer(0, uniqueKey = key, timestamp = baseTime + 1.minutes),
                    importTransfer(1, uniqueKey = key, timestamp = baseTime + 1.minutes),
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

    // Internal-transfer reconciliation (exchange <-> bridged app account). `source` (AccountId(1)) plays
    // the exchange account; `appAccount` is the bridged bank account (e.g. Monzo); incoming transfers run
    // exchange -> dangling counterparty (`target`, AccountId(2)) until rewritten onto the bridge.
    private val appAccount = AccountId(3)

    private fun internalTransferPolicy(
        window: Duration = 24.hours,
        tolerance: BigDecimal = BigDecimal.ZERO,
    ) = DedupePolicy.ApiMultiKey(
        reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
        reconciledRelationshipTypeId = RelationshipTypeId(1),
        internalTransferBridges = listOf(AccountBridge(exchangeAccountId = source, appAccountId = appAccount)),
        internalTransferWindow = window,
        internalTransferAmountTolerance = tolerance,
    )

    @Test
    fun internalTransfer_consumesEachExistingLegAtMostOnce() {
        // The real Kraken/Monzo shape: seven same-amount exchange withdrawals all fall within the 24h
        // window of a SINGLE existing bank credit. `selectNearestUnconsumedLeg` picks the nearest
        // EXISTING candidate for a given incoming row — with only one existing candidate here, whichever
        // incoming row is processed first (batch/list order) claims it; consumption then MUST stop any
        // of the other six from also claiming it (which would otherwise fabricate six phantom bank
        // credits). See internalTransfer_prefersNearestExistingLeg for the true nearest-selection case
        // (one incoming row, multiple existing candidates).
        val bankCredit = existing(50, timestamp = baseTime, src = AccountId(4), tgt = appAccount)
        val offsetsHours = listOf(-15.0, -13.0, -6.0, 0.1, 6.0, 13.0, 15.0)
        val incoming =
            offsetsHours.mapIndexed { i, h ->
                importTransfer(i.toLong(), timestamp = baseTime + (h * 60).minutes)
            }
        val result = ImportDeduper(internalTransferPolicy(), existing = listOf(bankCredit)).classify(incoming)

        // A withdrawal (exchange is source) rewrites `toAccount`, not `fromAccount` — see
        // classifyAsInternalTransferReconciled: fromAccount only changes for a deposit (exchange target).
        val rewritten = result.filter { it.transfer.toAccount == AccountRef.Existing(appAccount) }
        assertEquals(1, rewritten.size)
        val rewrittenSingle = rewritten.single()
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(50), typeId = RelationshipTypeId(1)),
            rewrittenSingle.transfer.relationships.single(),
        )
        (result - rewrittenSingle).forEach {
            assertEquals(AccountRef.Existing(target), it.transfer.toAccount)
            assertTrue(it.transfer.relationships.isEmpty())
        }
    }

    @Test
    fun internalTransfer_prefersNearestExistingLeg() {
        val nearLeg = existing(50, timestamp = baseTime, src = AccountId(4), tgt = appAccount)
        val farLeg = existing(51, timestamp = baseTime + 6.hours, src = AccountId(5), tgt = appAccount)
        val incoming = importTransfer(0, timestamp = baseTime + 10.minutes)
        val result =
            ImportDeduper(internalTransferPolicy(), existing = listOf(farLeg, nearLeg)).classify(listOf(incoming)).single()
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(50), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun internalTransfer_matchesRegardlessOfExistingLegCounterparty() {
        // Three existing legs from three different bank-side counterparty accounts, all crediting the
        // bridged app account: the match only cares that the app account received it, not who sent it.
        val legs =
            listOf(
                existing(50, amount = 10, src = AccountId(4), tgt = appAccount),
                existing(51, amount = 20, src = AccountId(5), tgt = appAccount),
                existing(52, amount = 30, src = AccountId(6), tgt = appAccount),
            )
        val incoming = listOf(importTransfer(0, amount = 20, timestamp = baseTime + 10.minutes))
        val result = ImportDeduper(internalTransferPolicy(), existing = legs).classify(incoming).single()
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(51), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun internalTransfer_zeroToleranceRejectsAnyAmountDifference() {
        val bankCredit = existing(50, amount = 100, src = AccountId(4), tgt = appAccount)
        val incoming = importTransfer(0, amount = 101, timestamp = baseTime + 10.minutes)
        val result =
            ImportDeduper(internalTransferPolicy(tolerance = BigDecimal.ZERO), existing = listOf(bankCredit))
                .classify(listOf(incoming))
                .single()
        assertEquals(AccountRef.Existing(target), result.transfer.toAccount)
        assertTrue(result.transfer.relationships.isEmpty())
    }

    @Test
    fun internalTransfer_toleranceAllowsConfiguredAmountDifference() {
        val bankCredit = existing(50, amount = 100, src = AccountId(4), tgt = appAccount)
        // 101 vs 100 is within a 2% tolerance.
        val incoming = importTransfer(0, amount = 101, timestamp = baseTime + 10.minutes)
        val result =
            ImportDeduper(internalTransferPolicy(tolerance = BigDecimal("2")), existing = listOf(bankCredit))
                .classify(listOf(incoming))
                .single()
        assertEquals(AccountRef.Existing(appAccount), result.transfer.toAccount)
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(50), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
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
    fun fuzzy_doesNotCollapseTwoRealMovementsOntoOneExistingLeg() {
        // A wallet export gives every deposit the same wording, so two genuine £5 deposits hours apart
        // differ only in their timestamp — which the fuzzy pass tolerates. One existing leg can only ever
        // be one of them; the other is a movement in its own right.
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(existing(9, description = "GBP Deposit (via FPS)")))
        val result =
            deduper.classify(
                listOf(
                    importTransfer(0, description = "GBP Deposit (via FPS)"),
                    importTransfer(1, description = "GBP Deposit (via FPS)", timestamp = baseTime + 8.hours),
                ),
            )
        assertEquals(ImportStatus.DUPLICATE, result[0].status)
        assertEquals(ImportStatus.IMPORTED, result[1].status, "the second deposit is not the first one again")
    }

    @Test
    fun fuzzy_doesNotReconcileAgainstAnAlreadyExcludedLeg() {
        // A third export of the same movement must not chain-exclude: reconciling onto an already-excluded
        // leg would leave the movement counted zero times.
        val alreadyExcluded =
            existing(9, description = "GBP Deposit").copy(attributes = mapOf(AttributeTypeId(-1) to "reconciled"))
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(alreadyExcluded))
        val result =
            deduper
                .classify(listOf(importTransfer(0, description = "Top Up Card", timestamp = baseTime + 1.minutes)))
                .single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
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

    // Unidentified-counterparty reconcile: the crypto.com/Monzo case. The wallet's own export records a
    // deposit as "GBP Deposit (via FPS)" against a placeholder account, while the bank's feed names both
    // ends (it resolves the wallet by sort code + account number). Only the owned account, the direction
    // and the amount are common to the two records.
    private val wallet = AccountId(30)
    private val placeholder = AccountId(31)
    private val bank = AccountId(32)

    private val unidentifiedPolicy =
        DedupePolicy.FuzzyAllFields(
            reconcileWindow = 60.minutes,
            reconciledExclusionAttributeTypeId = AttributeTypeId(-1),
            reconciledRelationshipTypeId = RelationshipTypeId(1),
            unidentifiedCounterpartyAttributeTypeId = AttributeTypeId(-9),
        )

    /** The wallet export's own row: placeholder -> wallet, counterparty unknown. */
    private fun placeholderDeposit(
        rowIndex: Long,
        amount: Long = 5,
        timestamp: Instant = baseTime,
    ) = importTransfer(
        rowIndex,
        description = "GBP Deposit (via FPS)",
        amount = amount,
        timestamp = timestamp,
        src = placeholder,
        tgt = wallet,
        attributes = listOf(NewAttribute(AttributeTypeId(-9), "true")),
    ).copy(unidentifiedCounterpartyAccountId = placeholder)

    /** The bank feed's own record of the same movement: bank -> wallet. */
    private fun bankCredit(
        id: Long,
        amount: Long = 5,
        timestamp: Instant = baseTime,
        src: AccountId = bank,
        tgt: AccountId = wallet,
    ) = existing(id, description = "BI6055443", amount = amount, timestamp = timestamp, src = src, tgt = tgt)

    @Test
    fun unidentifiedCounterparty_reconcilesAgainstIdentifiedLeg() {
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(bankCredit(40, timestamp = baseTime + 20.hours)))
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            NewAttribute(AttributeTypeId(-1), "reconciled"),
            result.transfer.attributes.single { it.typeId == AttributeTypeId(-1) },
        )
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(40), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun unidentifiedCounterparty_doesNotReconcileOppositeDirection() {
        // A withdrawal out of the wallet is not the deposit into it, however well amount and date match.
        val deduper =
            ImportDeduper(
                unidentifiedPolicy,
                existing = listOf(bankCredit(40, src = wallet, tgt = bank, timestamp = baseTime + 1.hours)),
            )
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun unidentifiedCounterparty_doesNotReconcileOutsideWindow() {
        // The window is the fuzzy date tolerance (3 days), not the exact-account reconcile window.
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(bankCredit(40, timestamp = baseTime + 4.days)))
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun unidentifiedCounterparty_consumesEachLegOnce() {
        // Two same-amount deposits a day apart but only one bank credit: the nearest row reconciles, the
        // other stays a plain import rather than both collapsing onto the single bank record.
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(bankCredit(40, timestamp = baseTime + 1.hours)))
        val result = deduper.classify(listOf(placeholderDeposit(0), placeholderDeposit(1, timestamp = baseTime + 1.days)))
        assertTrue(result[0].transfer.attributes.any { it.typeId == AttributeTypeId(-1) }, "first reconciles")
        assertTrue(result[1].transfer.attributes.none { it.typeId == AttributeTypeId(-1) }, "second is a plain import")
    }

    @Test
    fun unidentifiedCounterparty_ignoresOtherPlaceholderLegs() {
        // Another placeholder record is not evidence of the movement's real other end, so the two are left
        // to the exact-account reconcile pass instead of one silently swallowing the other.
        val otherPlaceholderLeg =
            existing(40, description = "Faster Payment In", src = AccountId(33), tgt = wallet, timestamp = baseTime + 1.hours)
                .copy(attributes = mapOf(AttributeTypeId(-9) to "true"))
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(otherPlaceholderLeg))
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun unidentifiedCounterparty_ignoresAlreadyExcludedLegs() {
        // An excluded leg represents no counted movement, so reconciling onto it would count the movement
        // zero times. Amount+direction alone is a loose match: a £5 withdrawal and an excluded £5 card
        // top-up both move £5 out of the same wallet a day apart.
        val excludedLeg =
            existing(43, description = "Top Up Card", src = wallet, tgt = AccountId(35), timestamp = baseTime + 1.hours)
                .copy(attributes = mapOf(AttributeTypeId(-1) to "reconciled"))
        val withdrawal =
            importTransfer(0, description = "GBP Withdrawal (via FPS)", src = wallet, tgt = placeholder)
                .copy(unidentifiedCounterpartyAccountId = placeholder)
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(excludedLeg))
        val result = deduper.classify(listOf(withdrawal)).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun unidentifiedCounterparty_ignoresLegsAnotherRecordAlreadyClaimed() {
        // Consumption must survive across imports: a bank credit an earlier (now excluded) placeholder row
        // already reconciled against must not excuse a second row, or that row's real deposit disappears.
        val deduper =
            ImportDeduper(
                unidentifiedPolicy,
                existing = listOf(bankCredit(40, timestamp = baseTime + 1.hours)),
                claimedReconcileTargets = setOf(TransferId(40)),
            )
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun unidentifiedCounterparty_ignoresMovementsTheSameExportBooksItself() {
        // A wallet statement lists both a £5 bank withdrawal and a £5 top-up of the card it also books.
        // Same account, same direction, same amount, days apart — but two real movements, not one seen twice.
        val card = AccountId(36)
        val topUp = existing(44, description = "Top Up Card", src = wallet, tgt = card, timestamp = baseTime + 2.days)
        val withdrawal =
            importTransfer(0, description = "GBP Withdrawal (via FPS)", src = wallet, tgt = placeholder)
                .copy(unidentifiedCounterpartyAccountId = placeholder)
        val toCard = importTransfer(1, description = "Top Up Card", src = wallet, tgt = card, timestamp = baseTime + 2.days)
        val deduper =
            ImportDeduper(
                unidentifiedPolicy,
                existing = listOf(topUp),
                ownBatchAccounts = setOf(wallet, placeholder, card),
            )
        val result = deduper.classify(listOf(withdrawal, toCard))
        assertTrue(result[0].transfer.attributes.none { it.typeId == AttributeTypeId(-1) }, "the withdrawal stands on its own")
    }

    @Test
    fun unidentifiedCounterparty_withoutConfigNeverReconciles() {
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(bankCredit(40, timestamp = baseTime + 1.hours)))
        val result = deduper.classify(listOf(placeholderDeposit(0))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun identifiedRow_supersedesExistingPlaceholderLeg() {
        // The opposite import order: the placeholder record already exists and the bank feed's row, which
        // names both ends, arrives now. The incoming row is kept and the placeholder leg is excluded.
        val existingPlaceholder =
            existing(41, description = "GBP Deposit (via FPS)", src = placeholder, tgt = wallet, timestamp = baseTime)
                .copy(attributes = mapOf(AttributeTypeId(-9) to "true"))
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(existingPlaceholder))
        val result =
            deduper
                .classify(
                    listOf(importTransfer(0, description = "BI6055443", timestamp = baseTime + 20.hours, src = bank, tgt = wallet)),
                ).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) }, "the better record stays counted")
        assertEquals(TransferId(41), result.excludeExisting?.transfer?.id)
        assertEquals(
            NewRelationship(relatedTransferId = TransferId(41), typeId = RelationshipTypeId(1)),
            result.transfer.relationships.single(),
        )
    }

    @Test
    fun identifiedRow_ignoresIdentifiedExistingLegs() {
        // Without the placeholder marker there is nothing to supersede: a genuine second movement of the
        // same amount through the same account must not be excluded.
        val deduper = ImportDeduper(unidentifiedPolicy, existing = listOf(bankCredit(42, src = AccountId(34))))
        val result =
            deduper
                .classify(
                    listOf(importTransfer(0, description = "BI9999999", timestamp = baseTime + 1.hours, src = bank, tgt = wallet)),
                ).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(null, result.excludeExisting)
    }

    // Funding-card reconcile: a conduit (e.g. Curve) spend, `conduit -> merchant`, reconciles against
    // the funding leg `fundingAccount -> conduit` by amount+window, ignoring the merchant.
    private val conduit = AccountId(10)
    private val fundingAcct = AccountId(20)
    private val merchant = AccountId(2)
    private val otherMerchant = AccountId(3)

    private fun curveRow(
        rowIndex: Long,
        timestamp: Instant = baseTime,
        amount: Long = 5,
        merchantId: AccountId = merchant,
        fundingHint: AccountId? = fundingAcct,
        description: String = "Merchant",
    ) = importTransfer(
        rowIndex = rowIndex,
        description = description,
        amount = amount,
        timestamp = timestamp,
        src = conduit,
        tgt = merchantId,
    ).copy(reconcileFundingAccountId = fundingHint)

    private fun fundingLeg(
        id: Long,
        timestamp: Instant = baseTime,
        amount: Long = 5,
    ) = existing(id, description = "Crv*Whatever", amount = amount, timestamp = timestamp, src = fundingAcct, tgt = conduit)

    @Test
    fun fundingReconcile_matchesFundingLegIgnoringMerchant() {
        // The funding leg has a different account pair and description than the incoming spend, so only
        // the funding-card reconcile (amount + window, merchant-agnostic) can link them.
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0, timestamp = baseTime + 30.minutes))).single()
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
    fun fundingReconcile_runsBeforeFuzzyDuplicate() {
        // The incoming spend is byte-identical to an existing conduit->merchant spend leg (same source
        // conduit, same amount, same description) — which fuzzy would drop as a DUPLICATE. With a
        // funding hint it instead reconciles against the funding leg (kept, excluded, linked).
        val spendLeg = existing(5, description = "Merchant", src = conduit, tgt = merchant)
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(spendLeg, fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0, timestamp = baseTime + 5.minutes))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        // Linked to the FUNDING leg (9), not the spend leg (5).
        assertEquals(
            TransferId(9),
            result.transfer.relationships
                .single()
                .relatedTransferId,
        )
    }

    @Test
    fun fundingReconcile_consumesFundingLegOneToOne() {
        // Two identical Curve rows (repeated daily £5) but only one funding leg: the first reconciles,
        // the second finds no unconsumed funding leg and imports as a new spend.
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0), curveRow(1)))
        assertEquals(ImportStatus.IMPORTED, result[0].status)
        assertTrue(result[0].transfer.attributes.any { it.typeId == AttributeTypeId(-1) }, "first is reconciled")
        assertEquals(ImportStatus.IMPORTED, result[1].status)
        assertTrue(result[1].transfer.attributes.none { it.typeId == AttributeTypeId(-1) }, "second is a plain import")
    }

    @Test
    fun fundingReconcile_prefersNearestTimestamp() {
        val far = fundingLeg(8, timestamp = baseTime)
        val near = fundingLeg(9, timestamp = baseTime + 40.minutes)
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(far, near))
        val result = deduper.classify(listOf(curveRow(0, timestamp = baseTime + 45.minutes))).single()
        assertEquals(
            TransferId(9),
            result.transfer.relationships
                .single()
                .relatedTransferId,
        )
    }

    @Test
    fun fundingReconcile_noHintLeavesRowUnchanged() {
        // Without a resolved funding account the row is a plain new spend (the funding pass is skipped).
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0, fundingHint = null, merchantId = otherMerchant))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun fundingReconcile_amountMismatchDoesNotMatch() {
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0, amount = 6))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
    }

    @Test
    fun fundingReconcile_outsideWindowDoesNotMatch() {
        val deduper = ImportDeduper(reconcilingFuzzyPolicy, existing = listOf(fundingLeg(9)))
        val result = deduper.classify(listOf(curveRow(0, timestamp = baseTime + 90.minutes))).single()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.transfer.attributes.none { it.typeId == AttributeTypeId(-1) })
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
