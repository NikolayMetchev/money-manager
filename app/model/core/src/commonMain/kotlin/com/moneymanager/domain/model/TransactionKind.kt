package com.moneymanager.domain.model

/**
 * What kind of transaction a row represents: a [Transfer] between accounts, a [Trade] converting
 * one asset to another, or an order (declared ahead of the order entity landing).
 */
enum class TransactionKind {
    TRANSFER,
    TRADE,
    ORDER,
}
