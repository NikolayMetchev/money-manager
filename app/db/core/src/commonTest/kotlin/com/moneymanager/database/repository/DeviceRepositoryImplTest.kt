package com.moneymanager.database.repository

import app.cash.sqldelight.db.QueryResult
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
    private fun selectAllPlatforms(): List<PlatformRecord> {
        val sql = "SELECT id, name FROM Platform"
        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val results = mutableListOf<PlatformRecord>()
                while (cursor.next().value) {
                    results.add(
                        PlatformRecord(
                            id = cursor.getLong(0)!!,
                            name = cursor.getString(1)!!,
                        ),
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ).value
    }

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
            assertTrue(deviceId > 0, "Repository device ID should be positive: $deviceId")

            // Verify we can retrieve the device
            val retrievedDevice = repositories.deviceRepository.getDeviceById(deviceId)
            assertNotNull(retrievedDevice, "Should be able to retrieve device by ID")

            // Verify the retrieved device matches the original
            when (deviceInfo) {
                is DeviceInfo.Jvm -> {
                    val retrieved = retrievedDevice as? DeviceInfo.Jvm
                    assertNotNull(retrieved, "Retrieved device should be JVM")
                    assertEquals(deviceInfo.osName, retrieved.osName)
                    assertEquals(deviceInfo.machineName, retrieved.machineName)
                }
                is DeviceInfo.Android -> {
                    val retrieved = retrievedDevice as? DeviceInfo.Android
                    assertNotNull(retrieved, "Retrieved device should be Android")
                    assertEquals(deviceInfo.deviceMake, retrieved.deviceMake)
                    assertEquals(deviceInfo.deviceModel, retrieved.deviceModel)
                }
            }

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
        }
}
