package com.moneymanager.domain.model

/**
 * What kind of transaction a row represents: a [Transfer] between accounts or a [Trade] converting
 * one asset to another. (Orders are not transactions — they are a separate metadata entity linked to
 * their fill trades, so they never appear as a transaction row.)
 */
enum class TransactionKind {
    TRANSFER,
    TRADE,
}
