package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.reconciliation.UnmatchedSourceLeg
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * What the match picker has open, held above the screen so that following a candidate's link to its transaction or
 * source and pressing back finds the picker as it was left, rather than closed with the selection lost.
 */
@Stable
class MatchPickerState {
    /** The [pickerKey] of the unmatched source leg being matched; null while the picker is closed. */
    var pickedKey: String? by mutableStateOf(null)
    var window: Duration by mutableStateOf(DEFAULT_WINDOW)
    var sameAssetOnly: Boolean by mutableStateOf(true)
    val selected = mutableStateListOf<ReconciliationLeg>()

    fun open(key: String) {
        pickedKey = key
        window = DEFAULT_WINDOW
        sameAssetOnly = true
        selected.clear()
    }

    fun close() {
        pickedKey = null
        selected.clear()
    }

    private companion object {
        val DEFAULT_WINDOW = 3.days
    }
}

/** Identifies an unmatched source leg across recompositions and navigation, where the leg object itself is rebuilt. */
internal val UnmatchedSourceLeg.pickerKey: String get() = "${walletAccountId.id}-${leg.key}"
