package com.moneymanager.test.database

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Money

/**
 * Whether this [Money]'s display value equals [expected] (e.g. "12.50"), independent of the asset's
 * storage scale. Prefer this over comparing [Money.amount] (raw minor units) directly — a hardcoded
 * minor-unit literal silently assumes a specific scale factor and breaks if it ever changes (as it did
 * when every currency moved to a shared, much higher-precision scale).
 */
fun Money.hasDisplayValue(expected: String): Boolean = toDisplayValue().compareTo(BigDecimal(expected)) == 0
