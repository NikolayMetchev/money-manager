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
    override fun getOrCreateDevice(deviceInfo: DeviceInfo): DeviceId =
        when (deviceInfo) {
            is DeviceInfo.Jvm -> getOrCreateJvmDevice(deviceInfo)
            is DeviceInfo.Android -> getOrCreateAndroidDevice(deviceInfo)
        }

    private fun getOrCreateJvmDevice(deviceInfo: DeviceInfo.Jvm): DeviceId {
        // Get or create OS
        database.deviceWriteQueries.insertOrIgnoreOs(deviceInfo.osName)
        val osId = database.deviceSelectQueries.selectOsByName(deviceInfo.osName).executeAsOne()

        // Get or create Machine
        database.deviceWriteQueries.insertOrIgnoreMachine(deviceInfo.machineName)
        val machineId = database.deviceSelectQueries.selectMachineByName(deviceInfo.machineName).executeAsOne()

        // Try to find existing device
        val existingDevice =
            database.deviceSelectQueries
                .selectByAttributesJvm(
                    os_id = osId,
                    machine_id = machineId,
                ).executeAsOneOrNull()

        if (existingDevice != null) {
            return DeviceId(existingDevice)
        }

        // Create new device
        database.deviceWriteQueries.insertJvm(
            os_id = osId,
            machine_id = machineId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return DeviceId(
            database.deviceSelectQueries
                .selectByAttributesJvm(
                    os_id = osId,
                    machine_id = machineId,
                ).executeAsOne(),
        )
    }

    private fun getOrCreateAndroidDevice(deviceInfo: DeviceInfo.Android): DeviceId {
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
