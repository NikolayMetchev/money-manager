package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Data class for platform records (test-only).
 */
private data class PlatformRecord(
    val id: Long,
    val name: String,
)

class DeviceRepositoryImplTest : DbTest() {
    /**
     * Test-only query: selectAllPlatforms
     * Retrieves all platforms using raw SQL.
     */
    private suspend fun selectAllPlatforms(): List<PlatformRecord> =
        database
            .rawQuery("SELECT id, name FROM platform") { cursor ->
                PlatformRecord(id = cursor.getLong(0)!!, name = cursor.getString(1)!!)
            }.awaitAsList()

    @Test
    fun `getOrCreateDevice should create device and return valid id`() =
        runTest {
            // First check if Platform table is seeded
            val platforms = selectAllPlatforms()
            assertTrue(platforms.isNotEmpty(), "Platform table should be seeded. Found: ${platforms.size} entries")
            assertTrue(platforms.any { it.id == 1L }, "Platform(id=1) should exist. Found: ${platforms.map { "${it.id}:${it.name}" }}")
            assertTrue(platforms.any { it.id == 2L }, "Platform(id=2) should exist. Found: ${platforms.map { "${it.id}:${it.name}" }}")

            val deviceInfo = getDeviceInfo()

            // Test through the repository (which handles device creation)
            val deviceId = repositories.deviceRepository.getOrCreateDevice(deviceInfo)
            assertTrue(deviceId.id > 0, "Repository device ID should be positive: $deviceId")

            // Verify we can retrieve the device
            val retrievedDevice = repositories.deviceRepository.getDeviceById(deviceId)
            assertNotNull(retrievedDevice, "Should be able to retrieve device by ID")

            assertEquals(deviceInfo, retrievedDevice)

            // Calling again should return the same ID (idempotent)
            val deviceId2 = repositories.deviceRepository.getOrCreateDevice(deviceInfo)
            assertEquals(deviceId, deviceId2, "getOrCreateDevice should return same ID for same device")
        }

    @Test
    fun `Platform table should be seeded with JVM entry`() =
        runTest {
            // Check that Platform(id=1) exists
            val platforms = selectAllPlatforms()
            assertTrue(platforms.isNotEmpty(), "Platform table should be seeded")

            val jvmPlatform = platforms.find { it.id == 1L }
            assertNotNull(jvmPlatform, "Platform(id=1) should exist for JVM. Found: ${platforms.map { "${it.id}:${it.name}" }}")
            assertEquals("JVM", jvmPlatform.name)

            // Check that Platform(id=2) exists for Android
            val androidPlatform = platforms.find { it.id == 2L }
            assertNotNull(androidPlatform, "Platform(id=2) should exist for Android")
            assertEquals("ANDROID", androidPlatform.name)

            assertEquals("NATIVE", platforms.find { it.id == 3L }?.name)
            assertEquals("WEB", platforms.find { it.id == 4L }?.name)
        }

    @Test
    fun `desktop and CLI hosts on one machine are separate devices`() =
        runTest {
            val desktop = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("Linux", "box"))
            val cli = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Native("Linux", "box"))

            assertTrue(desktop != cli, "JVM and NATIVE devices must not share a row")
            assertEquals(DeviceInfo.Jvm("Linux", "box"), repositories.deviceRepository.getDeviceById(desktop))
            assertEquals(DeviceInfo.Native("Linux", "box"), repositories.deviceRepository.getDeviceById(cli))
        }

    @Test
    fun `a browser is a host device of its own`() =
        runTest {
            val desktop = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("Linux", "box"))
            val browser = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Web("Linux", "Chrome"))

            assertTrue(desktop != browser, "JVM and WEB devices must not share a row")
            assertEquals(browser, repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Web("Linux", "Chrome")))
            assertEquals(DeviceInfo.Web("Linux", "Chrome"), repositories.deviceRepository.getDeviceById(browser))
        }

    @Test
    fun `a database created before the NATIVE platform gains its row on first CLI use`() =
        runTest {
            // Opening the database records the host device, which on native is a NATIVE one.
            database.execute(null, "DELETE FROM device WHERE platform_id = 3", 0)
            database.execute(null, "DELETE FROM platform WHERE id = 3", 0)

            val cli = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Native("Linux", "box"))

            assertEquals("NATIVE", selectAllPlatforms().find { it.id == 3L }?.name)
            assertEquals(DeviceInfo.Native("Linux", "box"), repositories.deviceRepository.getDeviceById(cli))
        }
}
