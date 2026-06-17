@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Auditable
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Computes the unique-identifier dedupe key for an existing (database) transfer, so the engine can
 * compare it against incoming [ImportTransfer.uniqueKey] values. The keys are strategy-defined (e.g.
 * CSV column names, API field names), so only the builder knows how to derive them — this lets the
 * engine own dedupe while the builder owns the key definition. Required for [DedupePolicy.UniqueIdentifier].
 */
fun interface ExistingUniqueKeyExtractor {
    fun extract(transfer: Transfer): Map<String, String>?
}

/**
 * Computes the API transaction id for an existing (database) transfer, so [DedupePolicy.ApiMultiKey]
 * can match incoming [ImportTransfer.apiId] values. Required for [DedupePolicy.ApiMultiKey].
 */
fun interface ExistingApiIdExtractor {
    fun extract(transfer: Transfer): String?
}

/** Builder-chosen placeholder identity for an account created in this batch, before DB ids exist. */
@JvmInline
value class LocalAccountKey(
    val value: String,
)

/** Builder-chosen placeholder identity for a person created in this batch, before DB ids exist. */
@JvmInline
value class LocalPersonKey(
    val value: String,
)

/** A reference to an account: an already-resolved DB id, or a key into [ImportBatch.accountsToCreate]. */
sealed interface AccountRef {
    data class Existing(
        val id: AccountId,
    ) : AccountRef

    data class Local(
        val key: LocalAccountKey,
    ) : AccountRef
}

/** How the engine decides whether an account already exists before creating it. */
sealed interface AccountMatchKey {
    /** Match an existing account by exact name (CSV/QIF). */
    data class ByName(
        val name: String,
    ) : AccountMatchKey

    /** Match by an external-id attribute value (API source accounts + counterparties). */
    data class ByExternalId(
        val typeId: AttributeTypeId,
        val value: String,
    ) : AccountMatchKey

    /** Match (and consolidate onto) a single account per built-in type value, e.g. ATM (API). */
    data class ByBuiltInType(
        val typeId: AttributeTypeId,
        val value: String,
    ) : AccountMatchKey

    /** Match a personal counterparty by a stable identity key (API). */
    data class ByPersonalCounterparty(
        val key: String,
    ) : AccountMatchKey

    /** Never reuse — always create a new account. */
    data object AlwaysCreate : AccountMatchKey
}

/**
 * An account to create during import (if not already matched). [match] tells the engine how to look
 * for an existing account first; [attributes] are written on creation (e.g. external-id, built-in type).
 */
data class ImportAccountIntent(
    val key: LocalAccountKey,
    val match: AccountMatchKey,
    val name: String,
    val openingDate: Instant,
    /** Where this account came from (e.g. the import as a whole, or the API `$.accounts[i]` node). */
    override val source: Source,
    val categoryId: Long = Category.UNCATEGORIZED_ID,
    val attributes: List<NewAttribute> = emptyList(),
    /**
     * When this intent reuses (adopts) a pre-existing account via the bank-identity fallback, whether to
     * re-point that account onto this intent — renaming it and adding this intent's attributes (e.g. the
     * provider external id). Set for own/source accounts so they take over a counterparty another provider
     * created for the same real account; left false for counterparties, which merge in silently without
     * renaming the account they merge into.
     */
    val adoptOnBankMatch: Boolean = false,
) : Auditable

/** How the engine decides whether a person already exists before creating them. */
sealed interface PersonMatchKey {
    /**
     * Match by an external-id attribute value. [nameKeyFallback], when set, lets the engine fall back to
     * a normalised-name match if no person carries the external id (cross-provider matching), backfilling
     * the external id onto the matched person so a later import resolves them by id. This mirrors the API
     * importer's identity chain (provider id, then name) without losing the id when matched by name.
     */
    data class ByExternalId(
        val typeId: AttributeTypeId,
        val value: String,
        val nameKeyFallback: String? = null,
    ) : PersonMatchKey

    /** Match by a normalised full-name key. */
    data class ByNameKey(
        val nameKey: String,
    ) : PersonMatchKey
}

data class ImportPersonIntent(
    val key: LocalPersonKey,
    val match: PersonMatchKey,
    val firstName: String,
    /** Where this person came from (e.g. the import as a whole, or the API node the holder came from). */
    override val source: Source,
    val lastName: String? = null,
    val attributes: List<NewAttribute> = emptyList(),
) : Auditable

data class ImportOwnershipIntent(
    val personKey: LocalPersonKey,
    val account: AccountRef,
    /** Where this ownership link came from (e.g. the import as a whole, or the API node it came from). */
    override val source: Source,
) : Auditable

