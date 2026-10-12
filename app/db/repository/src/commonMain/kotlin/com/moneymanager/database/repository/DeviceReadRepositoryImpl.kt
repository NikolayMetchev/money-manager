package com.moneymanager.database.repository

import com.moneymanager.database.mapper.deviceInfoOrNull
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.repository.DeviceReadRepository

class DeviceReadRepositoryImpl(
    private val database: MoneyManagerDatabase,
) : DeviceReadRepository {
    override suspend fun getDeviceById(id: DeviceId): DeviceInfo? {
        val device = database.deviceSelectQueries.selectById(id.id).executeAsOneOrNull() ?: return null
        return deviceInfoOrNull(device.platformName, device.osName, device.machineName, device.deviceMake, device.deviceModel)
    }
}
