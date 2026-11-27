package com.moneymanager.reconciliation

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.importengineapi.StringSimilarity
import kotlin.time.Instant

/** Short real names ("OF", "GBR") make poor partial matches. */
private const val MIN_PARTIAL_KEY_LENGTH = 4

/** Below this similarity a real account isn't worth suggesting for a wallet at all. */
private const val MIN_SUGGESTION_SIMILARITY = 0.6

/**
 * A reconciliation source's wallet with no link yet, and the real account it most plausibly mirrors
 * (for the user to confirm), if any.
 */
data class UnlinkedWallet(
    val wallet: ShadowAccount,
    val suggestion: Account?,
)

/** The real accounts a link may target: everything that isn't itself a shadow account. */
fun realAccounts(
    accounts: List<Account>,
    shadowAccounts: List<ShadowAccount>,
): List<Account> {
    val shadowIds = shadowAccounts.mapTo(mutableSetOf()) { it.accountId }
    return accounts.filter { it.id !in shadowIds }
}

/**
 * The links that can be made **without asking**: each unlinked wallet of [source] — unless the user
 * removed its links (see [ShadowAccount.autoLinkDeclined]) — whose name (after
 * the source's wallet prefix), or the last component of its colon-separated account path, equals
 * exactly one real account's name, ignoring case, spacing and punctuation. The full name takes priority,
 * and the real account must be free of other claims from this source. Anything
 * less certain is left for the user (see [unlinkedWallets]).
 *
 * @return shadow account id → the real account to link it to
 */
fun planAutoLinks(
    source: ReconciliationSource,
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
    realAccounts: List<Account>,
): Map<AccountId, AccountId> {
    val wallets = shadowAccounts.filter(source::isLinkable)
    val linkedWallets = links.mapTo(mutableSetOf()) { it.shadowAccountId }
    val walletIds = wallets.mapTo(mutableSetOf()) { it.accountId }
    val takenReal = links.filter { it.shadowAccountId in walletIds }.mapTo(mutableSetOf()) { it.realAccountId }
    val realByKey = realAccounts.groupBy { nameKey(it.name) }

    val candidates =
        wallets
            .filter { it.accountId !in linkedWallets && !it.autoLinkDeclined }
            .mapNotNull { wallet ->
                val match = walletNameKeys(source.walletName(wallet)).firstNotNullOfOrNull { realByKey[it] }?.singleOrNull()
                match?.let { wallet.accountId to it.id }
            }
    // Several wallets resolving to one real account is ambiguous: link none of them, the user chooses.
    val claims = candidates.groupingBy { it.second }.eachCount()
    return candidates
        .filter { (_, realId) -> realId !in takenReal && claims.getValue(realId) == 1 }
        .toMap()
}

/**
 * Wallets of [source] that still need the user: no link, with a best-guess real account to offer. An account another
 * wallet of the source already mirrors is offered only when nothing else is close: sharing one (Coinbase Pro's
 * wallet in the Coinbase account) is the exception.
 */
fun unlinkedWallets(
    source: ReconciliationSource,
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
    realAccounts: List<Account>,
): List<UnlinkedWallet> {
    val linkedWallets = links.mapTo(mutableSetOf()) { it.shadowAccountId }
    val walletIds = shadowAccounts.filter(source::isLinkable).mapTo(mutableSetOf()) { it.accountId }
    val takenReal = links.filter { it.shadowAccountId in walletIds }.mapTo(mutableSetOf()) { it.realAccountId }
    val offerable = realAccounts.filter { it.id !in takenReal }
    return shadowAccounts
        .filter { source.isLinkable(it) && it.accountId !in linkedWallets }
        .map { wallet ->
            val name = source.walletName(wallet)
            UnlinkedWallet(wallet, suggestRealAccount(name, offerable) ?: suggestRealAccount(name, realAccounts))
        }
}

/** Links accepted in bulk are limited to one suggestion per free account. */
fun planSuggestedLinks(
    unlinked: List<UnlinkedWallet>,
    takenReal: Set<AccountId>,
): Map<AccountId, AccountId> {
    val candidates =
        unlinked.mapNotNull { (wallet, suggestion) ->
            suggestion?.takeIf { !wallet.autoLinkDeclined && it.id !in takenReal }?.let { wallet.accountId to it.id }
        }
    val claims = candidates.groupingBy { it.second }.eachCount()
    return candidates.filter { claims.getValue(it.second) == 1 }.toMap()
}

