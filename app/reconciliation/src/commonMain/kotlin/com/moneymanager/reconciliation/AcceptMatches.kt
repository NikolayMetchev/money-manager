package com.moneymanager.reconciliation

import com.moneymanager.domain.model.TransactionId
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createReconciliationMatches

/**
 * Stores each of [matches] as a person's reconciliation match and pins every member's observations to that member, in one
 * write: a re-import then re-makes each member as the transaction the match names, so the match holds. Nothing merges,
 * so no balance moves.
 */
suspend fun ImportEngine.acceptMatches(matches: Collection<Collection<TransactionId>>) =
    createReconciliationMatches(matches, pinObservations = true)
