package com.moneymanager.database.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyConfigMigrationsTest {
    @Test
    fun `stamping writes the current version and upgrading strips it`() {
        val stamped = StrategyConfigMigrations.stampCsv(JsonObject(mapOf("fileNamePattern" to JsonPrimitive("x"))))
        assertEquals(StrategyConfigMigrations.currentCsvVersion, stamped.getValue(StrategyConfigMigrations.VERSION_KEY).jsonPrimitive.int)

        val upgraded = StrategyConfigMigrations.upgradeCsv(stamped)
        assertFalse(StrategyConfigMigrations.VERSION_KEY in upgraded)
        assertEquals("x", upgraded.getValue("fileNamePattern").jsonPrimitive.content)
    }

    @Test
    fun `a config from a newer app is rejected rather than silently misread`() {
        val future = StrategyConfigMigrations.currentApiVersion + 1
        val config = Json.parseToJsonElement("""{"${StrategyConfigMigrations.VERSION_KEY}": $future}""").jsonObject
        assertFailsWith<IllegalArgumentException> { StrategyConfigMigrations.upgradeApi(config) }
    }
}
