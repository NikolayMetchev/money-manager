package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import kotlin.time.Instant

/*
 * Convenience ImportEngine extensions for single, non-transfer writes. Each builds a one-item
 * ImportBatch and calls ImportEngine.import, so callers mutate the database without holding a
 * write repository — the engine remains the sole writer. Use these instead of injecting the
 * corresponding *WriteRepository.
 */

/** Upserts a currency by [code] and returns its id (the engine's `upsertCurrencyByCode`). */
suspend fun ImportEngine.createCurrency(
    code: String,
    name: String,
    source: Source = Source.Manual,
): CurrencyId {
    val key = LocalCurrencyKey(code)
    val result =
        import(
            ImportBatch(
                currencies = listOf(ImportCurrencyIntent(key = key, source = source, code = code, name = name)),
            ),
        )
    return requireNotNull(result.createdCurrencyIds[key]) { "Currency $code was not created" }
}

/** Upserts a crypto asset by ticker [code] and returns its id (fixed 18-decimal scale; name from the registry). */
suspend fun ImportEngine.createCrypto(
    code: String,
    name: String? = null,
    source: Source = Source.Manual,
): CryptoId {
    val key = LocalCryptoKey(code)
    val result =
        import(
            ImportBatch(
                cryptoAssets =
                    listOf(ImportCryptoIntent(key = key, source = source, code = code, name = name)),
            ),
        )
    return requireNotNull(result.createdCryptoIds[key]) { "Crypto asset $code was not created" }
}

/** Deletes a crypto asset by id. */
suspend fun ImportEngine.deleteCrypto(
    id: CryptoId,
    source: Source = Source.Manual,
) {
    import(
        ImportBatch(
            cryptoAssets =
                listOf(
                    ImportCryptoIntent(
                        key = LocalCryptoKey(id.toString()),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    ),
                ),
        ),
    )
}

/** Creates a single cross-asset trade and returns its id. */
@Suppress("LongParameterList")
suspend fun ImportEngine.createTrade(
    timestamp: Instant,
    description: String,
    fromAccountId: AccountId,
    fromAmount: Money,
    toAccountId: AccountId,
    toAmount: Money,
    source: Source = Source.Manual,
): TradeId {
    val key = LocalTradeKey("$fromAccountId->$toAccountId@$timestamp")
    val result =
        import(
            ImportBatch(
                trades =
                    listOf(
                        ImportTradeIntent(
                            key = key,
                            source = source,
                            timestamp = timestamp,
                            description = description,
                            fromAccountId = fromAccountId,
                            fromAmount = fromAmount,
                            toAccountId = toAccountId,
                            toAmount = toAmount,
                        ),
                    ),
            ),
        )
    return requireNotNull(result.createdTradeIds[key]) { "Trade was not created" }
}

/** Deletes a currency by id. */
suspend fun ImportEngine.deleteCurrency(
    id: CurrencyId,
    source: Source = Source.Manual,
) {
    import(
        ImportBatch(
            currencies =
                listOf(
                    ImportCurrencyIntent(
                        key = LocalCurrencyKey(id.toString()),
                        source = source,
                        operation = ImportOperation.DELETE,
                        existingId = id,
                    ),
                ),
        ),
    )
}

/** Resolves (get-or-create) a single attribute-type id by name. */
suspend fun ImportEngine.getOrCreateAttributeType(name: String): AttributeTypeId =
    requireNotNull(import(ImportBatch(attributeTypeNames = listOf(name))).attributeTypeIds[name]) {
        "Attribute type $name was not resolved"
    }

/** Resolves (get-or-create) several attribute-type ids by name in one batch. */
suspend fun ImportEngine.getOrCreateAttributeTypes(names: List<String>): Map<String, AttributeTypeId> =
    if (names.isEmpty()) emptyMap() else import(ImportBatch(attributeTypeNames = names)).attributeTypeIds

/**
 * Gets or creates [accounts] by name, returning their ids in input order. [sourceFor] gives each
 * account's provenance. Matching by name (rather than creating unconditionally) makes the call
 * idempotent: an importer that believes an account is new because its own name lookup missed — a
 * case difference, a stale snapshot, the same name twice in one list — reuses the existing account
 * instead of adding a second one with the same name.
 */
suspend fun ImportEngine.createAccounts(
    accounts: List<Account>,
    sourceFor: (Account) -> Source,
): List<AccountId> {
    if (accounts.isEmpty()) return emptyList()
    val intents =
        accounts.mapIndexed { index, account ->
            ImportAccountIntent(
                key = LocalAccountKey("create-$index"),
                source = sourceFor(account),
                match = AccountMatchKey.ByName(account.name),
                name = account.name,
                openingDate = account.openingDate,
                categoryId = account.categoryId,
            )
        }
    val result = import(ImportBatch(accountsToCreate = intents))
    return intents.map { requireNotNull(result.createdAccountIds[it.key]) { "Account ${it.name} was not created" } }
}

/** Gets or creates a single account by name (see [createAccounts]) and returns its id. */
suspend fun ImportEngine.createAccount(
    account: Account,
    source: Source,
): AccountId = createAccounts(listOf(account)) { source }.single()
