@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.moneymanager.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

actual fun Modifier.mouseButtonNavigation(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier =
    this.onPointerEvent(PointerEventType.Press) { event ->
        when (event.button) {
            PointerButton.Back -> onBack()
            PointerButton.Forward -> onForward()
            else -> {}
        }
    }
