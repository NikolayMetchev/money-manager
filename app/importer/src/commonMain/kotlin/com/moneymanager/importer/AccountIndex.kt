package com.moneymanager.importer

import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.bankKeyFromExternalId
import com.moneymanager.importengineapi.bankKeysFrom

/**
 * Attribute types that tie an account to a specific real bank account/counterparty identity. An
 * existing account carrying none of these is "unclaimed" — see [AccountIndex.match].
 */
private val IDENTITY_ATTR_TYPE_IDS =
    setOf(
        AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID),
    )

internal fun isIdentityAttributeType(typeId: AttributeTypeId): Boolean = typeId in IDENTITY_ATTR_TYPE_IDS

/**
 * Every personal-counterparty bank key this intent carries — one per attribute group holding both a
 * sort code and an account number, so an intent describing an account with two bank identities yields
 * both. Derived from the attribute values, never from the group key, so a stale group key is harmless.
 */
internal fun intentBankKeys(intent: ImportAccountIntent): List<String> =
    bankKeysFrom(intent.attributes.map { AttributeSlot(it.typeId.id, it.value, it.groupKey) })

/** A matched existing account; [adopted] is true for a bank-key fallback match that must be re-pointed. */
internal class AccountMatch(
    val id: AccountId,
    val adopted: Boolean,
)

/**
 * The accounts one batch can match, by every key an intent matches on, kept current as the batch creates,
 * renames and adopts accounts so a later intent sees what an earlier one did.
 *
 * [byName]: every visible account by name. [byAttr]: existing accounts by (attribute type, value), for the
 * attribute types the batch's match keys name. [byPersonalKey]: accounts by sort-code/account-number key, so a
 * personal counterparty reconciles onto an account a *previous* import created for the same real bank account
 * (cross-provider, order-independent). [claimed]: every account carrying an identity attribute
 * ([IDENTITY_ATTR_TYPE_IDS]); one NOT in it carries no signal tying it to a specific real bank account or
 * counterparty, so an own/source intent may adopt it by name.
 */
