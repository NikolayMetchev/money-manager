@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.remotestorage.sync.StrategyItem
import com.moneymanager.remotestorage.sync.StrategyItemStatus
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val synced = StrategyKey(StrategyKind.CSV, "Monzo")
private val uploading = StrategyKey(StrategyKind.API, "Wise")
private val onDrive = StrategyKey(StrategyKind.PDF, "Amex")
private val clashing = StrategyKey(StrategyKind.GLOBAL_MAPPINGS, "global-account-mappings")
private val unreadable = StrategyKey(StrategyKind.ASSET_LIST, "Assets and aliases")

private val items =
    listOf(
        StrategyItem(synced, StrategyItemStatus.IN_SYNC),
        StrategyItem(uploading, StrategyItemStatus.NEW_LOCAL),
        StrategyItem(onDrive, StrategyItemStatus.AVAILABLE),
        StrategyItem(clashing, StrategyItemStatus.CONFLICT),
        StrategyItem(unreadable, StrategyItemStatus.UNREADABLE, error = "Unknown format", hasLocal = true),
    )

class StrategyItemListTest {
    @Test
    fun rowsAreGroupedAndSyncedStartsCollapsed() {
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme { StrategyItemList(items, emptyList(), emptyMap(), { _, _ -> }, { _, _ -> }) }
            }
            onNodeWithText("▾ Clashing (2)").assertExists()
            onNodeWithText("▾ Unsynced (2)").assertExists()
            onNodeWithText("▸ Synced (1)").assertExists()

            onNodeWithText("Global account mappings").assertExists()
            onNodeWithText("Assets and aliases (Catalog list)").assertExists()
            onNodeWithText("Unknown format").assertExists()
            onNodeWithText("Replace on Drive").assertExists()
            onNodeWithText("Wise (API)").assertExists()
            onNodeWithText("Will upload").assertExists()
            onNodeWithText("Amex (PDF)").assertExists()
            onNodeWithText("New on Drive").assertExists()
            onNodeWithText("Monzo (CSV)").assertDoesNotExist()
        }
    }

    @Test
    fun clickingAGroupHeaderTogglesIt() {
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme { StrategyItemList(items, emptyList(), emptyMap(), { _, _ -> }, { _, _ -> }) }
            }
            onNodeWithTag("strategySyncGroup_SYNCED").performClick()
            waitForIdle()
            onNodeWithText("▾ Synced (1)").assertExists()
            onNodeWithText("Monzo (CSV)").assertExists()
            onNodeWithText("In sync").assertExists()

            onNodeWithTag("strategySyncGroup_UNSYNCED").performClick()
            waitForIdle()
            onNodeWithText("▸ Unsynced (2)").assertExists()
            onNodeWithText("Wise (API)").assertDoesNotExist()
        }
    }

    @Test
    fun aConflictOffersBothDirectionsAndAPullableRowATickbox() {
        runMoneyManagerComposeUiTest {
            val choices = mutableListOf<Pair<StrategyKey, ConflictChoice>>()
            val toggles = mutableListOf<Pair<StrategyKey, Boolean>>()
            setContent {
                MaterialTheme {
                    StrategyItemList(
                        items,
                        emptyList(),
                        emptyMap(),
                        { key, checked -> toggles += key to checked },
                        { key, choice -> choices += key to choice },
                    )
                }
            }
            onNodeWithText("Keep mine").performClick()
            onNodeWithText("Take Drive").performClick()
            onNodeWithText("Replace on Drive").performClick()
            waitForIdle()
            assertEquals(
                listOf(
                    clashing to ConflictChoice.KEEP_MINE,
                    clashing to ConflictChoice.TAKE_DRIVE,
                    unreadable to ConflictChoice.KEEP_MINE,
                ),
                choices,
            )
            onNode(isToggleable()).performClick()
            waitForIdle()
            assertEquals(listOf(onDrive to true), toggles)
        }
    }
}
