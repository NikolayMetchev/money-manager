@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.imports

import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test

class SourceRerunPreviewTest {
    private val session = Source.Api(ApiSessionId(1))

    @Test
    fun saysWhatItDeletesAndThatHandEditsStay() =
        runMoneyManagerComposeUiTest {
            val plan =
                SourceRerunPlan(
                    source = session,
                    createdTransfers = mapOf(TransferId(1) to session, TransferId(2) to session),
                    tradeIds = setOf(TradeId(3)),
                    createdAccountIds = emptySet(),
                )
            setContent { SourceRerunPreview(plan, recordNoun = "item") }

            onNodeWithText("Deletes the 2 transactions and 1 trade it created", substring = true).assertExists()
            onNodeWithText("imports every item again", substring = true).assertExists()
            onNodeWithText("takes its transactions' ids back", substring = true).assertExists()
        }

    @Test
    fun oneTransaction() =
        runMoneyManagerComposeUiTest {
            val plan =
                SourceRerunPlan(
                    source = session,
                    createdTransfers = mapOf(TransferId(1) to session),
                    tradeIds = emptySet(),
                    createdAccountIds = emptySet(),
                )
            setContent { SourceRerunPreview(plan, recordNoun = "row") }

            onNodeWithText("Deletes the 1 transaction it created", substring = true).assertExists()
        }
}
