@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

actual fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier =
    // Listen on the Initial pass so the back/forward mouse buttons are handled at the root before a
    // child (e.g. a clickable list card) can consume the press. Other buttons are left untouched.
    this.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
        when (event.button) {
            PointerButton.Back -> onBack()
            PointerButton.Forward -> onForward()
            else -> {}
        }
    }