/**
 * A fee charged on a transaction, modelled as its own movement linked to the main transfer via a
 * `fee` [NewRelationship] (main = id1, fee = id2). The engine expands this into a second [Transfer]
 * created in the same batch; producers don't allocate ids. The fee is a real movement and counts in
 * balances unless the main transfer is itself excluded (declined/reconciled), in which case it inherits
 * the exclusion so a duplicate's fee isn't double-counted.
 *
 * @property source Where the fee money leaves (typically the main transfer's own account).
 * @property target The consolidated per-provider fee account.
 * @property relationshipTypeId The `fee` relationship type id (supplied by the producer, mirroring how
 *   reconciliation passes its type id via [DedupePolicy]), used to link the fee (id2) to its main (id1).
 * @property rowKey Provenance key for the fee movement; when null the engine falls back to the main
 *   transfer's row key. Producers set this to point the audit trail at the fee's own source node (e.g.
 *   the `atm_fees_detailed.fee_amount` JSON node) rather than the whole transaction.
 *
 * Note: unlike [ImportTransfer.source] (which is the provenance [Source]), [ImportFee.source] is the
 * FROM account — a fee inherits the main transfer's provenance at engine time, so it is not [Auditable].
 */
data class ImportFee(
    val source: AccountRef,
    val target: AccountRef,
    val amount: Money,
    val description: String,
    val relationshipTypeId: RelationshipTypeId,
    val rowKey: ImportRowKey? = null,
)

/**
 * A transfer to import. Account references may be [AccountRef.Existing] (resolved by the builder) or
 * [AccountRef.Local] (resolved by the engine from [ImportBatch.accountsToCreate]).
 *
 * @property rowKey Opaque per-row provenance + status-writeback key.
 * @property fromAccount The account the money leaves (the transfer's source account).
 * @property toAccount The account the money arrives in (the transfer's target account).
 * @property source Where this transfer came from (the provenance [Source]); the engine fills in the
 *   per-row detail from [rowKey] via `Source.forRow`.
 * @property attributes Transfer attributes with type ids already resolved by the builder.
 * @property relationships Relationships to existing transfers, created once this transfer's id exists.
 * @property uniqueKey Unique-identifier dedupe key (attribute name -> value), or null for fuzzy dedupe.
 * @property fee An optional fee charged on this transaction, expanded by the engine into a linked transfer.
 */
data class ImportTransfer(
    val rowKey: ImportRowKey,
    val fromAccount: AccountRef,
    val toAccount: AccountRef,
    override val source: Source,
    val timestamp: Instant,
    val description: String,
    val amount: Money,
    val attributes: List<NewAttribute> = emptyList(),
    val relationships: List<NewRelationship> = emptyList(),
    val uniqueKey: Map<String, String>? = null,
    // API transaction id, for DedupePolicy.ApiMultiKey.
    val apiId: String? = null,
    val excludedFromBalances: Boolean = false,
    val fee: ImportFee? = null,
) : Auditable

/** Opaque per-row provenance + status-writeback key, identifying the source row of a transfer. */
sealed interface ImportRowKey {
    data class CsvRow(
        val rowIndex: Long,
    ) : ImportRowKey

    data class QifRecord(
        val recordIndex: Long,
        // QIF split records expand to several transfers sharing one recordIndex; splitIndex keeps the
        // row keys unique while recordIndex remains the unit of status write-back.
        val splitIndex: Int = 0,
    ) : ImportRowKey

    data class ApiJsonPath(
        val requestId: ApiRequestId,
        val jsonPath: String,
    ) : ImportRowKey
}

/**
 * The unified intermediate import model. CSV/QIF/API importers build this and hand it to the central
 * import engine, which creates accounts/people/ownerships, dedupes, and bulk-writes transfers.
 */
data class ImportBatch(
    val transfers: List<ImportTransfer>,
    val dedupePolicy: DedupePolicy,
    val accountsToCreate: List<ImportAccountIntent> = emptyList(),
    val peopleToCreate: List<ImportPersonIntent> = emptyList(),
    val ownerships: List<ImportOwnershipIntent> = emptyList(),
    /** Required when [dedupePolicy] is [DedupePolicy.UniqueIdentifier] or [DedupePolicy.ApiMultiKey]. */
    val uniqueKeyExtractor: ExistingUniqueKeyExtractor? = null,
    /** Required when [dedupePolicy] is [DedupePolicy.ApiMultiKey]. */
    val apiIdExtractor: ExistingApiIdExtractor? = null,
)
