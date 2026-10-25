package com.moneymanager.csvimporter

import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.rules.CaptureTarget
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PdfRowAssemblerTest {
    private fun capture(
        pattern: String,
        target: CaptureTarget,
        vararg more: RuleAction,
        conditions: List<Condition> = emptyList(),
    ) = StrategyRule(
        conditions = conditions,
        actions = listOf(RuleAction.Capture(PdfLineConfig.TEXT, pattern, target)) + more,
    )

    private fun lines(vararg texts: String) = texts.mapIndexed { index, text -> PdfTextLine(1, index + 1, text) }

    private val statement =
        PdfLineConfig(
            listOf(
                capture("""Statement date (?<statementDate>\d\d/\d\d/\d\d)""", CaptureTarget.DOCUMENT),
                capture(
                    """^(?<mon>[A-Z][a-z]{2}) (?<day>\d{1,2}) {2,}(?<description>.+?) {2,}(?<amount>[\d,]+\.\d\d)$""",
                    CaptureTarget.NEW_ROW,
                    RuleAction.SetValue("date", RuleValue.Template("{day} {mon}")),
                ),
                capture("""^(?<credit>CR)$""", CaptureTarget.ROW),
            ),
        )

    @Test
    fun `rows start and continue and carry the document values`() {
        val staged =
            PdfRowAssembler(statement).assemble(
                lines(
                    "Membership Rewards",
                    "Statement date 19/01/13",
                    "Dec 20   TESCO STORES   12.50",
                    "Jan 2   PAYMENT RECEIVED - THANK YOU   2,609.36",
                    "CR",
                ),
            )
        assertEquals(
            listOf("page", "line", "text", "statementDate", "mon", "day", "description", "amount", "date", "credit"),
            staged.headers,
        )
        assertEquals(
            listOf(
                listOf("1", "3", "Dec 20   TESCO STORES   12.50", "19/01/13", "Dec", "20", "TESCO STORES", "12.50", "20 Dec", ""),
                listOf(
                    "1",
                    "4",
                    "Jan 2   PAYMENT RECEIVED - THANK YOU   2,609.36",
                    "19/01/13",
                    "Jan",
                    "2",
                    "PAYMENT RECEIVED - THANK YOU",
                    "2,609.36",
                    "2 Jan",
                    "CR",
                ),
            ),
            staged.rows,
        )
    }

    @Test
    fun `a document value captured after a row still fills it`() {
        val staged = PdfRowAssembler(statement).assemble(lines("Dec 20   TESCO   1.00", "Statement date 19/01/13"))
        assertEquals("19/01/13", staged.rows.single()[staged.headers.indexOf("statementDate")])
    }

    @Test
    fun `a continuation before any row and a skipped line are dropped`() {
        val config =
            PdfLineConfig(
                listOf(
                    StrategyRule(
                        conditions = listOf(Condition(PdfLineConfig.TEXT, ConditionOp.MATCHES, "Balance")),
                        actions = listOf(RuleAction.Skip),
                    ),
                ) + statement.rules,
            )
        val staged = PdfRowAssembler(config).assemble(lines("CR", "Jan 2   Balance brought   1.00", "Jan 3   CAFE   2.00"))
        assertEquals(listOf("CAFE"), staged.rows.map { it[staged.headers.indexOf("description")] })
        assertEquals("", staged.rows.single()[staged.headers.indexOf("credit")])
    }

    @Test
    fun `identical lines stay separate rows`() {
        val staged = PdfRowAssembler(statement).assemble(lines("Jan 3   CAFE   2.00", "Jan 3   CAFE   2.00"))
        assertEquals(2, staged.rows.size)
        assertEquals(listOf("1", "2"), staged.rows.map { it[1] })
    }

    @Test
    fun `raw lines stage one row per line`() {
        val staged = PdfRowAssembler.rawLines(listOf(PdfTextLine(2, 7, "hello")))
        assertEquals(PdfLineConfig.BASE_COLUMNS, staged.headers)
        assertEquals(listOf(listOf("2", "7", "hello")), staged.rows)
    }

    @Test
    fun `the rules hash changes with the rules`() =
        runTest {
            assertEquals(PdfRowAssembler.rulesHash(statement), PdfRowAssembler.rulesHash(statement.copy()))
            assertNotEquals(PdfRowAssembler.rulesHash(statement), PdfRowAssembler.rulesHash(PdfLineConfig(statement.rules.drop(1))))
        }
}
