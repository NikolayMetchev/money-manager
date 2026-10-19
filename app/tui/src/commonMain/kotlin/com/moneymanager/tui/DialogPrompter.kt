package com.moneymanager.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent
import com.moneymanager.terminal.Prompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A question on screen, waiting for the person. */
sealed interface PromptRequest {
    val label: String

    /** A line of text, or a secret when [masked]. */
    class Line(
        override val label: String,
        val masked: Boolean,
        default: String?,
        internal val answer: CompletableDeferred<String?> = CompletableDeferred(),
    ) : PromptRequest {
        val editor = LineEditor(default.orEmpty())
    }

    class Confirm(
        override val label: String,
        val default: Boolean,
        internal val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) : PromptRequest
}

/** A message for the person, shown in the footer and above any open dialog. */
data class Notice(
    val message: String,
    val error: Boolean,
)

/** Something to read at leisure, such as a sign-in URL: it stays in a dialog until dismissed, and nothing waits on it. */
data class Info(
    val title: String,
    val text: String,
)

/**
 * The TUI's [Prompter]: each question opens a dialog and waits for its answer, so the code that asks (the vault
 * unlocker, the cloud close prompts) is the same code the CLI runs. Questions from concurrent callers queue up,
 * one dialog at a time. The TUI counterpart of the desktop's `CredentialVaultPromptHost`.
 */
class DialogPrompter : Prompter {
    private val oneAtATime = Mutex()

    /** The question on screen, or null. */
    var request: PromptRequest? by mutableStateOf(null)
        private set

    /** The latest message, until the person dismisses it. */
    var notice: Notice? by mutableStateOf(null)

    /** The [Info] on screen, until the person dismisses it. */
    var info: Info? by mutableStateOf(null)

    override val interactive: Boolean get() = true

    /** Shows [text] under [title] in a dialog, without waiting for the person to read it. */
    fun inform(
        title: String,
        text: String,
    ) {
        info = Info(title, text)
    }

    override fun error(message: String) {
        notice = Notice(message, error = true)
    }

    override fun warn(message: String) {
        notice = Notice(message, error = false)
    }

    override suspend fun secret(label: String): String? =
        ask(PromptRequest.Line(label, masked = true, default = null)) { it.answer.await() }?.takeIf { it.isNotEmpty() }

    override suspend fun text(
        label: String,
        default: String,
    ): String? = ask(PromptRequest.Line(label, masked = false, default = default)) { it.answer.await() }?.trim()?.ifEmpty { default }

    override suspend fun confirm(
        label: String,
        default: Boolean,
    ): Boolean = ask(PromptRequest.Confirm(label, default)) { it.answer.await() }

    private suspend fun <R : PromptRequest, T> ask(
        next: R,
        await: suspend (R) -> T,
    ): T =
        oneAtATime.withLock {
            request = next
            try {
                await(next)
            } finally {
                request = null
            }
        }

    /**
     * Answers the open dialog from [event], or dismisses the [info] (Enter or Escape); false when neither is open.
     * Every key goes to an open dialog.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val current = request
        if (current == null) {
            if (info == null) return false
            if (event.key == "Enter" || event.key == "Escape") info = null
            return true
        }
        when (current) {
            is PromptRequest.Line -> {
                when (event.key) {
                    "Enter" -> current.answer.complete(current.editor.text)
                    "Escape" -> current.answer.complete(null)
                    else -> current.editor.handle(event)
                }
            }

            is PromptRequest.Confirm -> {
                when (event.key) {
                    "y", "Y" -> current.answer.complete(true)
                    "n", "N" -> current.answer.complete(false)
                    "Enter", "Escape" -> current.answer.complete(current.default)
                }
            }
        }
        return true
    }

    /** Declines the open dialog, as Escape would: quitting mustn't leave a caller waiting forever. */
    fun dismiss() {
        when (val current = request) {
            is PromptRequest.Line -> current.answer.complete(null)
            is PromptRequest.Confirm -> current.answer.complete(current.default)
            null -> Unit
        }
    }
}
