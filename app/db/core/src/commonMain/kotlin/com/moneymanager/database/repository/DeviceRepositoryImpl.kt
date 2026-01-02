package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Platform
import com.moneymanager.domain.repository.DeviceRepository

class DeviceRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : DeviceRepository {
    override fun getOrCreateDevice(deviceInfo: DeviceInfo): DeviceId {
        return when (deviceInfo) {
            is DeviceInfo.Jvm -> getOrCreateJvmDevice(deviceInfo)
            is DeviceInfo.Android -> getOrCreateAndroidDevice(deviceInfo)
        }
    }

    private fun getOrCreateJvmDevice(deviceInfo: DeviceInfo.Jvm): DeviceId {
        // Get or create OS
        database.deviceQueries.insertOrIgnoreOs(deviceInfo.osName)
        val osId = database.deviceQueries.selectOsByName(deviceInfo.osName).executeAsOne()

        // Get or create Machine
        database.deviceQueries.insertOrIgnoreMachine(deviceInfo.machineName)
        val machineId = database.deviceQueries.selectMachineByName(deviceInfo.machineName).executeAsOne()

        // Try to find existing device
        val existingDevice =
            database.deviceQueries.selectByAttributesJvm(
                os_id = osId,
                machine_id = machineId,
            ).executeAsOneOrNull()

        if (existingDevice != null) {
            return DeviceId(existingDevice)
        }

        // Create new device
        database.deviceQueries.insertJvm(
            os_id = osId,
            machine_id = machineId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return DeviceId(
            database.deviceQueries.selectByAttributesJvm(
                os_id = osId,
                machine_id = machineId,
            ).executeAsOne(),
        )
    }

    private fun getOrCreateAndroidDevice(deviceInfo: DeviceInfo.Android): DeviceId {
        // Get or create DeviceMake
        database.deviceQueries.insertOrIgnoreDeviceMake(deviceInfo.deviceMake)
        val makeId = database.deviceQueries.selectDeviceMakeByName(deviceInfo.deviceMake).executeAsOne()

        // Get or create DeviceModel
        database.deviceQueries.insertOrIgnoreDeviceModel(deviceInfo.deviceModel)
        val modelId = database.deviceQueries.selectDeviceModelByName(deviceInfo.deviceModel).executeAsOne()

        // Try to find existing device
        val existingDevice =
            database.deviceQueries.selectByAttributesAndroid(
                device_make_id = makeId,
                device_model_id = modelId,
            ).executeAsOneOrNull()

        if (existingDevice != null) {
            return DeviceId(existingDevice)
        }

        // Create new device
        database.deviceQueries.insertAndroid(
            device_make_id = makeId,
            device_model_id = modelId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return DeviceId(
            database.deviceQueries.selectByAttributesAndroid(
                device_make_id = makeId,
                device_model_id = modelId,
            ).executeAsOne(),
        )
    }

    override suspend fun getDeviceById(id: DeviceId): DeviceInfo? {
        val device = database.deviceQueries.selectById(id.id).executeAsOneOrNull() ?: return null

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

    companion object {
        /**
         * Maps a platform name to a Platform enum.
         */
        fun mapPlatform(platformName: String): Platform =
            when (platformName) {
                "JVM" -> Platform.JVM
                "ANDROID" -> Platform.ANDROID
                else -> throw IllegalArgumentException("Unknown platform: $platformName")
            }

        /**
         * Creates DeviceInfo from query result columns.
         */
        fun createDeviceInfo(
            platformName: String,
            osName: String?,
            machineName: String?,
            deviceMake: String?,
            deviceModel: String?,
        ): DeviceInfo =
            when (platformName) {
                "JVM" ->
                    DeviceInfo.Jvm(
                        osName = osName ?: "Unknown",
                        machineName = machineName ?: "Unknown",
                    )
                "ANDROID" ->
                    DeviceInfo.Android(
                        deviceMake = deviceMake ?: "Unknown",
                        deviceModel = deviceModel ?: "Unknown",
                    )
                else -> throw IllegalArgumentException("Unknown platform: $platformName")
            }
    }
}
