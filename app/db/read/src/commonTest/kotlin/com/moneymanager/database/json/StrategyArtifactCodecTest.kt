package com.moneymanager.database.json

import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.AccountFromRulesExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.FieldMappingExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedAccountExport
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.strategy.StrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StrategyArtifactCodecTest {
    private val sourceMapping =
        HardCodedAccountExport(fieldType = TransferField.SOURCE_ACCOUNT, accountName = "My Account")
    private val targetMapping = AccountFromRulesExport(fieldType = TransferField.TARGET_ACCOUNT, defaultCategoryName = "Uncategorized")
    private val targetNamings =
        listOf(
            AccountNaming(value = ValueExpr(listOf("Payee")), pattern = "TESCO", name = "Tesco"),
            AccountNaming(value = ValueExpr(listOf("Payee")), pattern = "T.*", name = "Other T"),
            AccountNaming(value = ValueExpr(listOf("Payee"))),
        )
    private val attributeRules =
        listOf(
            StrategyRule(actions = listOf(RuleAction.Attribute("kind", RuleValue.read("Kind")))),
            StrategyRule(actions = listOf(RuleAction.Attribute("hash", RuleValue.read("Hash")))),
        )

    private fun csvExport(
        version: String = "1.0.0",
        identificationColumns: Set<String> = setOf("Amount", "Date"),
        fieldMappings: Map<TransferField, FieldMappingExport> =
            mapOf(TransferField.SOURCE_ACCOUNT to sourceMapping, TransferField.TARGET_ACCOUNT to targetMapping),
        rules: List<StrategyRule> = attributeRules + StrategyRule.accounts(AccountSide.TARGET, targetNamings),
        accountMappings: List<AccountMappingExport> =
            listOf(
                AccountMappingExport(valuePattern = "TESCO", accountName = "Tesco"),
                AccountMappingExport(valuePattern = "AMAZON", accountName = "Amazon"),
            ),
    ) = CsvStrategyExport(
        version = version,
        name = "Test Strategy",
        config =
            CsvStrategyConfig(
                identificationColumns = identificationColumns,
                fieldMappings = fieldMappings,
                rules = rules,
            ),
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
                accountMappings = csvExport().accountMappings.reversed(),
            )

        assertEquals(CsvStrategyExportCodec.encode(original), CsvStrategyExportCodec.encode(reordered))
        assertEquals(hash(original), hash(reordered))
    }

    @Test
    fun `csv hash ignores the version stamp`() {
        assertEquals(hash(csvExport()), hash(csvExport(version = "9.9.9")))
    }

    @Test
    fun `csv hash still distinguishes regex rule order because rules are first-match-wins`() {
        val original = csvExport()
        val changed = csvExport(rules = attributeRules + StrategyRule.accounts(AccountSide.TARGET, targetNamings.reversed()))

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

        assertEquals(listOf("Amount", "Date"), decoded.config.identificationColumns.toList())
        val decodedFieldOrder =
            decoded.config.fieldMappings.keys
                .map { it.name }
        assertEquals(decodedFieldOrder.sorted(), decodedFieldOrder)
        assertEquals(reordered.config.rules, decoded.config.rules, "rules are ordered, so they keep their order")
        assertEquals(decoded.accountMappings, decoded.accountMappings.sorted())
    }
}
