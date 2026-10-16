package com.moneymanager.tui

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.moneymanager.domain.model.AccountId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiWidgetsTest {
    @Test
    fun theLineEditorInsertsAtTheCursorAndDeletesAroundIt() {
        val editor = LineEditor("ac")

        editor.handle(KeyEvent("ArrowLeft"))
        editor.handle(KeyEvent("b"))
        assertEquals("abc", editor.text)
        assertEquals(2, editor.cursor)

        editor.handle(KeyEvent("Backspace"))
        editor.handle(KeyEvent("Delete"))
        assertEquals("a", editor.text)

        editor.handle(KeyEvent("u", ctrl = true))
        assertEquals("", editor.text)
        assertFalse(editor.handle(KeyEvent("Enter")))
    }

    @Test
    fun aMultiSelectTicksWhatTheFilterLeavesUnderTheCursor() {
        val picker = MultiSelect("Assets", listOf(1 to "BTC", 2 to "ETH", 3 to "GBP"), initial = setOf(3))

        assertEquals(PickResult.Continue, picker.handle(KeyEvent("ArrowDown"), pageRows = 5))
        picker.handle(KeyEvent(" "), pageRows = 5)
        assertEquals(setOf(2, 3), picker.selected)

        // Typing narrows the list and puts the cursor back on its first item.
        "bt".forEach { picker.handle(KeyEvent(it.toString()), pageRows = 5) }
        assertEquals(listOf("BTC"), picker.shown.map { it.second })
        picker.handle(KeyEvent(" "), pageRows = 5)
        assertEquals(PickResult.Apply(setOf(1, 2, 3)), picker.handle(KeyEvent("Enter"), pageRows = 5))

        picker.handle(KeyEvent("a", ctrl = true), pageRows = 5)
        assertEquals(PickResult.Apply(emptySet()), picker.handle(KeyEvent("Enter"), pageRows = 5))
        assertEquals(PickResult.Cancel, picker.handle(KeyEvent("Escape"), pageRows = 5))
    }

    @Test
    fun aSingleChoicePicksTheHighlightedItemOnEnterAndIgnoresTicks() {
        val picker = MultiSelect("Account", listOf(1 to "Current", 2 to "Savings"), initial = setOf(1), single = true)

        assertEquals(PickResult.Continue, picker.handle(KeyEvent(" "), pageRows = 5))
        assertEquals(setOf(1), picker.selected)
        picker.handle(KeyEvent("ArrowDown"), pageRows = 5)
        assertEquals(PickResult.Apply(setOf(2)), picker.handle(KeyEvent("Enter"), pageRows = 5))

        picker.handle(KeyEvent("x"), pageRows = 5)
        assertEquals(PickResult.Continue, picker.handle(KeyEvent("Enter"), pageRows = 5))
    }

    @Test
    fun theAccountsSelectionFollowsItsAccountWhenTheListChanges() {
        val (a, b, c) = listOf(AccountId(1), AccountId(2), AccountId(3))

        // An account added above the selection moves it down a row, not onto another account.
        assertEquals(2, followSelection(listOf(c, a, b), selectedId = b, previous = 1))
        // Listed twice (under two owners): the row nearest where it was.
        assertEquals(3, followSelection(listOf(b, a, c, b), selectedId = b, previous = 2))
        // Gone, or nothing picked yet: the same row, kept on the list.
        assertEquals(1, followSelection(listOf(a, c), selectedId = b, previous = 4))
        assertEquals(0, followSelection(emptyList(), selectedId = null, previous = 3))
    }

    @Test
    fun aSecretIsAnsweredFromTheKeysTypedIntoItsDialog() =
        runTest {
            val prompter = DialogPrompter()
            val answer = async { prompter.secret("Vault password") }
            yield()

            assertEquals("Vault password", prompter.request?.label)
            "pw".forEach { prompter.handleKey(KeyEvent(it.toString())) }
            prompter.handleKey(KeyEvent("Enter"))

            assertEquals("pw", answer.await())
            assertNull(prompter.request)
        }

    @Test
    fun escapeDeclinesAndAnEmptyTextAnswerTakesTheDefault() =
        runTest {
            val prompter = DialogPrompter()
            val declined = async { prompter.secret("Vault password") }
            yield()
            prompter.handleKey(KeyEvent("Escape"))
            assertNull(declined.await())

            val text = async { prompter.text("Vault file", "/tmp/vault") }
            yield()
            prompter.handleKey(KeyEvent("u", ctrl = true))
            prompter.handleKey(KeyEvent("Enter"))
            assertEquals("/tmp/vault", text.await())

            val confirm = async { prompter.confirm("Create one", default = true) }
            yield()
            prompter.handleKey(KeyEvent("n"))
            assertFalse(confirm.await())
        }

    @Test
    fun questionsFromConcurrentCallersOpenOneDialogAtATime() =
        runTest {
            val prompter = DialogPrompter()
            val first = async { prompter.confirm("First", default = false) }
            val second = async { prompter.confirm("Second", default = false) }
            yield()

            assertEquals("First", prompter.request?.label)
            prompter.handleKey(KeyEvent("y"))
            assertTrue(first.await())
            yield()
            assertEquals("Second", prompter.request?.label)
            prompter.dismiss()
            assertFalse(second.await())
        }

    @Test
    fun keysOutsideADialogAreLeftToTheScreen() {
        assertFalse(DialogPrompter().handleKey(KeyEvent("q")))
    }

    @Test
    fun onlyKeysMosaicCanNameReachTheComposition() {
        assertTrue(KeyFilterTerminal.isNamedKey('a'.code))
        assertTrue(KeyFilterTerminal.isNamedKey(KeyboardEvent.Up))
        assertTrue(KeyFilterTerminal.isNamedKey(KeyboardEvent.F12))
        assertFalse(KeyFilterTerminal.isNamedKey('é'.code))
        assertFalse(KeyFilterTerminal.isNamedKey(0x1c))
    }

    @Test
    fun aFrameRedrawsEveryRowFromTheTopWithoutScrolling() {
        assertEquals(
            "\u001b[H\u001b[2Kone\r\n\u001b[2Ktwo\r\n\u001b[J",
            ScreenWriter.frame(listOf("one", "two"), screenRows = 5, synchronized = false),
        )
        // A full screen ends on its last row: a newline there would scroll the first row away.
        assertEquals(
            "\u001b[H\u001b[2Kone\r\n\u001b[2Ktwo",
            ScreenWriter.frame(listOf("one", "two"), screenRows = 2, synchronized = false),
        )
        assertTrue(ScreenWriter.frame(listOf("x"), screenRows = 1, synchronized = true).startsWith("\u001b[?2026h"))
    }
}
