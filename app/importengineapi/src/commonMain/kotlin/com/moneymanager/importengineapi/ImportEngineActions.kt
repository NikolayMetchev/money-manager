package com.moneymanager.importengineapi

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source

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
 * Creates [accounts] (always new, no matching), returning their ids in input order. [sourceFor] gives
 * each account's provenance. Mirrors `AccountWriteRepository.createAccountsBatch` but via the engine.
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
                match = AccountMatchKey.AlwaysCreate,
                name = account.name,
                openingDate = account.openingDate,
                categoryId = account.categoryId,
            )
        }
    val result = import(ImportBatch(accountsToCreate = intents))
    return intents.map { requireNotNull(result.createdAccountIds[it.key]) { "Account ${it.name} was not created" } }
}

/** Creates a single new account (always new, no matching) and returns its id. */
suspend fun ImportEngine.createAccount(
    account: Account,
    source: Source,
): AccountId = createAccounts(listOf(account)) { source }.single()