internal class AccountIndex private constructor(
    val byName: MutableMap<String, AccountId>,
    val byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
    private val byPersonalKey: MutableMap<String, AccountId>,
    private val claimed: MutableSet<AccountId>,
) {
    /**
     * Names of accounts created earlier in THIS batch, so a later same-named intent with no other way to match
     * reuses that account instead of forking a duplicate. Kept apart from [byName] (which also holds pre-existing
     * accounts) because the name fallback must not reach across to a pre-existing account that merely shares a
     * name — see [match].
     */
    private val batchCreatedByName = mutableMapOf<String, AccountId>()

    fun match(intent: ImportAccountIntent): AccountMatch? {
        val primary =
            when (val match = intent.match) {
                is AccountMatchKey.ByName -> byName[match.name]
                is AccountMatchKey.ByExternalId -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByBuiltInType -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey[match.key]
                is AccountMatchKey.AlwaysCreate -> null
                is AccountMatchKey.FeeAccountOf -> error("A fee account is resolved by resolveFeeAccount")
            }
        if (primary != null) return AccountMatch(primary, adopted = false)
        val bankKeys = intentBankKeys(intent)
        // Fallback: an intent that carries bank details (sort code + account number attributes) but didn't
        // match by its primary key adopts a pre-existing account for the same real bank account — e.g. a
        // source account adopting a counterparty another provider created, keeping imports order-independent.
        // The matched account is re-pointed (renamed + the intent's own attributes added) so this provider
        // resolves it by id next time, while its bank attributes keep the other provider matching it.
        bankKeys.firstNotNullOfOrNull { byPersonalKey[it] }?.let { return AccountMatch(it, adopted = true) }
        // An own/source account intent with no bank-identity match yet may still adopt a pre-existing
        // account of the exact same name that carries no identity attributes of its own (an "unclaimed"
        // account — e.g. one created ahead of time by hand, or by the import-directory auto-create-by-name
        // feature). Without this, the first real import for that account forks a duplicate instead of
        // filling in the identity/owner attributes the user's placeholder was waiting for. Scoped to
        // adoptOnBankMatch (own/source) intents only — a COUNTERPARTY must never merge into an unrelated
        // pre-existing account just because the name coincides.
        if (intent.adoptOnBankMatch) {
            intent.name
                ?.let { byName[it] }
                ?.takeIf { it !in claimed }
                ?.let { return AccountMatch(it, adopted = true) }
        }
        // Last resort for an intent with no bank identity — a merchant/conduit counterparty rather than a
        // real bank account: reuse an account of the same name created earlier IN THIS BATCH. A provider
        // routinely mints several external ids for one merchant (Monzo issued two for "Curve"), so without
        // this the second id forks another same-named account. Scoped to batch-created names — never a
        // pre-existing account — so an own/source account can't merge into an unrelated account that
        // merely shares its name. Accounts that carry bank details are excluded too: two people can share
        // a name while owning different accounts.
        if (bankKeys.isEmpty() && intent.match !is AccountMatchKey.AlwaysCreate) {
            intent.name?.let { batchCreatedByName[it] }?.let { return AccountMatch(it, adopted = false) }
        }
        return null
    }

    /** Marks [id] as carrying an identity, so the "unclaimed name" fallback no longer adopts it. */
    fun claim(id: AccountId) {
        claimed += id
    }

    /** Indexes an account this batch created for [intent], under [name] (its resolved, possibly suffixed, name). */
    fun addCreated(
        intent: ImportAccountIntent,
        id: AccountId,
        name: String,
    ) {
        byName.getOrPut(name) { id }
        intent.attributes.forEach { attr -> byAttr.getOrPut(attr.typeId to attr.value) { id } }
        when (val match = intent.match) {
            is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey.getOrPut(match.key) { id }
            else -> Unit
        }
        // Register every bank identity the new account carries (from its sort/account attribute groups) so
        // a later intent in this batch sharing any of them merges into it instead of creating a duplicate.
        intentBankKeys(intent).forEach { key -> byPersonalKey.getOrPut(key) { id } }
        // Keyed by the REQUESTED name (match's lookup key), not the resolved one: a later same-batch intent asking
        // for the same name (e.g. a second external id for the same merchant) must find this account even when its
        // name was suffixed to dodge a pre-existing account's UNIQUE collision.
        intent.name?.let { batchCreatedByName.getOrPut(it) { id } }
    }

    companion object {
        /**
         * Indexes [accounts] and their [attributes] for [intents]. The attribute-keyed indexes are only built when an
         * intent can use them, so a name-only batch (CSV/QIF) does not pay to scan every account's attributes, and
         * [attributes] is only read then.
         */
        suspend fun build(
            intents: List<ImportAccountIntent>,
            accounts: Map<String, AccountId>,
            attributes: suspend () -> List<AccountAttribute>,
        ): AccountIndex {
            val relevantTypeIds =
                intents
                    .mapNotNull { intent ->
                        when (val m = intent.match) {
                            is AccountMatchKey.ByExternalId -> m.typeId
                            is AccountMatchKey.ByBuiltInType -> m.typeId
                            is AccountMatchKey.FeeAccountOf -> FEE_ACCOUNT_FOR_TYPE
                            else -> null
                        }
                    }.toSet()
            // An own/source account intent (adoptOnBankMatch) with no bank-identity match may still adopt a
            // pre-existing SAME-NAMED account that carries none of the identity attributes (see [match]'s
            // "unclaimed" fallback), so it needs [claimed] even when no match key names an attribute type.
            val needsAttrIndex = relevantTypeIds.isNotEmpty() || intents.any { it.adoptOnBankMatch }
            // Built when the batch carries any bank identity — a personal-counterparty match key, or an intent
            // (e.g. a source account) whose attributes include sort code + account number that may adopt an
            // existing account for the same real bank account.
            val needsPersonalKeys =
                intents.any { it.match is AccountMatchKey.ByPersonalCounterparty } ||
                    intents.any { intentBankKeys(it).isNotEmpty() }

            val byAttr = mutableMapOf<Pair<AttributeTypeId, String>, AccountId>()
            val claimed = mutableSetOf<AccountId>()
            val byPersonalKey = mutableMapOf<String, AccountId>()
            if (needsAttrIndex || needsPersonalKeys) {
                // One query over all account attributes rather than a per-account read: a per-account N+1 over the
                // whole account table used to dominate the "Resolving accounts" phase on a large database.
                val all = attributes()
                if (needsAttrIndex) {
                    // Ordered by (type, account_id), so getOrPut keeps the lowest-id account for a given (type, value).
                    for (attr in all) {
                        if (attr.attributeType.id in relevantTypeIds) {
                            byAttr.getOrPut(attr.attributeType.id to attr.value) { attr.accountId }
                        }
                        if (attr.attributeType.id in IDENTITY_ATTR_TYPE_IDS) {
                            claimed += attr.accountId
                        }
                    }
                }
                if (needsPersonalKeys) {
                    for ((accountId, attrs) in all.groupBy { it.accountId }) {
                        // Index EVERY identity: an account that owns two bank identities must be reachable by both, or
                        // the second one silently forks a duplicate account on the next import.
                        existingBankKeys(attrs).forEach { key -> byPersonalKey.getOrPut(key) { accountId } }
                    }
                }
            }
            return AccountIndex(accounts.toMutableMap(), byAttr, byPersonalKey, claimed)
        }

        /** Every bank key an existing account's stored [attrs] resolve to, best source first. */
        private fun existingBankKeys(attrs: List<AccountAttribute>): List<String> {
            val slots = attrs.map { AttributeSlot(it.attributeType.id.id, it.value, it.groupKey) }
            val grouped = bankKeysFrom(slots)
            if (grouped.isNotEmpty()) return grouped
            // Loose pairing: an account whose sort code and account number ended up in different groups (a
            // half-finished hand edit) would otherwise index nothing. Only when there is exactly one of each
            // across the whole account, so this can never invent a pair.
            bankKeysFrom(slots.map { it.copy(groupKey = "") }).singleOrNull()?.let { return listOf(it) }
            // Fallback: an account whose only bank identity is a synthetic "bank:<sort>:<account>" external-id
            // (created before its sort/account were persisted as attributes).
            val externalIdTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID)
            return attrs
                .firstOrNull { it.attributeType.id == externalIdTypeId }
                ?.value
                ?.let(::bankKeyFromExternalId)
                ?.let { listOf(it) }
                .orEmpty()
        }
    }
}
