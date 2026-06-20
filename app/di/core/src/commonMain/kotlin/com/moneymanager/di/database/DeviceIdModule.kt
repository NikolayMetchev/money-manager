package com.moneymanager.di.database

import com.moneymanager.di.DatabaseScope
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.DeviceWriteRepository
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Module that provides DeviceId as a singleton.
 * The DeviceId is computed once from the DeviceWriteRepository when first accessed.
 */
@ContributesTo(DatabaseScope::class)
interface DeviceIdModule {
    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideDeviceId(deviceRepository: DeviceWriteRepository): DeviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())
}
