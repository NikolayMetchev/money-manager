package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo

/**
 * Provides platform-specific device information for source tracking.
 * Each platform implements this to capture relevant device metadata.
 */
expect fun getDeviceInfo(): DeviceInfo
