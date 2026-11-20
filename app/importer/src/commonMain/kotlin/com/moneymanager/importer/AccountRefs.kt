package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.LocalAccountKey

/** This reference's account id, once the batch's own accounts are resolved. */
internal fun AccountRef.requireExistingId(): AccountId =
    when (this) {
        is AccountRef.Existing -> id
        is AccountRef.Local -> error("Unresolved account reference: $key")
    }

internal fun requireId(ref: AccountRef?): AccountId = (ref ?: error("Missing account reference")).requireExistingId()

/** [ref] with a batch-local key replaced by the account [keyToId] resolved it to. */
internal fun resolveRef(
    ref: AccountRef,
    keyToId: Map<LocalAccountKey, AccountId>,
): AccountRef =
    when (ref) {
        is AccountRef.Existing -> ref
        is AccountRef.Local -> AccountRef.Existing(keyToId[ref.key] ?: error("Unresolved account reference: ${ref.key}"))
    }
