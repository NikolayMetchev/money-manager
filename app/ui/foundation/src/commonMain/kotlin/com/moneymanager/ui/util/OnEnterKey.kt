package com.moneymanager.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Invokes [onEnter] when the Enter (or numpad Enter) key is pressed while this element has focus.
 *
 * This is the cross-platform path for "press Enter to confirm a dialog": it fires on desktop and on
 * hardware keyboards on both platforms. Soft-keyboard "Done" parity (Android) is handled separately via
 * [androidx.compose.foundation.text.KeyboardActions]. The caller is responsible for gating [onEnter] on
 * the same validity the confirm button uses, so Enter never submits a disabled form.
 */
fun Modifier.onEnterKeyDown(onEnter: () -> Unit): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)
        ) {
            onEnter()
            true
        } else {
            false
        }
    }
