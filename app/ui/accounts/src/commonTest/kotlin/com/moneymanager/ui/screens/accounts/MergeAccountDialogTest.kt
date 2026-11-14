@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.importengineapi.AccountMergeRequest
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/** The merge dialog's tickbox decides whether the merge maps the deleted account's name to the survivor. */
class MergeAccountDialogTest {
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

    private val now = Clock.System.now()
    private val old = Account(id = AccountId(1L), name = "Old Bank", openingDate = now)
    private val current = Account(id = AccountId(2L), name = "Current Bank", openingDate = now)

    private fun ComposeUiTest.showDialog(engine: RecordingEngine) {
        val accounts: AccountReadRepository =
            mock(MockMode.autoUnit) {
                every { getAllAccounts() } returns flowOf(listOf(old, current))
                everySuspend { countTransfersByAccount(any()) } returns 3L
                everySuspend { getTransfersBetweenAccounts(any(), any()) } returns emptyList()
            }
        val maintenance: Maintenance =
            mock(MockMode.autoUnit) { everySuspend { fullRefreshMaterializedViews() } returns Duration.ZERO }
        setContent {
            ProvideSchemaAwareScope {
                CompositionLocalProvider(LocalImportEngine provides engine) {
                    DeleteAccountDialog(account = old, accountRepository = accounts, maintenance = maintenance, onDismiss = {})
                }
            }
        }
        waitUntilExactlyOneExists(hasText("Merge into (surviving account)"))
        onNodeWithText("Merge into (surviving account)").performClick()
        waitUntilExactlyOneExists(hasText("Current Bank"))
        onNodeWithText("Current Bank").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.merge(engine: RecordingEngine): AccountMergeRequest {
        onNodeWithText("Merge").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.batches.isNotEmpty() }
        return engine.batches
            .single()
            .accountMerges
            .single()
    }

    @Test
    fun mergeMapsTheDeletedNameByDefault() {
        runMoneyManagerComposeUiTest {
            val engine = RecordingEngine()
            showDialog(engine)
            onNode(isToggleable()).assertIsOn()

            assertEquals(AccountMergeRequest(deletedId = old.id, survivingId = current.id, mapDeletedName = true), merge(engine))
        }
    }

    @Test
    fun untickingTheBoxMergesWithoutAMapping() {
        runMoneyManagerComposeUiTest {
            val engine = RecordingEngine()
            showDialog(engine)
            onNodeWithTag("mergeMapDeletedNameCheckbox").performClick()
            onNode(isToggleable()).assertIsOff()

            assertEquals(AccountMergeRequest(deletedId = old.id, survivingId = current.id), merge(engine))
        }
    }
}
