package com.moneymanager.ui

import androidx.compose.runtime.compositionLocalOf
import com.moneymanager.domain.model.DeviceId

/**
 * The current device id, provided once at the app root ([com.moneymanager.ui.MoneyManagerApp]).
 *
 * The default ([DeviceId] 1 — the seeded SYSTEM device) only applies to isolated previews/tests that
 * render a screen outside the app root; production always provides the real device. Entities created
 * through the repositories no longer need this: the device is injected into the write layer, so UI
 * callers simply pass a [com.moneymanager.domain.model.Source].
 */
val LocalDeviceId =
    compositionLocalOf {
        DeviceId(1)
    }
