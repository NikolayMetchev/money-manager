package com.moneymanager.ui.util

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * Shared secret input: passwords, API tokens, keys. When [onSubmit] is provided the field confirms the dialog on
 * Enter (desktop / hardware keyboard) and on the soft-keyboard "Done" action (Android); the caller gates [onSubmit]
 * on the same validity its confirm button uses.
 *
 * Material's secure field edits a `TextFieldState`; this keeps the `value`/`onValueChange` shape every caller
 * already holds its text in, so the screens that keep a plain `String` need no state of their own.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    val state = rememberTextFieldState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)

    // The caller changed the text itself (a loaded key file, a cleared form).
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            if (typed != currentValue) currentOnValueChange(typed)
        }
    }

    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        modifier = if (onSubmit != null) modifier.onEnterKeyDown(onSubmit) else modifier,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        onKeyboardAction = KeyboardActionHandler { onSubmit?.invoke() },
    )
}
