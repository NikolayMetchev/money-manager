package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * A conduit every row of a strategy runs through: a card aggregator (Curve) or wallet whose own export
 * records each payment it forwarded, so a row `funder -> merchant` really moved `funder -> conduit ->
 * merchant`. The row is imported as that pass-through chain (the same shape a card statement's `CRV*`
 * row gets from a pass-through definition), so the conduit nets to zero and the merchant spend is
 * counted once. The row's source is the funder (an incoming row's target), typically an unidentified
 * placeholder the engine reconciles against the funder's own statement.
 *
 * @property accountName The conduit account.
 * @property conditions A row runs through the conduit only when all of these hold (e.g. not a row paid
 *   from the conduit's own rewards balance); empty means every row.
 */
@Serializable
data class StrategyConduit(
    val accountName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedConditionListSerializer::class)
    val conditions: List<Condition> = emptyList(),
)
