package com.moneymanager.database.json

import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.FieldMappingExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedAccountExport
import com.moneymanager.domain.model.csvstrategy.export.RegexAccountExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StrategyArtifactCodecTest {
    private val sourceMapping =
        HardCodedAccountExport(fieldType = TransferField.SOURCE_ACCOUNT, accountName = "My Account")
    private val targetMapping =
        RegexAccountExport(
            fieldType = TransferField.TARGET_ACCOUNT,
            columnName = "Payee",
            rules =
                listOf(
                    RegexRule(pattern = "TESCO", accountName = "Tesco"),
                    RegexRule(pattern = "T.*", accountName = "Other T"),
                ),
            defaultCategoryName = "Uncategorized",
        )

    private fun csvExport(
        version: String = "1.0.0",
        identificationColumns: Set<String> = setOf("Amount", "Date"),
        fieldMappings: Map<TransferField, FieldMappingExport> =
            mapOf(TransferField.SOURCE_ACCOUNT to sourceMapping, TransferField.TARGET_ACCOUNT to targetMapping),
        attributeMappings: List<AttributeColumnMapping> =
            listOf(
                AttributeColumnMapping(columnName = "Kind", attributeTypeName = "kind"),
                AttributeColumnMapping(columnName = "Hash", attributeTypeName = "hash"),
            ),
        accountMappings: List<AccountMappingExport> =
            listOf(
                AccountMappingExport(valuePattern = "TESCO", accountName = "Tesco"),
                AccountMappingExport(valuePattern = "AMAZON", accountName = "Amazon"),
            ),
    ) = CsvStrategyExport(
        version = version,
        name = "Test Strategy",
        identificationColumns = identificationColumns,
        fieldMappings = fieldMappings,
        attributeMappings = attributeMappings,
        accountMappings = accountMappings,
    )

    private fun hash(export: CsvStrategyExport): String =
        StrategyArtifactCodec.canonicalHash(StrategyKind.CSV, CsvStrategyExportCodec.encode(export))

    @Test
    fun `unordered collections serialize to identical bytes regardless of construction order`() {
        val original = csvExport()
        val reordered =
            csvExport(
                identificationColumns = setOf("Date", "Amount"),
                fieldMappings = mapOf(TransferField.TARGET_ACCOUNT to targetMapping, TransferField.SOURCE_ACCOUNT to sourceMapping),
                attributeMappings = csvExport().attributeMappings.reversed(),
                accountMappings = csvExport().accountMappings.reversed(),
            )

        assertEquals(CsvStrategyExportCodec.encode(original), CsvStrategyExportCodec.encode(reordered))
        assertEquals(hash(original), hash(reordered))
    }

    @Test
    fun `csv hash ignores the version stamp`() {
        assertEquals(hash(csvExport(version = "1.0.0")), hash(csvExport(version = "9.9.9")))
    }

    @Test
    fun `csv hash still distinguishes regex rule order because rules are first-match-wins`() {
        val reorderedRules = targetMapping.copy(rules = targetMapping.rules.reversed())
        val original = csvExport()
        val changed =
            csvExport(fieldMappings = mapOf(TransferField.SOURCE_ACCOUNT to sourceMapping, TransferField.TARGET_ACCOUNT to reorderedRules))

        assertNotEquals(hash(original), hash(changed))
    }

    @Test
    fun `global mappings hash ignores mapping order and version`() {
        val mappings =
            listOf(
                AccountMappingExport(valuePattern = "TESCO", accountName = "Tesco"),
                AccountMappingExport(valuePattern = "AMAZON", accountName = "Amazon"),
                AccountMappingExport(valuePattern = "AMAZON", accountName = "Amazon Prime"),
            )
        val original = AccountMappingsExport(version = "1.0.0", mappings = mappings)
        val reordered = AccountMappingsExport(version = "2.0.0", mappings = mappings.reversed())

        assertEquals(
            StrategyArtifactCodec.canonicalHash(StrategyKind.GLOBAL_MAPPINGS, AccountMappingExportCodec.encode(original)),
            StrategyArtifactCodec.canonicalHash(StrategyKind.GLOBAL_MAPPINGS, AccountMappingExportCodec.encode(reordered)),
        )
    }

    @Test
    fun `decoding returns collections in canonical order`() {
        val reordered =
            csvExport(
                identificationColumns = setOf("Date", "Amount"),
                accountMappings = csvExport().accountMappings.reversed(),
            )

        val decoded = CsvStrategyExportCodec.decode(CsvStrategyExportCodec.encode(reordered))

        assertEquals(listOf("Amount", "Date"), decoded.identificationColumns.toList())
        val decodedFieldOrder = decoded.fieldMappings.keys.map { it.name }
        assertEquals(decodedFieldOrder.sorted(), decodedFieldOrder)
        assertEquals(decoded.attributeMappings, decoded.attributeMappings.sorted())
        assertEquals(decoded.accountMappings, decoded.accountMappings.sorted())
    }
}
