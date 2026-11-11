package com.moneymanager.ui.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.reconciliation.ReconciliationFocus

/**
 * A request, made from anywhere, for the Reconciliation tab to open at a given source, wallet and month. The tab takes it
 * when it shows (it may not be showing yet when the request is made) and clears it, so it applies once.
 */
class ReconciliationFocusState {
    var request: ReconciliationFocus? by mutableStateOf(null)

    fun clear() {
        request = null
    }
}
