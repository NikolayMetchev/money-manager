package com.moneymanager.tui

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
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