/**
 * The real account [walletName] most plausibly mirrors (see the preference order inside), or null when
 * nothing is close (similarity below [MIN_SUGGESTION_SIMILARITY]).
 */
fun suggestRealAccount(
    walletName: String,
    realAccounts: List<Account>,
): Account? {
    val keys = walletNameKeys(walletName)
    if (keys.isEmpty()) return null
    val keyed = realAccounts.map { it to nameKey(it.name) }
    keys.firstNotNullOfOrNull { key -> keyed.firstOrNull { it.second == key }?.first }?.let { return it }
    val partial = keyed.filter { it.second.length >= MIN_PARTIAL_KEY_LENGTH }
    // Compare partial names against the leaf so parent categories never suggest an account.
    val leaf = walletLeafName(walletName)
    val key = keys.last()
    val containingLeaf = partial.filter { key.length >= MIN_PARTIAL_KEY_LENGTH && it.second.contains(key) }
    val partialMatch =
        partial.filter { key.startsWith(it.second) }.maxByOrNull { it.second.length }
            ?: partial.filter { it.second.startsWith(key) }.minByOrNull { it.second.length }
            ?: partial.filter { key.contains(it.second) }.maxByOrNull { it.second.length }
            ?: containingLeaf.minByOrNull { it.second.length }
    if (partialMatch != null) return partialMatch.first
    return realAccounts
        .map { it to StringSimilarity.similarity(leaf, it.name) }
        .filter { it.second >= MIN_SUGGESTION_SIMILARITY }
        .maxByOrNull { it.second }
        ?.first
}

private fun walletLeafName(name: String): String = name.substringAfterLast(':').trim().ifEmpty { name }

private fun walletNameKeys(name: String): List<String> =
    listOf(nameKey(name), nameKey(walletLeafName(name))).filter(String::isNotEmpty).distinct()

/** Case/spacing/punctuation-insensitive name identity: "Crypto.com App" and "crypto com app" agree. */
internal fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

/** Applies an auto-link [plan] (see [planAutoLinks]) in one engine batch; no-op when it is empty. */
suspend fun ImportEngine.applyAutoLinks(plan: Map<AccountId, AccountId>) {
    if (plan.isEmpty()) return
    import(
        ImportBatch(
            reconciliationLinkMutations = plan.map { (shadow, real) -> ReconciliationLinkMutation.SetLinks(shadow, setOf(real)) },
        ),
    )
}

/**
 * The real accounts that can be created for [wallets] of [source] without asking, each named after its
 * wallet (see [ReconciliationSource.walletName]). A wallet is left out when its name is blank, when an
 * existing account already carries its full name or final path component (ignoring case, spacing and punctuation — account names are
 * unique, and the user should link to that account instead), or when another of [wallets] would create
 * the same name.
 *
 * @return shadow account id → the name of the real account to create and link it to
 */
fun planAccountCreation(
    source: ReconciliationSource,
    wallets: List<ShadowAccount>,
    accounts: List<Account>,
): Map<AccountId, String> {
    val takenKeys = accounts.mapTo(mutableSetOf()) { nameKey(it.name) }
    val candidates =
        wallets
            .map { it.accountId to source.walletName(it) }
            .filter { (_, name) -> nameKey(name).isNotEmpty() && walletNameKeys(name).none { it in takenKeys } }
    val claims = candidates.groupingBy { nameKey(it.second) }.eachCount()
    return candidates.filter { (_, name) -> claims.getValue(nameKey(name)) == 1 }.toMap()
}

/**
 * Creates the real accounts a [plan] (see [planAccountCreation]) names, opened at [openingDate], and links
 * each wallet to its new account. No-op when the plan is empty.
 */
suspend fun ImportEngine.createAndLinkAccounts(
    plan: Map<AccountId, String>,
    openingDate: Instant,
) {
    if (plan.isEmpty()) return
    val intents =
        plan.entries.associate { (walletId, name) ->
            walletId to
                ImportAccountIntent(
                    key = LocalAccountKey("reconciliation-${walletId.id}"),
                    source = Source.Manual,
                    name = name,
                    openingDate = openingDate,
                )
        }
    val created = import(ImportBatch.manualEdits(accounts = intents.values.toList())).createdAccountIds
    applyAutoLinks(intents.mapValues { (_, intent) -> created.getValue(intent.key) })
}
