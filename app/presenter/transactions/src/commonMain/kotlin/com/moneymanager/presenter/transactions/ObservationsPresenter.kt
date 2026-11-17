package com.moneymanager.presenter.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ObservationDecision
import com.moneymanager.query.TransactionObservations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A person's "same" or "different" about the observations of one transaction. */
sealed interface ObservationsEvent {
    /** Keep [observation] on this transaction whatever the matcher's rules say. */
    data class Pin(
        val observation: ObservationId,
    ) : ObservationsEvent

    /** Let the matcher's rules decide [observation] again. */
    data class Unpin(
        val observation: ObservationId,
    ) : ObservationsEvent

    /**
     * "This one is different": [observation] is a movement of its own, apart from each of [others] (the rest of this
     * transaction), so it leaves for a transaction of its own.
     */
    data class Different(
        val observation: ObservationId,
        val others: List<ObservationId>,
    ) : ObservationsEvent

    /**
     * "These are the same": [mine] (on this transaction) and [other] are one movement. Takes back a split between them
     * and pins both here.
     */
    data class Same(
        val mine: ObservationId,
        val other: ObservationId,
    ) : ObservationsEvent

    /** Take back the split of [a] and [b] without pinning: the matcher's rules decide them again. */
    data class Undo(
        val a: ObservationId,
        val b: ObservationId,
    ) : ObservationsEvent
}

data class ObservationsModel(
    val transactionId: TransactionId,
    /** Null until loaded, and after the transaction is gone. */
    val observations: TransactionObservations?,
    val loading: Boolean,
    /** A decision is being applied; the view disables its buttons. */
    val applying: Boolean,
    val error: String?,
    val eventSink: (ObservationsEvent) -> Unit,
)

/**
 * The observations of [transactionId] and a person's decisions about them, shared by the desktop transaction screen
 * and the TUI. [load] reads them ([com.moneymanager.query.TransactionDetailQuery.observations]); each decision goes
 * through [importEngine] and re-matches, then they are read again. [onChanged] lets the view refresh what else it shows
 * of the transaction, whose values a re-match can change.
 */
@Composable
fun presentObservations(
    transactionId: TransactionId,
    load: suspend (TransactionId) -> TransactionObservations?,
    importEngine: ImportEngine,
    onChanged: () -> Unit = {},
): ObservationsModel {
    val currentLoad by rememberUpdatedState(load)
    val currentOnChanged by rememberUpdatedState(onChanged)
    var generation by remember(transactionId) { mutableStateOf(0) }
    var observations by remember(transactionId) { mutableStateOf<TransactionObservations?>(null) }
    var loading by remember(transactionId) { mutableStateOf(true) }
    var applying by remember(transactionId) { mutableStateOf(false) }
    var error by remember(transactionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(transactionId, generation) {
        loading = true
        try {
            observations = currentLoad(transactionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            error = expected.message ?: expected::class.simpleName
        } finally {
            loading = false
        }
    }

    val eventSink: (ObservationsEvent) -> Unit =
        remember(transactionId, importEngine) {
            { event ->
                if (!applying) {
                    applying = true
                    error = null
                    scope.launch {
                        try {
                            importEngine.apply(event, transactionId)
                            currentOnChanged()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (expected: Exception) {
                            error = expected.message ?: expected::class.simpleName
                        } finally {
                            applying = false
                            generation++
                        }
                    }
                }
            }
        }
    return ObservationsModel(transactionId, observations, loading, applying, error, eventSink)
}

/** One batch per decision, so its re-match runs once, over all of it. */
private suspend fun ImportEngine.apply(
    event: ObservationsEvent,
    transactionId: TransactionId,
) {
    val decisions =
        when (event) {
            is ObservationsEvent.Pin -> {
                listOf(ObservationDecision.Pin(ObservationPin(event.observation, transactionId)))
            }

            is ObservationsEvent.Unpin -> {
                listOf(ObservationDecision.Unpin(event.observation))
            }

            // Its pin here would keep it, so the pin goes with the decision.
            is ObservationsEvent.Different -> {
                listOf(ObservationDecision.Unpin(event.observation)) +
                    event.others.map { ObservationDecision.Split(ObservationSplit.of(event.observation, it)) }
            }

            is ObservationsEvent.Same -> {
                listOf(
                    ObservationDecision.Unsplit(ObservationSplit.of(event.mine, event.other)),
                    ObservationDecision.Pin(ObservationPin(event.mine, transactionId)),
                    ObservationDecision.Pin(ObservationPin(event.other, transactionId)),
                )
            }

            is ObservationsEvent.Undo -> {
                listOf(ObservationDecision.Unsplit(ObservationSplit.of(event.a, event.b)))
            }
        }
    import(ImportBatch(observationDecisions = decisions, syncMatches = true))
}
