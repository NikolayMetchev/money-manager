package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.write.DeviceWriteRepository

class DeviceWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    reader: DeviceReadRepository,
) : DeviceWriteRepository,
    DeviceReadRepository by reader {
    override suspend fun getOrCreateDevice(deviceInfo: DeviceInfo): DeviceId =
        when (deviceInfo) {
            is DeviceInfo.Host -> getOrCreateHostDevice(deviceInfo)
            is DeviceInfo.Android -> getOrCreateAndroidDevice(deviceInfo)
        }

    private suspend fun getOrCreateHostDevice(deviceInfo: DeviceInfo.Host): DeviceId {
        val platform = deviceInfo.platform
        database.deviceWriteQueries.insertOrIgnorePlatform(platform.id, platform.name)

        database.deviceWriteQueries.insertOrIgnoreOs(deviceInfo.osName)
        val osId = database.deviceSelectQueries.selectOsByName(deviceInfo.osName).executeAsOne()

        database.deviceWriteQueries.insertOrIgnoreMachine(deviceInfo.machineName)
        val machineId = database.deviceSelectQueries.selectMachineByName(deviceInfo.machineName).executeAsOne()

        fun existing() =
            database.deviceSelectQueries
                .selectByAttributesHost(platform_id = platform.id, os_id = osId, machine_id = machineId)
                .executeAsOneOrNull()

        existing()?.let { return DeviceId(it) }
        database.deviceWriteQueries.insertHost(platform_id = platform.id, os_id = osId, machine_id = machineId)
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return DeviceId(checkNotNull(existing()) { "Device row for $deviceInfo not found after insert" })
    }

    private suspend fun getOrCreateAndroidDevice(deviceInfo: DeviceInfo.Android): DeviceId {
        // Get or create DeviceMake
        database.deviceWriteQueries.insertOrIgnoreDeviceMake(deviceInfo.deviceMake)
        val makeId = database.deviceSelectQueries.selectDeviceMakeByName(deviceInfo.deviceMake).executeAsOne()

        // Get or create DeviceModel
        database.deviceWriteQueries.insertOrIgnoreDeviceModel(deviceInfo.deviceModel)
        val modelId = database.deviceSelectQueries.selectDeviceModelByName(deviceInfo.deviceModel).executeAsOne()

        // Try to find existing device
        val existingDevice =
            database.deviceSelectQueries
                .selectByAttributesAndroid(
                    device_make_id = makeId,
                    device_model_id = modelId,
                ).executeAsOneOrNull()

        if (existingDevice != null) {
            return DeviceId(existingDevice)
        }

        // Create new device
        database.deviceWriteQueries.insertAndroid(
            device_make_id = makeId,
            device_model_id = modelId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return DeviceId(
            database.deviceSelectQueries
                .selectByAttributesAndroid(
                    device_make_id = makeId,
                    device_model_id = modelId,
                ).executeAsOne(),
        )
    }
}
