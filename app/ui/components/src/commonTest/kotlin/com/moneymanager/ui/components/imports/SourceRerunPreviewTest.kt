@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.imports

import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.RerunFile
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.uuid.Uuid

class SourceRerunPreviewTest {
    private val session = Source.Api(ApiSessionId(1))

    private fun dependentFile() = RerunFile(Source.Csv(CsvImportId(Uuid.random())), emptyMap(), emptySet(), emptySet())

    @Test
    fun saysWhatItDeletesAndWhichOtherFilesItTakesAlong() =
        runMoneyManagerComposeUiTest {
            val plan =
                SourceRerunPlan(
                    source = session,
                    createdTransfers = mapOf(TransferId(1) to session, TransferId(2) to session),
                    tradeIds = setOf(TradeId(3)),
                    createdAccountIds = emptySet(),
                    manualEdits = emptyList(),
                    cascade = listOf(dependentFile(), dependentFile()),
                )
            setContent { SourceRerunPreview(plan, recordNoun = "item", accountName = { "account ${it.id}" }) }

            onNodeWithText("Deletes the 2 transactions and 1 trade it created", substring = true).assertExists()
            onNodeWithText("imports every item again", substring = true).assertExists()
            onNodeWithText("Other files to re-import too (2):").assertExists()
            onNodeWithText("2 other files matched or reconciled against these transactions", substring = true).assertExists()
        }

    @Test
    fun oneTransactionAndNoDependentFiles() =
        runMoneyManagerComposeUiTest {
            val plan =
                SourceRerunPlan(
                    source = session,
                    createdTransfers = mapOf(TransferId(1) to session),
                    tradeIds = emptySet(),
                    createdAccountIds = emptySet(),
                    manualEdits = emptyList(),
                )
            setContent { SourceRerunPreview(plan, recordNoun = "row", accountName = { "account ${it.id}" }) }

            onNodeWithText("Deletes the 1 transaction it created", substring = true).assertExists()
            onNodeWithText("Other files to re-import too", substring = true).assertDoesNotExist()
        }
}
