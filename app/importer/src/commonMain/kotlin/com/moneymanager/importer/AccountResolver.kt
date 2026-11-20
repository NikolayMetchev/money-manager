package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.bankKeyFromExternalId
import com.moneymanager.importengineapi.bankKeysFrom
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Attribute types that tie an account to a specific real bank account/counterparty identity. An
 * existing account carrying none of these is "unclaimed" — see [AccountResolver.matchAccount].
 */
private val IDENTITY_ATTR_TYPE_IDS =
    setOf(
        AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID),
    )

/**
 * Resolves a batch's account intents to account ids: matches existing accounts by name, bank identity
 * or personal key (adopting a bank identity's account where the intent asks for it), and creates the rest.
 */
internal class AccountResolver(
    private val accountRepository: AccountWriteRepository,
    private val accountAttributeRepository: AccountAttributeWriteRepository,
) {
    /** Ids of the shadow accounts of reconciliation source [source]. */
    suspend fun shadowAccountIds(source: String): Set<AccountId> =
        accountAttributeRepository
            .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID))
            .first()
            .filter { it.value == source }
            .mapTo(mutableSetOf()) { it.accountId }

    class AccountResolution(
        val keyToId: Map<LocalAccountKey, AccountId>,
        val created: Int,
    )

    suspend fun resolveAccounts(batch: ImportBatch): AccountResolution {
        if (batch.accountsToCreate.isEmpty()) return AccountResolution(emptyMap(), 0)

        // A shadow-only batch must never match (and so reuse) a real account by name: only the source's
        // own shadow accounts are candidates, and a name clash with a real account creates a suffixed one.
        val shadowScope = batch.shadowSource?.let { shadowAccountIds(it) }
        val byName =
            accountRepository
                .getAllAccounts()
                .first()
                .filter { shadowScope == null || it.id in shadowScope }
                .associate { it.name to it.id }
                .toMutableMap()
        val existingAccountAttrIndex = buildExistingAccountAttrIndex(batch.accountsToCreate)
        val byAttr = existingAccountAttrIndex.byAttr
        val claimedAccountIds = existingAccountAttrIndex.claimedAccountIds
        val byPersonalKey = buildExistingPersonalKeyIndex(batch.accountsToCreate)
        // Names of accounts created earlier in THIS batch, so a later same-named intent with no other
        // way to match reuses that account instead of forking a duplicate. Kept separate from [byName]
        // (which also holds pre-existing accounts) because the name fallback must not reach across to a
        // pre-existing account that merely shares a name — see [matchAccount].
        val batchCreatedByName = mutableMapOf<String, AccountId>()

        val keyToId = mutableMapOf<LocalAccountKey, AccountId>()
        var created = 0
        val shadowTag =
            batch.shadowSource?.let { NewAttribute(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID), it) }
        // A fee account names the account it belongs to, which may be another intent of this batch: resolve it last.
        val (feeIntents, otherIntents) = batch.accountsToCreate.partition { it.match is AccountMatchKey.FeeAccountOf }
        for (rawIntent in otherIntents) {
            val intent =
                if (shadowTag == null || rawIntent.attributes.any { it.typeId == shadowTag.typeId }) {
                    rawIntent
                } else {
                    rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                }
            val match = matchAccount(intent, byName, byAttr, byPersonalKey, batchCreatedByName, claimedAccountIds)
            if (match != null) {
                // A bank-identity (adopted) match re-points the existing account onto this intent only for
                // own/source accounts; counterparties merge in silently keeping the existing account as-is.
                if (match.adopted && intent.adoptOnBankMatch) {
                    adoptAccount(intent, match.id, byName, byAttr)
                    // Now carries this intent's identity attributes: a later same-batch intent that would
                    // otherwise adopt it by the "unclaimed name" fallback must see it as claimed.
                    claimedAccountIds += match.id
                } else if (intent.adoptOnBankMatch) {
                    shareAdoptedAttributes(intent, match.id)
                }
                keyToId[intent.key] = match.id
                continue
            }
            val requestedName = requireNotNull(intent.name)
            val resolvedName = uniqueName(requestedName, intent, byName)
            val newId = createAccount(intent, resolvedName)
            created++
            keyToId[intent.key] = newId
            indexNewAccount(intent, newId, resolvedName, byName, byAttr, byPersonalKey)
            // Keyed by the REQUESTED name (matchAccount's lookup key), not resolvedName: a later
            // same-batch intent asking for the same name (e.g. a second external id for the same
            // merchant) must find this account even when resolvedName was suffixed to dodge a
            // pre-existing account's UNIQUE collision.
            batchCreatedByName.getOrPut(requestedName) { newId }
        }
        if (feeIntents.isNotEmpty()) {
            val nameById = byName.entries.associate { (name, id) -> id to name }.toMutableMap()
            for (rawIntent in feeIntents) {
                val intent = if (shadowTag == null) rawIntent else rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                val resolved = resolveFeeAccount(intent, keyToId, byName, byAttr, nameById)
                if (resolved.created) created++
                keyToId[intent.key] = resolved.id
            }
        }
        return AccountResolution(keyToId, created)
    }

    private class ResolvedFeeAccount(
        val id: AccountId,
        val created: Boolean,
    )

    /**
     * The fee account of an [AccountMatchKey.FeeAccountOf] intent's account: the one tagged as its fee account, else a new one
     * named "<account> Fees". Only the tag finds it: an untagged account of that name is not a fee account, and the new one
     * takes a suffixed name beside it. A shadow account's new fee account is a shadow account of the same source. [nameById]
     * holds every account the batch can see, those it created included.
     */
    private suspend fun resolveFeeAccount(
        intent: ImportAccountIntent,
        keyToId: Map<LocalAccountKey, AccountId>,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        nameById: MutableMap<AccountId, String>,
    ): ResolvedFeeAccount {
        val owner =
            when (val ref = (intent.match as AccountMatchKey.FeeAccountOf).account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> requireNotNull(keyToId[ref.key]) { "Fee account of an unresolved account ${ref.key}" }
            }
        val tag = NewAttribute(FEE_ACCOUNT_FOR_TYPE, owner.id.toString())
        byAttr[tag.typeId to tag.value]?.let { return ResolvedFeeAccount(it, created = false) }
        val desired = "${requireNotNull(nameById[owner]) { "Fee account of unknown account $owner" }} Fees"
        // A shadow account's fee account belongs to the same reconciliation source, whichever batch creates it.
        val shadowType = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)
        val shadowTag =
            if (intent.attributes.any { it.typeId == shadowType }) {
                null
            } else {
                accountAttributeRepository.getByAccount(owner).first().firstOrNull { it.attributeType.id == shadowType }?.let {
                    NewAttribute(shadowType, it.value)
                }
            }
        val named = intent.copy(name = desired, attributes = intent.attributes + tag + listOfNotNull(shadowTag))
        val resolvedName = uniqueName(desired, named, byName)
        val id = createAccount(named, resolvedName)
        byName[resolvedName] = id
        nameById[id] = resolvedName
        byAttr[tag.typeId to tag.value] = id
        return ResolvedFeeAccount(id, created = true)
    }

    /**
     * A display name no other account holds, so the `account.name` UNIQUE constraint can never reject a
     * create/rename. Identity still decides WHICH account an intent matches (see [matchAccount]) — this
     * only disambiguates the display name, so genuinely distinct accounts that share a real-world name
     * (e.g. five different people all named "Nikolay Metchev", two banks both trading as "Payward Ltd.")
     * stay separate and readable rather than colliding. Shape matches the sibling-collision convention
     * already used for Monzo-style accounts: "Name (1234)" (see `ApiSessionImportService.displayName`).
     *
     * [selfId] excludes the intent's own current account from the collision check (for [adoptAccount]
     * renaming an account to a name it may already hold, or re-resolving to a name it already suffixed on
     * an earlier import) so re-imports don't grow an extra suffix each time.
     */
    private fun uniqueName(
        desired: String,
        intent: ImportAccountIntent,
        byName: Map<String, AccountId>,
        selfId: AccountId? = null,
    ): String {
        fun free(candidate: String) = byName[candidate].let { it == null || it == selfId }
        if (free(desired)) return desired
        discriminatorFor(intent)?.let { discriminator ->
            val candidate = "$desired ($discriminator)"
            if (free(candidate)) return candidate
        }
        // Last resort: no identity attribute distinguishes this intent, or even the discriminated name is
        // taken. An incrementing counter always terminates, so the UNIQUE insert can never fail — but it is
        // the only branch whose outcome depends on import order (which duplicate got the counter suffix);
        // every duplicate cluster seen in practice (bank accounts, wallets, exchange external ids) resolves
        // via the identity branch above instead.
        return generateSequence(2) { it + 1 }.map { "$desired ($it)" }.first(::free)
    }

    /**
     * An identity-derived discriminator for [intent], preferring a bank account number (last 4 digits,
     * matching how banks themselves distinguish same-named accounts) and falling back to the intent's own
     * match value (covers a wallet address or an exchange/provider external id — the last 6 characters is
     * enough to disambiguate without making the name unreadably long).
     */
    private fun discriminatorFor(intent: ImportAccountIntent): String? {
        // Take the account number from the first group that yields a COMPLETE bank key, so an account with
        // several identities gets a stable discriminator instead of one that depends on attribute order.
        val accountNumberDiscriminator =
            intentBankKeys(intent)
                .firstOrNull()
                ?.substringAfter('|')
                ?.takeLast(4)
        if (accountNumberDiscriminator != null) return accountNumberDiscriminator
        return (intent.match as? AccountMatchKey.ByExternalId)?.value?.takeLast(6)
    }

    /** A matched existing account; [adopted] is true for a bank-key fallback match that must be re-pointed. */
    private class AccountMatch(
        val id: AccountId,
        val adopted: Boolean,
    )

    private fun matchAccount(
        intent: ImportAccountIntent,
        byName: Map<String, AccountId>,
        byAttr: Map<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: Map<String, AccountId>,
        batchCreatedByName: Map<String, AccountId>,
        claimedAccountIds: Set<AccountId>,
    ): AccountMatch? {
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
                ?.takeIf { it !in claimedAccountIds }
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

    /**
     * Re-purposes a bank-key-matched account as this intent's account: renames it to [intent]'s name and
     * writes any of the intent's attributes it doesn't already have (e.g. this provider's external id).
     */
    private suspend fun adoptAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
    ) {
        val existing = accountRepository.getAccountById(id).first() ?: return
        val intentName = requireNotNull(intent.name)
        if (existing.name != intentName) {
            val resolvedName = uniqueName(intentName, intent, byName, selfId = id)
            accountRepository.updateAccount(existing.copy(name = resolvedName), intent.source)
            byName.remove(existing.name)
            byName[resolvedName] = id
        }
        val currentAttrs = accountAttributeRepository.getByAccount(id).first()
        val storedGroupKey = storedGroupKeyResolver(intent.attributes, currentAttrs)
        // What an API session adopts is the session's (account_attribute_api_owner), so its unimport or re-import takes it
        // back. An attribute already there is claimed only when another session adopted it too: one typed by hand, or
        // written by the import that created the account, stays nobody's.
        val session = (intent.source as? Source.Api)?.sessionId
        for (attr in intent.attributes) {
            val present = currentAttrs.firstOrNull { it.attributeType.id == attr.typeId && it.value == attr.value }
            if (present == null) {
                // Upsert rather than insert-and-swallow: the (type, group) slot may already hold a
                // different value, and dropping the write there would silently lose this provider's
                // identity for the account.
                val groupKey = storedGroupKey(attr)
                accountAttributeRepository.upsertInCreationMode(id, attr.typeId, attr.value, groupKey)
                if (session != null) accountAttributeRepository.claimForApiSession(id, attr.typeId, groupKey, session)
                byAttr.getOrPut(attr.typeId to attr.value) { id }
            } else if (session != null && accountAttributeRepository.isClaimed(present.id)) {
                accountAttributeRepository.claimForApiSession(id, attr.typeId, present.groupKey, session)
            }
        }
    }

    /**
     * Claims, for [intent]'s API session, the attributes of account [id] it names that another session adopted: a fresh
     * import without that session would have adopted them for this one, so its unimport must leave them.
     */
    private suspend fun shareAdoptedAttributes(
        intent: ImportAccountIntent,
        id: AccountId,
    ) {
        val session = (intent.source as? Source.Api)?.sessionId ?: return
        if (intent.attributes.isEmpty()) return
        val currentAttrs = accountAttributeRepository.getByAccount(id).first()
        for (attr in intent.attributes) {
            val present = currentAttrs.firstOrNull { it.attributeType.id == attr.typeId && it.value == attr.value } ?: continue
            if (accountAttributeRepository.isClaimed(present.id)) {
                accountAttributeRepository.claimForApiSession(id, attr.typeId, present.groupKey, session)
            }
        }
    }

    /**
     * Builds the function that maps each of an intent's attributes to the `group_key` it is stored under.
     *
     * Stored group keys are opaque UUIDs. The intent carries a transient grouping tag (the natural
     * `personalCounterpartyKey`) that only binds an identity's attributes together within the batch; this
     * translates that tag to a persisted UUID, minting one for a new group and reusing an existing group's
     * UUID when the incoming identity matches an existing one BY DERIVED VALUE. Matching by value (not by
     * key string) is what keeps a re-import idempotent and lets two independently-created accounts be
     * merged without duplicating a shared identity.
     *
     * Ungrouped identity attributes (e.g. a provider external id) are resolved per attribute: one whose
     * ungrouped slot already holds a DIFFERENT value is moved into a group of its own rather than
     * overwriting, so two providers that each know the account by a different external id both survive.
     */
    private fun storedGroupKeyResolver(
        intentAttributes: List<NewAttribute>,
        existingAttrs: List<AccountAttribute>,
    ): (NewAttribute) -> String {
        val existingGroups = existingAttrs.filter { it.groupKey.isNotEmpty() }.groupBy { it.groupKey }
        val tagToStored =
            intentAttributes
                .filter { it.groupKey.isNotEmpty() }
                .groupBy { it.groupKey }
                .keys
                .associateWith { tag ->
                    val incomingKey =
                        bankKeysFrom(
                            intentAttributes
                                .filter { it.groupKey == tag }
                                .map { AttributeSlot(it.typeId.id, it.value, it.groupKey) },
                        ).firstOrNull()
                    val reuse =
                        incomingKey?.let {
                            existingGroups.entries
                                .firstOrNull { (key, rows) ->
                                    bankKeysFrom(rows.map { AttributeSlot(it.attributeType.id.id, it.value, key) })
                                        .firstOrNull() == incomingKey
                                }?.key
                        }
                    reuse ?: Uuid.random().toString()
                }
        val identityTypeIds = IDENTITY_ATTR_TYPE_IDS.map { it.id }
        return { attr ->
            if (attr.groupKey.isNotEmpty()) {
                tagToStored.getValue(attr.groupKey)
            } else {
                val occupied =
                    existingAttrs.any {
                        it.groupKey.isEmpty() && it.attributeType.id == attr.typeId && it.value != attr.value
                    }
                if (occupied && attr.typeId.id in identityTypeIds) Uuid.random().toString() else ""
            }
        }
    }

    /**
     * Every personal-counterparty bank key this intent carries — one per attribute group holding both a
     * sort code and an account number, so an intent describing an account with two bank identities yields
     * both. Derived from the attribute values, never from the group key, so a stale group key is harmless.
     */
    private fun intentBankKeys(intent: ImportAccountIntent): List<String> =
        bankKeysFrom(intent.attributes.map { AttributeSlot(it.typeId.id, it.value, it.groupKey) })

    private suspend fun createAccount(
        intent: ImportAccountIntent,
        name: String,
    ): AccountId {
        val newId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = name,
                    openingDate = requireNotNull(intent.openingDate),
                    categoryId = intent.categoryId,
                ),
                intent.source,
            )
        val storedGroupKey = storedGroupKeyResolver(intent.attributes, existingAttrs = emptyList())
        intent.attributes.forEach { attr ->
            accountAttributeRepository.insertInCreationMode(newId, attr.typeId, attr.value, storedGroupKey(attr))
        }
        return newId
    }

    private fun indexNewAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        name: String,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: MutableMap<String, AccountId>,
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
    }

    /**
     * Builds an index of existing accounts by (attributeTypeId, value), but only for the attribute
     * types actually referenced by attribute-based match keys in this batch — so a name-only batch
     * (CSV/QIF) does not pay to scan every account's attributes.
     */
    private suspend fun buildExistingAccountAttrIndex(intents: List<ImportAccountIntent>): ExistingAccountAttrIndex {
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
        // pre-existing SAME-NAMED account that carries none of these identity attributes (see
        // [matchAccount]'s "unclaimed" fallback) — so the per-account attribute scan below must also run
        // whenever such an intent is present, even if this batch's own relevantTypeIds is otherwise empty.
        if (relevantTypeIds.isEmpty() && intents.none { it.adoptOnBankMatch }) {
            return ExistingAccountAttrIndex(mutableMapOf(), mutableSetOf())
        }

        val index = mutableMapOf<Pair<AttributeTypeId, String>, AccountId>()
        val claimed = mutableSetOf<AccountId>()
        // One query over all account attributes rather than a per-account read: this used to be an N+1
        // over the whole account table, which dominated the "Resolving accounts" phase on a large database.
        // getAll() is ordered by (type, account_id), so getOrPut still keeps the lowest-id account for a
        // given (type, value) — the same winner the per-account scan produced.
        for (attr in accountAttributeRepository.getAll().first()) {
            if (attr.attributeType.id in relevantTypeIds) {
                index.getOrPut(attr.attributeType.id to attr.value) { attr.accountId }
            }
            if (attr.attributeType.id in IDENTITY_ATTR_TYPE_IDS) {
                claimed += attr.accountId
            }
        }
        return ExistingAccountAttrIndex(index, claimed)
    }

    /**
     * [byAttr] indexes existing accounts by (relevant attribute type, value) for primary-key matching.
     * [claimedAccountIds] is every existing account carrying at least one identity attribute
     * ([IDENTITY_ATTR_TYPE_IDS]) — an account NOT in this set is "unclaimed": it carries no signal tying
     * it to a specific real bank account/counterparty, so an own/source intent may freely adopt it by
     * name (see [matchAccount]).
     */
    private class ExistingAccountAttrIndex(
        val byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        val claimedAccountIds: MutableSet<AccountId>,
    )

    /**
     * Builds an index of existing accounts by their sort-code/account-number composite key, so an
     * incoming [AccountMatchKey.ByPersonalCounterparty] reconciles onto an account a *previous* import
     * already created for the same real bank account (cross-provider, order-independent). Only built when
     * the batch actually carries personal-counterparty match keys, so name-only batches pay nothing.
     */
    private suspend fun buildExistingPersonalKeyIndex(intents: List<ImportAccountIntent>): MutableMap<String, AccountId> {
        // Built when the batch carries any bank identity — a personal-counterparty match key, or an intent
        // (e.g. a source account) whose attributes include sort code + account number that may adopt an
        // existing account for the same real bank account.
        val hasPersonalKeys =
            intents.any { it.match is AccountMatchKey.ByPersonalCounterparty } ||
                intents.any { intentBankKeys(it).isNotEmpty() }
        if (!hasPersonalKeys) return mutableMapOf()

        val index = mutableMapOf<String, AccountId>()
        // One query for every account's attributes rather than a per-account flow read: this used to be an
        // N+1 over the whole account table on every import carrying a bank identity.
        for ((accountId, attrs) in accountAttributeRepository.getAll().first().groupBy { it.accountId }) {
            // Index EVERY identity: an account that owns two bank identities must be reachable by both, or
            // the second one silently forks a duplicate account on the next import.
            existingBankKeys(attrs).forEach { key -> index.getOrPut(key) { accountId } }
        }
        return index
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
