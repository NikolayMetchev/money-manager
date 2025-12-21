package com.moneymanager.domain

import android.os.Build
import com.moneymanager.domain.model.DeviceInfo

/**
 * Android implementation of DeviceInfoProvider.
 * Captures device manufacturer and model.
 */
actual fun getDeviceInfo(): DeviceInfo =
    DeviceInfo.Android(
        deviceMake = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
    )
