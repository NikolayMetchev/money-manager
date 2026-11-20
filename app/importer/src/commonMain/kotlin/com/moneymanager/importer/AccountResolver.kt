package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.bankKeysFrom
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

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
        accountAttributeRepository.shadowSources().filterValues { it == source }.keys

    class AccountResolution(
        val keyToId: Map<LocalAccountKey, AccountId>,
        val created: Int,
    )

    suspend fun resolveAccounts(batch: ImportBatch): AccountResolution {
        if (batch.accountsToCreate.isEmpty()) return AccountResolution(emptyMap(), 0)

        // A shadow-only batch must never match (and so reuse) a real account by name: only the source's
        // own shadow accounts are candidates, and a name clash with a real account creates a suffixed one.
        val shadowScope = batch.shadowSource?.let { shadowAccountIds(it) }
        val visible =
            accountRepository
                .getAllAccounts()
                .first()
                .filter { shadowScope == null || it.id in shadowScope }
                .associate { it.name to it.id }
        val index = AccountIndex.build(batch.accountsToCreate, visible) { accountAttributeRepository.getAll().first() }

        val keyToId = mutableMapOf<LocalAccountKey, AccountId>()
        var created = 0
        val shadowTag =
            batch.shadowSource?.let { NewAttribute(RECONCILIATION_SOURCE_TYPE, it) }
        // A fee account names the account it belongs to, which may be another intent of this batch: resolve it last.
        val (feeIntents, otherIntents) = batch.accountsToCreate.partition { it.match is AccountMatchKey.FeeAccountOf }
        for (rawIntent in otherIntents) {
            val intent =
                if (shadowTag == null || rawIntent.attributes.any { it.typeId == shadowTag.typeId }) {
                    rawIntent
                } else {
                    rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                }
            val match = index.match(intent)
            if (match != null) {
                // A bank-identity (adopted) match re-points the existing account onto this intent only for
                // own/source accounts; counterparties merge in silently keeping the existing account as-is.
                if (match.adopted && intent.adoptOnBankMatch) {
                    adoptAccount(intent, match.id, index)
                    // Now carries this intent's identity attributes: a later same-batch intent that would
                    // otherwise adopt it by the "unclaimed name" fallback must see it as claimed.
                    index.claim(match.id)
                } else if (intent.adoptOnBankMatch) {
                    shareAdoptedAttributes(intent, match.id)
                }
                keyToId[intent.key] = match.id
                continue
            }
            val resolvedName = uniqueName(requireNotNull(intent.name), intent, index.byName)
            val newId = createAccount(intent, resolvedName)
            created++
            keyToId[intent.key] = newId
            index.addCreated(intent, newId, resolvedName)
        }
        if (feeIntents.isNotEmpty()) {
            val nameById =
                index.byName.entries
                    .associate { (name, id) -> id to name }
                    .toMutableMap()
            for (rawIntent in feeIntents) {
                val intent = if (shadowTag == null) rawIntent else rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                val resolved = resolveFeeAccount(intent, keyToId, index, nameById)
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
        index: AccountIndex,
        nameById: MutableMap<AccountId, String>,
    ): ResolvedFeeAccount {
        val owner =
            when (val ref = (intent.match as AccountMatchKey.FeeAccountOf).account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> requireNotNull(keyToId[ref.key]) { "Fee account of an unresolved account ${ref.key}" }
            }
        val tag = NewAttribute(FEE_ACCOUNT_FOR_TYPE, owner.id.toString())
        index.byAttr[tag.typeId to tag.value]?.let { return ResolvedFeeAccount(it, created = false) }
        val desired = "${requireNotNull(nameById[owner]) { "Fee account of unknown account $owner" }} Fees"
        // A shadow account's fee account belongs to the same reconciliation source, whichever batch creates it.
        val shadowTag =
            if (intent.attributes.any { it.typeId == RECONCILIATION_SOURCE_TYPE }) {
                null
            } else {
                accountAttributeRepository
                    .getByAccount(
                        owner,
                    ).first()
                    .firstOrNull { it.attributeType.id == RECONCILIATION_SOURCE_TYPE }
                    ?.let {
                        NewAttribute(RECONCILIATION_SOURCE_TYPE, it.value)
                    }
            }
        val named = intent.copy(name = desired, attributes = intent.attributes + tag + listOfNotNull(shadowTag))
        val resolvedName = uniqueName(desired, named, index.byName)
        val id = createAccount(named, resolvedName)
        index.byName[resolvedName] = id
        nameById[id] = resolvedName
        index.byAttr[tag.typeId to tag.value] = id
        return ResolvedFeeAccount(id, created = true)
    }

    /**
     * A display name no other account holds, so the `account.name` UNIQUE constraint can never reject a
     * create/rename. Identity still decides WHICH account an intent matches (see [AccountIndex.match]) — this
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

    /**
     * Re-purposes a bank-key-matched account as this intent's account: renames it to [intent]'s name and
     * writes any of the intent's attributes it doesn't already have (e.g. this provider's external id).
     */
    private suspend fun adoptAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        index: AccountIndex,
    ) {
        val existing = accountRepository.getAccountById(id).first() ?: return
        val intentName = requireNotNull(intent.name)
        if (existing.name != intentName) {
            val resolvedName = uniqueName(intentName, intent, index.byName, selfId = id)
            accountRepository.updateAccount(existing.copy(name = resolvedName), intent.source)
            index.byName.remove(existing.name)
            index.byName[resolvedName] = id
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
                index.byAttr.getOrPut(attr.typeId to attr.value) { id }
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
        return { attr ->
            if (attr.groupKey.isNotEmpty()) {
                tagToStored.getValue(attr.groupKey)
            } else {
                val occupied =
                    existingAttrs.any {
                        it.groupKey.isEmpty() && it.attributeType.id == attr.typeId && it.value != attr.value
                    }
                if (occupied && isIdentityAttributeType(attr.typeId)) Uuid.random().toString() else ""
            }
        }
    }

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
}
