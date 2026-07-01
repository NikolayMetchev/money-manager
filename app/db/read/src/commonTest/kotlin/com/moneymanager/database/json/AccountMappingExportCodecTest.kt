package com.moneymanager.database.json

import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountMappingExportCodecTest {
    @Test
    fun `encode and decode round-trips mappings`() {
        val export =
            AccountMappingsExport(
                version = "1.0.0",
                mappings =
                    listOf(
                        AccountMappingExport(columnName = "Name", valuePattern = ".*Acme.*", accountName = "Acme Bank"),
                        AccountMappingExport(columnName = "Payee", valuePattern = "^Paxos.*$", accountName = "Paxos"),
                    ),
            )

        val decoded = AccountMappingExportCodec.decode(AccountMappingExportCodec.encode(export))

        assertEquals(export, decoded)
        assertEquals(2, decoded.mappings.size)
        assertEquals("Acme Bank", decoded.mappings.first().accountName)
    }

    @Test
    fun `decode tolerates unknown keys and missing mappings`() {
        val json =
            """
            {
                "version": "9.9.9",
                "extraKey": "ignored"
            }
            """.trimIndent()

        val decoded = AccountMappingExportCodec.decode(json)

        assertEquals("9.9.9", decoded.version)
        assertTrue(decoded.mappings.isEmpty())
    }
}
