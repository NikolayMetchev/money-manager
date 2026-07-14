package com.moneymanager.database.di

import com.moneymanager.di.scope.DatabaseScope
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.write.DeviceWriteRepository
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Module that provides DeviceId as a singleton.
 * The DeviceId is computed once from the DeviceWriteRepository when first accessed.
 *
 * NOTE: This is the one deliberate exception to the "only the ImportEngine injects a *WriteRepository"
 * rule. [DeviceId] is a synchronous singleton that every write-repository impl (and therefore the
 * ImportEngine itself) depends on, so it must be resolved at graph-construction time — before, and as a
 * dependency of, the engine. Routing it through the (suspend) engine would create a dependency cycle
 * (engine -> write repos -> DeviceId -> engine), so [DeviceWriteRepository] is injected here directly.
 * The arch check that forbids write-repo injection outside the engine whitelists this module.
 */
@ContributesTo(DatabaseScope::class)
interface DeviceIdModule {
    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideDeviceId(deviceRepository: DeviceWriteRepository): DeviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())
}
