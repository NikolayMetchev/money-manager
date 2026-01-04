package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.AccountLookupExport
import com.moneymanager.domain.model.csvstrategy.export.AmountParsingExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.DateTimeParsingExport
import com.moneymanager.domain.model.csvstrategy.export.DirectColumnExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedAccountExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedCurrencyExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedTimezoneExport
import com.moneymanager.domain.model.csvstrategy.export.RegexAccountExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvStrategyExportCodecTest {
    @Test
    fun `encode and decode simple strategy export`() {
        val export =
            CsvStrategyExport(
                version = "1.0.0",
                name = "Test Strategy",
                identificationColumns = setOf("Date", "Amount"),
                fieldMappings =
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            HardCodedAccountExport(
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                accountName = "My Account",
                            ),
                    ),
            )

        val json = CsvStrategyExportCodec.encode(export)
        val decoded = CsvStrategyExportCodec.decode(json)

        assertEquals(export.version, decoded.version)
        assertEquals(export.name, decoded.name)
        assertEquals(export.identificationColumns, decoded.identificationColumns)
        assertEquals(1, decoded.fieldMappings.size)

        val mapping = decoded.fieldMappings[TransferField.SOURCE_ACCOUNT]
        assertIs<HardCodedAccountExport>(mapping)
        assertEquals("My Account", mapping.accountName)
    }

    @Test
    fun `encode and decode strategy with all field mapping types`() {
        val export =
            CsvStrategyExport(
                version = "2.0.0",
                name = "Full Strategy",
                identificationColumns = setOf("Date", "Description", "Amount"),
                fieldMappings =
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            HardCodedAccountExport(
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                accountName = "Source Account",
                            ),
                        TransferField.TARGET_ACCOUNT to
                            AccountLookupExport(
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Payee",
                                fallbackColumns = listOf("Type"),
                                defaultCategoryName = "Uncategorized",
                            ),
                        TransferField.TIMESTAMP to
                            DateTimeParsingExport(
                                fieldType = TransferField.TIMESTAMP,
                                dateColumnName = "Date",
                                dateFormat = "dd/MM/yyyy",
                                timeColumnName = "Time",
                                timeFormat = "HH:mm",
                                defaultTime = "12:00:00",
                            ),
                        TransferField.DESCRIPTION to
                            DirectColumnExport(
                                fieldType = TransferField.DESCRIPTION,
                                columnName = "Description",
                            ),
                        TransferField.AMOUNT to
                            AmountParsingExport(
                                fieldType = TransferField.AMOUNT,
                                mode = AmountMode.SINGLE_COLUMN,
                                amountColumnName = "Amount",
                                negateValues = true,
                            ),
                        TransferField.CURRENCY to
                            HardCodedCurrencyExport(
                                fieldType = TransferField.CURRENCY,
                                currencyCode = "GBP",
                            ),
                        TransferField.TIMEZONE to
                            HardCodedTimezoneExport(
                                fieldType = TransferField.TIMEZONE,
                                timezoneId = "Europe/London",
                            ),
                    ),
            )

        val json = CsvStrategyExportCodec.encode(export)
        val decoded = CsvStrategyExportCodec.decode(json)

        assertEquals(export.version, decoded.version)
        assertEquals(export.name, decoded.name)
        assertEquals(7, decoded.fieldMappings.size)

        assertIs<HardCodedAccountExport>(decoded.fieldMappings[TransferField.SOURCE_ACCOUNT])
        assertIs<AccountLookupExport>(decoded.fieldMappings[TransferField.TARGET_ACCOUNT])
        assertIs<DateTimeParsingExport>(decoded.fieldMappings[TransferField.TIMESTAMP])
        assertIs<DirectColumnExport>(decoded.fieldMappings[TransferField.DESCRIPTION])
        assertIs<AmountParsingExport>(decoded.fieldMappings[TransferField.AMOUNT])
        assertIs<HardCodedCurrencyExport>(decoded.fieldMappings[TransferField.CURRENCY])
        assertIs<HardCodedTimezoneExport>(decoded.fieldMappings[TransferField.TIMEZONE])
    }

    @Test
    fun `encode and decode RegexAccountExport`() {
        val export =
            CsvStrategyExport(
                version = "1.0.0",
                name = "Regex Strategy",
                identificationColumns = emptySet(),
                fieldMappings =
                    mapOf(
                        TransferField.TARGET_ACCOUNT to
                            RegexAccountExport(
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Description",
                                rules =
                                    listOf(
                                        RegexRule(pattern = ".*Grocery.*", accountName = "Food"),
                                        RegexRule(pattern = ".*Amazon.*", accountName = "Shopping"),
                                    ),
                                fallbackColumns = listOf("Name"),
                                defaultCategoryName = "Other",
                            ),
                    ),
            )

        val json = CsvStrategyExportCodec.encode(export)
        val decoded = CsvStrategyExportCodec.decode(json)

        val mapping = decoded.fieldMappings[TransferField.TARGET_ACCOUNT]
        assertIs<RegexAccountExport>(mapping)
        assertEquals("Description", mapping.columnName)
        assertEquals(2, mapping.rules.size)
        assertEquals(".*Grocery.*", mapping.rules[0].pattern)
        assertEquals("Food", mapping.rules[0].accountName)
        assertEquals(listOf("Name"), mapping.fallbackColumns)
        assertEquals("Other", mapping.defaultCategoryName)
    }

    @Test
    fun `encoded JSON is pretty printed`() {
        val export =
            CsvStrategyExport(
                version = "1.0.0",
                name = "Test",
                identificationColumns = setOf("A"),
                fieldMappings = emptyMap(),
            )

        val json = CsvStrategyExportCodec.encode(export)

        assertTrue(json.contains("\n"), "JSON should be pretty printed with newlines")
        assertTrue(json.contains("  "), "JSON should have indentation")
    }

    @Test
    fun `encoded JSON contains expected fields`() {
        val export =
            CsvStrategyExport(
                version = "1.0.0",
                name = "My Bank Strategy",
                identificationColumns = setOf("Date", "Amount"),
                fieldMappings = emptyMap(),
            )

        val json = CsvStrategyExportCodec.encode(export)

        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"1.0.0\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"My Bank Strategy\""))
        assertTrue(json.contains("\"identificationColumns\""))
    }

    @Test
    fun `decode ignores unknown fields`() {
        val jsonWithUnknownFields =
            """
            {
                "version": "1.0.0",
                "name": "Test",
                "identificationColumns": [],
                "fieldMappings": {},
                "unknownField": "should be ignored",
                "anotherUnknown": 123
            }
            """.trimIndent()

        val decoded = CsvStrategyExportCodec.decode(jsonWithUnknownFields)

        assertEquals("1.0.0", decoded.version)
        assertEquals("Test", decoded.name)
    }
}
