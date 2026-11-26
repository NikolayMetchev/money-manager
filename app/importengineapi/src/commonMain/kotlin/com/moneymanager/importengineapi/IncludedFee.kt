package com.moneymanager.importengineapi

import com.moneymanager.bigdecimal.BigDecimal

/** A same-asset fee included in a statement's gross amount, before rounding to minor units. */
class IncludedFee(
    gross: BigDecimal,
    fee: BigDecimal?,
) {
    private val grossMagnitude = gross.abs()
    private val feeMagnitude = fee?.abs()
    val isWhole = feeMagnitude != null && grossMagnitude.compareTo(BigDecimal.ZERO) != 0 && grossMagnitude.compareTo(feeMagnitude) == 0
    val netMagnitude get() =
        if (feeMagnitude == null ||
            isWhole
        ) {
            grossMagnitude
        } else {
            (grossMagnitude - feeMagnitude).coerceAtLeast(BigDecimal.ZERO)
        }
}
