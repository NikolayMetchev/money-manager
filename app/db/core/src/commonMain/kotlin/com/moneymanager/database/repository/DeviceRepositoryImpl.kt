package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Platform
import com.moneymanager.domain.repository.DeviceRepository

class DeviceRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : DeviceRepository {
    override suspend fun getOrCreateDevice(deviceInfo: DeviceInfo): Long {
        return when (deviceInfo) {
            is DeviceInfo.Jvm -> getOrCreateJvmDevice(deviceInfo)
            is DeviceInfo.Android -> getOrCreateAndroidDevice(deviceInfo)
        }
    }

    private suspend fun getOrCreateJvmDevice(deviceInfo: DeviceInfo.Jvm): Long {
        // Get or create OsName
        database.osNameQueries.insertOrIgnore(deviceInfo.osName)
        val osId = database.osNameQueries.selectByName(deviceInfo.osName).executeAsOne()

        // Get or create MachineName
        database.machineNameQueries.insertOrIgnore(deviceInfo.machineName)
        val machineId = database.machineNameQueries.selectByName(deviceInfo.machineName).executeAsOne()

        // Try to find existing device (platform_id = 1 for JVM)
        val existingDevice =
            database.deviceQueries.selectByAttributes(
                platform_id = 1L,
                os_id = osId,
                os_id_ = osId,
                machine_id = machineId,
                machine_id_ = machineId,
                device_make_id = null,
                device_make_id_ = null,
                device_model_id = null,
                device_model_id_ = null,
            ).executeAsOneOrNull()

        if (existingDevice != null) {
            return existingDevice
        }

        // Create new device
        database.deviceQueries.insertJvm(
            os_id = osId,
            machine_id = machineId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return database.deviceQueries.selectByAttributes(
            platform_id = 1L,
            os_id = osId,
            os_id_ = osId,
            machine_id = machineId,
            machine_id_ = machineId,
            device_make_id = null,
            device_make_id_ = null,
            device_model_id = null,
            device_model_id_ = null,
        ).executeAsOne()
    }

    private suspend fun getOrCreateAndroidDevice(deviceInfo: DeviceInfo.Android): Long {
        // Get or create DeviceMake
        database.deviceMakeQueries.insertOrIgnore(deviceInfo.deviceMake)
        val makeId = database.deviceMakeQueries.selectByName(deviceInfo.deviceMake).executeAsOne()

        // Get or create DeviceModel
        database.deviceModelQueries.insertOrIgnore(deviceInfo.deviceModel)
        val modelId = database.deviceModelQueries.selectByName(deviceInfo.deviceModel).executeAsOne()

        // Try to find existing device (platform_id = 2 for Android)
        val existingDevice =
            database.deviceQueries.selectByAttributes(
                platform_id = 2L,
                os_id = null,
                os_id_ = null,
                machine_id = null,
                machine_id_ = null,
                device_make_id = makeId,
                device_make_id_ = makeId,
                device_model_id = modelId,
                device_model_id_ = modelId,
            ).executeAsOneOrNull()

        if (existingDevice != null) {
            return existingDevice
        }

        // Create new device
        database.deviceQueries.insertAndroid(
            device_make_id = makeId,
            device_model_id = modelId,
        )
        // Re-query to get the ID (lastInsertRowId doesn't work reliably with JDBC)
        return database.deviceQueries.selectByAttributes(
            platform_id = 2L,
            os_id = null,
            os_id_ = null,
            machine_id = null,
            machine_id_ = null,
            device_make_id = makeId,
            device_make_id_ = makeId,
            device_model_id = modelId,
            device_model_id_ = modelId,
        ).executeAsOne()
    }

    override suspend fun getDeviceById(id: Long): DeviceInfo? {
        val device = database.deviceQueries.selectById(id).executeAsOneOrNull() ?: return null

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
