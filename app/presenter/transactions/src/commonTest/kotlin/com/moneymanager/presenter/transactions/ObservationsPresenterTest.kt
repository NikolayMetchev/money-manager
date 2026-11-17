package com.moneymanager.presenter.transactions

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ObservationDecision
import com.moneymanager.query.TransactionObservations
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationsPresenterTest {
    private val transaction = TransferId(10)

    private class RecordingEngine : ImportEngine {
        val batches = mutableListOf<ImportBatch>()

        override suspend fun import(
            batch: ImportBatch,
            onProgress: (suspend (Progress) -> Unit)?,
            batchSize: Int,
        ): ImportResult {
            batches += batch
            return ImportResult()
        }
    }

    @Test
    fun differentUnpinsAndSplitsFromEachOtherObservation_inOneBatch_thenReloads() =
        runTest {
            val engine = RecordingEngine()
            var loads = 0
            var changed = 0
            present(engine, onLoad = { loads++ }, onChanged = { changed++ }).test {
                awaitUntil { !it.loading && it.observations != null }
                    .eventSink(ObservationsEvent.Different(ObservationId(3), listOf(ObservationId(1), ObservationId(2))))
                awaitUntil { !it.applying && !it.loading && loads == 2 }

                val batch = engine.batches.single()
                assertTrue(batch.syncMatches)
                assertEquals(
                    listOf(
                        ObservationDecision.Unpin(ObservationId(3)),
                        ObservationDecision.Split(ObservationSplit.of(ObservationId(1), ObservationId(3))),
                        ObservationDecision.Split(ObservationSplit.of(ObservationId(2), ObservationId(3))),
                    ),
                    batch.observationDecisions,
                )
                assertEquals(1, changed)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun sameTakesBackTheSplitAndPinsBothHere() =
        runTest {
            val engine = RecordingEngine()
            present(engine).test {
                awaitUntil { !it.loading && it.observations != null }
                    .eventSink(ObservationsEvent.Same(ObservationId(1), ObservationId(99)))
                awaitUntil { !it.applying && engine.batches.isNotEmpty() }

                assertEquals(
                    listOf(
                        ObservationDecision.Unsplit(ObservationSplit.of(ObservationId(1), ObservationId(99))),
                        ObservationDecision.Pin(ObservationPin(ObservationId(1), transaction)),
                        ObservationDecision.Pin(ObservationPin(ObservationId(99), transaction)),
                    ),
                    engine.batches.single().observationDecisions,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun present(
        engine: ImportEngine,
        onLoad: () -> Unit = {},
        onChanged: () -> Unit = {},
    ) = moleculeFlow(RecompositionMode.Immediate) {
        presentObservations(
            transactionId = transaction,
            load = {
                onLoad()
                TransactionObservations()
            },
            importEngine = engine,
            onChanged = onChanged,
        )
    }

    private suspend fun ReceiveTurbine<ObservationsModel>.awaitUntil(predicate: (ObservationsModel) -> Boolean): ObservationsModel {
        while (true) {
            val model = awaitItem()
            if (predicate(model)) return model
        }
    }
}
