@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Auditable
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The kind of write an intent describes. Defaults to [CREATE] everywhere so the import producers
 * (CSV/QIF/API), which only ever create, are unaffected; manual edits set [UPDATE]/[DELETE].
 */
enum class ImportOperation { CREATE, UPDATE, DELETE }

/** An intent declaring one write on an entity; [operation] selects create/update/delete. */
interface WriteIntent {
    val operation: ImportOperation
}

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

/** Builder-chosen placeholder identity for a category created in this batch, before DB ids exist. */
@JvmInline
value class LocalCategoryKey(
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
    /** Where this account came from (e.g. the import as a whole, or the API accounts node). */
    override val source: Source,
    val match: AccountMatchKey = AccountMatchKey.AlwaysCreate,
    val name: String? = null,
    val openingDate: Instant? = null,
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
    /** [ImportOperation.CREATE] (default), or UPDATE/DELETE of [existingId]. */
    override val operation: ImportOperation = ImportOperation.CREATE,
    /** The account to UPDATE/DELETE (required for those operations). */
    val existingId: AccountId? = null,
    /** UPDATE: the new account row (null to change only attributes), passed straight to the repository. */
    val account: Account? = null,
    /** UPDATE: attribute rows to remove. */
    val deletedAttributeIds: Set<Long> = emptySet(),
    /** UPDATE: existing attribute rows to change (id -> new value). */
    val updatedAttributes: Map<Long, NewAttribute> = emptyMap(),
) : Auditable,
    WriteIntent

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
    /** Where this person came from (e.g. the import as a whole, or the API node the holder came from). */
    override val source: Source,
    val match: PersonMatchKey = PersonMatchKey.ByNameKey(""),
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val attributes: List<NewAttribute> = emptyList(),
    /** [ImportOperation.CREATE] (default), or UPDATE/DELETE of [existingId]. */
    override val operation: ImportOperation = ImportOperation.CREATE,
    /** The person to UPDATE/DELETE (required for those operations). */
    val existingId: PersonId? = null,
    /** UPDATE: the new person row (null to change only attributes), passed straight to the repository. */
    val person: Person? = null,
    /** UPDATE: attribute rows to remove. */
    val deletedAttributeIds: Set<Long> = emptySet(),
    /** UPDATE: existing attribute rows to change (id -> new value). */
    val updatedAttributes: Map<Long, NewAttribute> = emptyMap(),
) : Auditable,
    WriteIntent

data class ImportOwnershipIntent(
    /** Where this ownership link came from (e.g. the import as a whole, or the API node it came from). */
    override val source: Source,
    /** CREATE: the person, as a batch-local key (resolved against created people). */
    val personKey: LocalPersonKey? = null,
    /** CREATE: the person, when already persisted (the UI add-owner case). */
    val existingPersonId: PersonId? = null,
    /** CREATE: the account (an existing id, or a batch-local key). */
    val account: AccountRef? = null,
    /** [ImportOperation.CREATE] (default) or DELETE of [existingId]. Ownerships have no UPDATE. */
    override val operation: ImportOperation = ImportOperation.CREATE,
    /** DELETE: the ownership row id. */
    val existingId: Long? = null,
) : Auditable,
    WriteIntent

/**
 * A category to create/update/delete. [key] lets a CREATE's generated id be read back from
 * [ImportResult.createdCategoryIds]; [existingId] targets the row for UPDATE/DELETE.
 */
data class ImportCategoryIntent(
    val key: LocalCategoryKey,
    override val source: Source,
    val name: String? = null,
    val parentId: Long? = null,
    override val operation: ImportOperation = ImportOperation.CREATE,
    val existingId: Long? = null,
    /** UPDATE: the new category row, passed straight to the repository. */
    val category: Category? = null,
) : Auditable,
    WriteIntent

/** Builder-chosen placeholder identity for a currency created in this batch, before DB ids exist. */
@JvmInline
value class LocalCurrencyKey(
    val value: String,
)

