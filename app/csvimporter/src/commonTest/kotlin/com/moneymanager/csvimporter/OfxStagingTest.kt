package com.moneymanager.csvimporter

import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.ofx.OfxColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfxStagingTest {
    private fun column(name: String) = OfxColumns.headers.indexOf(name)

    @Test
    fun aTransactionIsOneRowOverTheOfxColumns() {
        val ofx =
            """
            OFXHEADER:100
            DATA:OFXSGML

            <OFX><SIGNONMSGSRSV1><SONRS><FI><ORG>BANK</ORG></FI></SONRS></SIGNONMSGSRSV1>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>GBP
            <BANKACCTFROM><BANKID>123456<ACCTID>12345678<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>POS<DTPOSTED>20240402134501.000[-7:MST]<DTUSER>20240401<TRNAMT>-12.34
            <FITID>F1<REFNUM>R1<CHECKNUM>7<NAME>SHOP<MEMO>LUNCH
            <ORIGCURRENCY><CURRATE>1.25<CURSYM>USD</ORIGCURRENCY></STMTTRN>
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
            """.trimIndent()

        val parsed = parseOfxAsCsv(ofx.encodeToByteArray())

        assertEquals(OfxColumns.headers, parsed.headers)
        assertEquals(
            listOf(
                listOf(
                    "2024-04-02",
                    "13:45:01",
                    "-07:00",
                    "2024-04-01",
                    "-12.34",
                    "POS",
                    "F1",
                    "R1",
                    "7",
                    "SHOP",
                    "LUNCH",
                    "GBP",
                    "USD",
                    "1.25",
                    "12345678",
                    "123456",
                    "CHECKING",
                    "BANK",
                ),
            ),
            parsed.rows,
        )
    }

    @Test
    fun everyStatementOfTheFileIsStaged() {
        val ofx =
            "<OFX><CREDITCARDMSGSRSV1>" +
                "<CCSTMTTRNRS><CCSTMTRS><CURDEF>GBP</CURDEF><CCACCTFROM><ACCTID>1111</ACCTID></CCACCTFROM><BANKTRANLIST>" +
                "<STMTTRN><DTPOSTED>20190722000000</DTPOSTED><TRNAMT>-1.00</TRNAMT><NAME>A</NAME></STMTTRN>" +
                "</BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS>" +
                "<CCSTMTTRNRS><CCSTMTRS><CURDEF>EUR</CURDEF><CCACCTFROM><ACCTID>2222</ACCTID></CCACCTFROM><BANKTRANLIST>" +
                "<STMTTRN><DTPOSTED>20190723</DTPOSTED><TRNAMT>-2.00</TRNAMT><NAME>B</NAME></STMTTRN>" +
                "</BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS>" +
                "</CREDITCARDMSGSRSV1></OFX>"

        val rows = parseOfxAsCsv(ofx.encodeToByteArray()).rows

        assertEquals(listOf("1111", "2222"), rows.map { it[column(OfxColumns.COL_ACCOUNT_ID)] })
        assertEquals(listOf("GBP", "EUR"), rows.map { it[column(OfxColumns.COL_CURRENCY)] })
        assertEquals(listOf("00:00:00", ""), rows.map { it[column(OfxColumns.COL_TIME)] })
        assertEquals(listOf("CREDITCARD", "CREDITCARD"), rows.map { it[column(OfxColumns.COL_ACCOUNT_TYPE)] })
    }

    @Test
    fun onlyDocumentFormatsParseAsDocuments() {
        assertEquals(OfxColumns.headers, parseDocumentAsCsv(ImportFileKind.OFX, "<OFX></OFX>".encodeToByteArray())?.headers)
        assertNull(parseDocumentAsCsv(ImportFileKind.CSV, "a,b".encodeToByteArray()))
    }
}
