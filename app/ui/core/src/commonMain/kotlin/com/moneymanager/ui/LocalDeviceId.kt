package com.moneymanager.ui

import androidx.compose.runtime.compositionLocalOf
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityProvenance

/**
 * The current device id, provided once at the app root ([com.moneymanager.ui.MoneyManagerApp]). Lets
 * leaf dialogs that create entities (e.g. currencies/categories) build an [EntityProvenance] without
 * threading a source through every caller. Use [manualProvenance] for user-initiated creation.
 *
 * The default ([DeviceId] 1 — the seeded SYSTEM device) only applies to isolated previews/tests that
 * render a dialog outside the app root; production always provides the real device.
 */
val LocalDeviceId =
    compositionLocalOf {
        DeviceId(1)
    }

/** Manual (this-device) provenance for entities a user creates directly in the UI. */
fun manualProvenance(deviceId: DeviceId): EntityProvenance = EntityProvenance.Manual(deviceId)