/**
 * A currency to create/update/delete. CREATE upserts by [code] (existing code is updated to [name]);
 * the resulting id is read back from [ImportResult.createdCurrencyIds] via [key]. [existingId]/[currency]
 * target the row for UPDATE; [existingId] for DELETE.
 */
data class ImportCurrencyIntent(
    val key: LocalCurrencyKey,
    override val source: Source,
    val code: String? = null,
    val name: String? = null,
    override val operation: ImportOperation = ImportOperation.CREATE,
    val existingId: CurrencyId? = null,
    /** UPDATE: the new currency row, passed straight to the repository. */
    val currency: Currency? = null,
) : Auditable,
    WriteIntent

/** Builder-chosen placeholder identity for a crypto asset created in this batch, before DB ids exist. */
@JvmInline
value class LocalCryptoKey(
    val value: String,
)

/**
 * A crypto asset to create/update/delete. CREATE upserts by [code] (ticker) at the fixed 18-decimal
 * crypto scale; the default name comes from the crypto registry. The resulting id is read back from
 * [ImportResult.createdCryptoIds] via [key]. [existingId]/[crypto] target the row for UPDATE;
 * [existingId] for DELETE.
 */
data class ImportCryptoIntent(
    val key: LocalCryptoKey,
    override val source: Source,
    val code: String? = null,
    val name: String? = null,
    override val operation: ImportOperation = ImportOperation.CREATE,
    val existingId: CryptoId? = null,
    /** UPDATE: the new crypto row, passed straight to the repository. */
    val crypto: CryptoAsset? = null,
) : Auditable,
    WriteIntent

/** Builder-chosen placeholder identity for a trade created in this batch, before DB ids exist. */
@JvmInline
value class LocalTradeKey(
    val value: String,
)

/**
 * A cross-asset exchange to create: [fromAmount] leaves [fromAccountId] and [toAmount] enters
 * [toAccountId], where the two [Money] legs may be denominated in different assets. The engine
 * allocates a `transaction_id`, inserts the trade, and records provenance; the resulting id is read
 * back from [ImportResult.createdTradeIds] via [key]. (CREATE only for now.)
 */
data class ImportTradeIntent(
    val key: LocalTradeKey,
    val source: Source,
    val timestamp: Instant,
    val description: String,
    val fromAccountId: AccountId,
    val fromAmount: Money,
    val toAccountId: AccountId,
    val toAmount: Money,
)

/** A request to merge [deletedId] into [survivingId] (reassign its transfers, then delete it). */
data class AccountMergeRequest(
    val deletedId: AccountId,
    val survivingId: AccountId,
)

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
 * A charge routed through a chain of conduit accounts (e.g. card → Curve → PayPal → merchant),
 * modelled as linked movements of the same amount: a funding leg (the transfer's own `fromAccount` →
 * first conduit, built by the producer) and one spend leg per adjacent pair of the chain
 * (C1 → C2, …, Cn → [merchantTarget]), each linked to the previous movement via a
 * [relationshipTypeId] (`pass-through`) relationship. The engine expands the spend legs in the same
 * batch — like [ImportFee], producers don't allocate ids. Every conduit nets to zero, so the spend is
 * counted once (in either direction).
 *
 * This is fully generic: the engine never inspects the merchant text or knows any conduit's name —
 * detection + extraction happen in the importers via [PassThroughDetector] and user-editable config.
 *
 * @property conduits The accounts money passes through, ordered outermost first (resolved by the
 *   producer to real/created accounts); the main transfer's conduit side is `conduits.first()`.
 * @property merchantTarget The real merchant the final spend leg pays.
 * @property amount The charge amount; identical on every leg.
 * @property spendDescriptions One description per spend leg (the remainder after each conduit's prefix
 *   was peeled; the last is the cleaned merchant). The funding leg keeps the main transfer's description.
 * @property relationshipTypeId The `pass-through` relationship type linking each movement (id1) to the
 *   next leg (id2).
 * @property rowKey Provenance key for the spend legs; when null the engine falls back to the main row key.
 * @property incoming True for a refund/cancellation arriving back on the card: the funding leg (the main
 *   transfer, built by the producer) is `conduits.first()` → card and each engine spend leg runs
 *   in reverse (C2 → C1, …, [merchantTarget] → Cn).
 */
