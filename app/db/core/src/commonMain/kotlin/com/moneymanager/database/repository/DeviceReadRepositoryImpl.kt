package com.moneymanager.database.repository

import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.repository.DeviceReadRepository

class DeviceReadRepositoryImpl(
    private val database: MoneyManagerDatabase,
) : DeviceReadRepository {
    override suspend fun getDeviceById(id: DeviceId): DeviceInfo? {
        val device = database.deviceSelectQueries.selectById(id.id).executeAsOneOrNull() ?: return null

        return when (device.platformName) {
            "JVM" ->
                DeviceInfo.Jvm(
                    osName = device.osName ?: "Unknown",
                    machineName = device.machineName ?: "Unknown",
                )
            "ANDROID" ->
                DeviceInfo.Android(
                    deviceMake = device.deviceMake ?: "Unknown",
                    deviceModel = device.deviceModel ?: "Unknown",
                )
            else -> null
        }
    }
}
