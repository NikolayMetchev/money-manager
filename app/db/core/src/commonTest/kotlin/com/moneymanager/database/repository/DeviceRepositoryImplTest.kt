package com.moneymanager.database.repository

import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceRepositoryImplTest : DbTest() {
    @Test
    fun `getOrCreateDevice should create device and return valid id`() =
        runTest {
            // First check if Platform table is seeded
            val platforms = database.platformQueries.selectAll().executeAsList()
            assertTrue(platforms.isNotEmpty(), "Platform table should be seeded. Found: ${platforms.size} entries")
            assertTrue(platforms.any { it.id == 1L }, "Platform(id=1) should exist. Found: ${platforms.map { "${it.id}:${it.name}" }}")

            val deviceInfo = getDeviceInfo()
            assertTrue(deviceInfo is DeviceInfo.Jvm, "Device info should be JVM: $deviceInfo")
            val jvmInfo = deviceInfo as DeviceInfo.Jvm

            // Manually trace through what getOrCreateDevice does
            // 1. Insert OsName
            database.osNameQueries.insertOrIgnore(jvmInfo.osName)
            val osNames = database.osNameQueries.selectAll().executeAsList()
            assertTrue(osNames.isNotEmpty(), "OsName should have entries after insert. Found: ${osNames.size}")

            val osId = database.osNameQueries.selectByName(jvmInfo.osName).executeAsOneOrNull()
            assertNotNull(osId, "OsName should be found for '${jvmInfo.osName}'. Available: ${osNames.map { "${it.id}:${it.name}" }}")
            assertTrue(osId > 0, "OsName ID should be positive: $osId")

            // 2. Insert MachineName
            database.machineNameQueries.insertOrIgnore(jvmInfo.machineName)
            val machineNames = database.machineNameQueries.selectAll().executeAsList()
            assertTrue(machineNames.isNotEmpty(), "MachineName should have entries after insert")

            val machineId = database.machineNameQueries.selectByName(jvmInfo.machineName).executeAsOneOrNull()
            assertNotNull(machineId, "MachineName should be found for '${jvmInfo.machineName}'")
            assertTrue(machineId > 0, "MachineName ID should be positive: $machineId")

            // 3. Test through the repository (which handles device creation)
            val deviceId = repositories.deviceRepository.getOrCreateDevice(deviceInfo)
            assertTrue(deviceId > 0, "Repository device ID should be positive: $deviceId")

            // Verify we can retrieve the device
            val retrievedDevice = repositories.deviceRepository.getDeviceById(deviceId)
            assertNotNull(retrievedDevice, "Should be able to retrieve device by ID")
            assertEquals(jvmInfo.osName, (retrievedDevice as DeviceInfo.Jvm).osName)
            assertEquals(jvmInfo.machineName, retrievedDevice.machineName)

            // Calling again should return the same ID (idempotent)
            val deviceId2 = repositories.deviceRepository.getOrCreateDevice(deviceInfo)
            assertEquals(deviceId, deviceId2, "getOrCreateDevice should return same ID for same device")
        }

    @Test
    fun `Platform table should be seeded with JVM entry`() =
        runTest {
            // Check that Platform(id=1) exists
            val platforms = database.platformQueries.selectAll().executeAsList()
            assertTrue(platforms.isNotEmpty(), "Platform table should be seeded")

            val jvmPlatform = platforms.find { it.id == 1L }
            assertNotNull(jvmPlatform, "Platform(id=1) should exist for JVM. Found: ${platforms.map { "${it.id}:${it.name}" }}")
            assertEquals("JVM", jvmPlatform.name)

            // Check that Platform(id=2) exists for Android
            val androidPlatform = platforms.find { it.id == 2L }
            assertNotNull(androidPlatform, "Platform(id=2) should exist for Android")
            assertEquals("ANDROID", androidPlatform.name)
        }
}