data class ImportPassThrough(
    val conduits: List<AccountRef>,
    val merchantTarget: AccountRef,
    val amount: Money,
    val spendDescriptions: List<String>,
    val relationshipTypeId: RelationshipTypeId,
    val rowKey: ImportRowKey? = null,
    val incoming: Boolean = false,
) {
    init {
        require(conduits.isNotEmpty()) { "ImportPassThrough needs at least one conduit" }
        require(spendDescriptions.size == conduits.size) {
            "spendDescriptions must have one entry per spend leg (got ${spendDescriptions.size} for ${conduits.size} conduits)"
        }
    }

    /** The conduit the main (funding) transfer moves money to/from. */
    val conduit: AccountRef get() = conduits.first()
}

/**
 * A relationship from the owning [ImportTransfer] (id1) to another transfer **created in the SAME
 * batch**, referenced by its [ImportRowKey] rather than a persisted id. Unlike [NewRelationship]
 * (whose `relatedTransferId` must already exist), this lets a producer link two co-created transfers
 * before either has an id; the engine resolves [relatedRowKey] to the related transfer's real id
 * (created in this chunk, or in an earlier chunk of this batch) and resolves [typeName] get-or-create.
 *
 * The link is silently skipped when [relatedRowKey] resolves to no newly-created transfer — i.e. the
 * related row was deduped to an existing transfer (DUPLICATE/UPDATED) or errored. This is deliberate:
 * such a link is already redundant. On re-import the owning row and its target dedupe together, so the
 * relationship persisted by the first import still stands; nothing needs re-linking.
 */
data class BatchRelationship(
    val relatedRowKey: ImportRowKey,
    val typeName: String,
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
 * @property batchRelationships Relationships to OTHER transfers created in the same batch, referenced
 *   by their [ImportRowKey]; the engine resolves each related row key to its newly-created id (in this
 *   chunk or an earlier chunk of this batch) and the type name get-or-create. This transfer becomes
 *   id1, the related transfer id2. A link whose target was not newly created (deduped/errored) is
 *   skipped — see [BatchRelationship].
 * @property uniqueKey Unique-identifier dedupe key (attribute name -> value), or null for fuzzy dedupe.
 * @property fee An optional fee charged on this transaction, expanded by the engine into a linked transfer.
 */
data class ImportTransfer(
    override val source: Source,
    val rowKey: ImportRowKey? = null,
    val fromAccount: AccountRef? = null,
    val toAccount: AccountRef? = null,
    val timestamp: Instant? = null,
    val description: String = "",
    val amount: Money? = null,
    val attributes: List<NewAttribute> = emptyList(),
    val relationships: List<NewRelationship> = emptyList(),
    val batchRelationships: List<BatchRelationship> = emptyList(),
    val uniqueKey: Map<String, String>? = null,
    // API transaction id, for DedupePolicy.ApiMultiKey.
    val apiId: String? = null,
    val excludedFromBalances: Boolean = false,
    val fee: ImportFee? = null,
    /** An optional conduit pass-through (e.g. Curve), expanded by the engine into a linked spend leg. */
    val passThrough: ImportPassThrough? = null,
    /** [ImportOperation.CREATE] (default), or UPDATE/DELETE of [existingId]. */
    override val operation: ImportOperation = ImportOperation.CREATE,
    /** The transfer to UPDATE/DELETE (required for those operations). */
    val existingId: TransferId? = null,
    /** UPDATE: attribute rows to remove. */
    val deletedAttributeIds: Set<Long> = emptySet(),
    /** UPDATE: existing attribute rows to change (id -> new value). */
    val updatedAttributes: Map<Long, NewAttribute> = emptyMap(),
) : Auditable,
    WriteIntent

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

    /** Provenance key for a manually-entered transfer (no source row); [index] keeps a batch's keys unique. */
    data class Manual(
        val index: Long,
    ) : ImportRowKey
}

