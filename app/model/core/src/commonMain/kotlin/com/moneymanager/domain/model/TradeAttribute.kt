package com.moneymanager.domain.model

/** An attribute on a trade (e.g. the source's id, or `excluded` with its reason); one per type. */
data class TradeAttribute(
    val tradeId: TradeId,
    val attributeType: AttributeType,
    val value: String,
)
