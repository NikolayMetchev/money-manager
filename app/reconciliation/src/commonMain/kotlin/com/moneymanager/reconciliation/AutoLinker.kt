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

/** Shorter real names ("OF", "GBR") prefix far too many wallet names to be meaningful suggestions. */
private const val MIN_PREFIX_KEY_LENGTH = 4

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
 * the source's wallet prefix) equals exactly one real account's name, ignoring case, spacing and
 * punctuation — and that real account isn't already linked to another wallet of the source. Anything
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
            .mapNotNull { wallet -> realByKey[nameKey(source.walletName(wallet))]?.singleOrNull()?.let { wallet.accountId to it.id } }
    // Several wallets resolving to one real account is ambiguous: link none of them, the user chooses.
    val claims = candidates.groupingBy { it.second }.eachCount()
    return candidates
        .filter { (_, realId) -> realId !in takenReal && claims.getValue(realId) == 1 }
        .toMap()
}

/** Wallets of [source] that still need the user: no link, with a best-guess real account to offer. */
fun unlinkedWallets(
    source: ReconciliationSource,
    shadowAccounts: List<ShadowAccount>,
    links: List<ReconciliationLink>,
    realAccounts: List<Account>,
): List<UnlinkedWallet> {
    val linkedWallets = links.mapTo(mutableSetOf()) { it.shadowAccountId }
    return shadowAccounts
        .filter { source.isLinkable(it) && it.accountId !in linkedWallets }
        .map { wallet -> UnlinkedWallet(wallet, suggestRealAccount(source.walletName(wallet), realAccounts)) }
}

/**
 * The real account [walletName] most plausibly mirrors (see the preference order inside), or null when
 * nothing is close (similarity below [MIN_SUGGESTION_SIMILARITY]).
 */
fun suggestRealAccount(
    walletName: String,
    realAccounts: List<Account>,
): Account? {
    val walletKey = nameKey(walletName)
    if (walletKey.isEmpty()) return null
    val keyed = realAccounts.map { it to nameKey(it.name) }.filter { it.second.length >= MIN_PREFIX_KEY_LENGTH }
    // Exact, then the longest real name the wallet name starts with ("Crypto.com App" → "Crypto.com"),
    // then the shortest real name starting with the wallet name ("Binance" → "Binance Earn" rather than
    // "Binance Earn Rewards"), then plain similarity.
    keyed.firstOrNull { it.second == walletKey }?.let { return it.first }
    keyed.filter { walletKey.startsWith(it.second) }.maxByOrNull { it.second.length }?.let { return it.first }
    keyed.filter { it.second.startsWith(walletKey) }.minByOrNull { it.second.length }?.let { return it.first }
    return realAccounts
        .map { it to StringSimilarity.similarity(walletName, it.name) }
        .filter { it.second >= MIN_SUGGESTION_SIMILARITY }
        .maxByOrNull { it.second }
        ?.first
}

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
 * existing account already carries that name (ignoring case, spacing and punctuation — account names are
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
            .filter { (_, name) -> nameKey(name).isNotEmpty() && nameKey(name) !in takenKeys }
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