/**
 * The unified intermediate import model. CSV/QIF/API importers build this and hand it to the central
 * import engine, which creates accounts/people/ownerships, dedupes, and bulk-writes transfers.
 */
data class ImportBatch(
    val transfers: List<ImportTransfer> = emptyList(),
    val dedupePolicy: DedupePolicy = DedupePolicy.None,
    val accountsToCreate: List<ImportAccountIntent> = emptyList(),
    val peopleToCreate: List<ImportPersonIntent> = emptyList(),
    val ownerships: List<ImportOwnershipIntent> = emptyList(),
    val categories: List<ImportCategoryIntent> = emptyList(),
    val currencies: List<ImportCurrencyIntent> = emptyList(),
    val cryptoAssets: List<ImportCryptoIntent> = emptyList(),
    val trades: List<ImportTradeIntent> = emptyList(),
    val csvStrategyMutations: List<CsvStrategyMutation> = emptyList(),
    val apiStrategyMutations: List<ApiStrategyMutation> = emptyList(),
    val passThroughMutations: List<PassThroughMutation> = emptyList(),
    val accountMappingMutations: List<AccountMappingMutation> = emptyList(),
    val csvImportMutations: List<CsvImportMutation> = emptyList(),
    val qifImportMutations: List<QifImportMutation> = emptyList(),
    val importDirectoryMutations: List<ImportDirectoryMutation> = emptyList(),
    val apiSessionMutations: List<ApiSessionMutation> = emptyList(),
    val settings: ImportSettings? = null,
    val accountMerges: List<AccountMergeRequest> = emptyList(),
    val accountUnmerges: List<MergeId> = emptyList(),
    /**
     * Attribute-type names to resolve (get-or-create) before anything else; the resulting ids are
     * returned in [ImportResult.attributeTypeIds]. Lets producers build [NewAttribute]s by id without
     * holding an `AttributeTypeWriteRepository` — they issue a resolve-only batch first.
     */
    val attributeTypeNames: List<String> = emptyList(),
    /** Relationship-type names to resolve (get-or-create); ids returned in [ImportResult.relationshipTypeIds]. */
    val relationshipTypeNames: List<String> = emptyList(),
    /** Required when [dedupePolicy] is [DedupePolicy.UniqueIdentifier] or [DedupePolicy.ApiMultiKey]. */
    val uniqueKeyExtractor: ExistingUniqueKeyExtractor? = null,
    /** Required when [dedupePolicy] is [DedupePolicy.ApiMultiKey]. */
    val apiIdExtractor: ExistingApiIdExtractor? = null,
) {
    companion object {
        /**
         * Builds a batch of direct edits (no dedup): the UI describes the exact writes it wants. Each
         * list may carry create/update/delete intents (per their [ImportOperation]); merges/unmerges are
         * their own lists.
         */
        fun manualEdits(
            transfers: List<ImportTransfer> = emptyList(),
            accounts: List<ImportAccountIntent> = emptyList(),
            people: List<ImportPersonIntent> = emptyList(),
            ownerships: List<ImportOwnershipIntent> = emptyList(),
            categories: List<ImportCategoryIntent> = emptyList(),
            currencies: List<ImportCurrencyIntent> = emptyList(),
            accountMerges: List<AccountMergeRequest> = emptyList(),
            accountUnmerges: List<MergeId> = emptyList(),
        ): ImportBatch =
            ImportBatch(
                transfers = transfers,
                dedupePolicy = DedupePolicy.None,
                accountsToCreate = accounts,
                peopleToCreate = people,
                ownerships = ownerships,
                categories = categories,
                currencies = currencies,
                accountMerges = accountMerges,
                accountUnmerges = accountUnmerges,
            )
    }
}
