package com.simplebookkeeper.data

import com.simplebookkeeper.data.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 往返健壮性回归测试。
 *
 * DataExporter 的 CSV 工具方法声明为 internal，单元测试源集是主源集的
 * friend module，可直接调用，无需反射。
 */
class DataExporterCsvTest {

    private val header =
        "id,type,amount,categoryId,paymentMethod,customPaymentMethod,note,date,createdAt,updatedAt"

    // ── 旧缺陷：备注含换行时整份 CSV 错行 ──────────────────────────────

    @Test
    fun testRoundTrip_noteWithNewline_shouldStayOneRecord() {
        val trickyNote = "第一行\n第二行, 带逗号\n第三行 \"带引号\""
        val escaped = DataExporter.escapeCsv(trickyNote)
        assertTrue("含换行/逗号/引号的字段必须被引号包裹", escaped.startsWith("\""))

        val csv = buildString {
            appendLine(header)
            appendLine("1,EXPENSE,1999,3,CASH,,$escaped,1700000000000,1700000000000,1700000000000")
            appendLine("2,INCOME,500,4,WECHAT,,正常备注,1700000001000,1700000001000,1700000001000")
        }

        val rows = DataExporter.parseCsv(csv)
        assertEquals("3 行记录必须仍是 3 行（含表头）", 3, rows.size)

        val txs = DataExporter.parseTransactionsCsv(csv)
        assertEquals("含换行备注不应导致记录被拆开", 2, txs.size)
    }

    @Test
    fun testRoundTrip_noteWithNewline_shouldPreserveExactContent() {
        val trickyNote = "第一行\n第二行, 带逗号\n第三行 \"带引号\""
        val csv = buildString {
            appendLine(header)
            appendLine("1,EXPENSE,1999,3,CASH,,${DataExporter.escapeCsv(trickyNote)},1700000000000,1700000000000,1700000000000")
        }
        val txs = DataExporter.parseTransactionsCsv(csv)
        assertEquals(1, txs.size)
        assertEquals("备注内容必须逐字还原", trickyNote, txs[0].note)
    }

    @Test
    fun testRoundTrip_multipleRecordsWithNewlines() {
        val csv = buildString {
            appendLine(header)
            appendLine("1,EXPENSE,100,1,CASH,,\"a\nb\",1000,1000,1000")
            appendLine("2,EXPENSE,200,1,CASH,,\"c\nd\",2000,2000,2000")
            appendLine("3,EXPENSE,300,1,CASH,,\"e\nf\",3000,3000,3000")
        }
        val rows = DataExporter.parseCsv(csv)
        assertEquals(4, rows.size)
        val txs = DataExporter.parseTransactionsCsv(csv)
        assertEquals(3, txs.size)
    }

    @Test
    fun testParseCsv_escapedQuotesAndCommas() {
        val csv = "a,b,c\n" +
            "1,\"含,逗号\",\"含\"\"引号\"\"\"\n"
        val rows = DataExporter.parseCsv(csv)
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "含,逗号", "含\"引号\""), rows[1])
    }

    @Test
    fun testParseCsv_noTrailingNewline() {
        val rows = DataExporter.parseCsv("a,b\n1,2")
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), rows[1])
    }

    @Test
    fun testParseCsv_crlfLineEndings() {
        val rows = DataExporter.parseCsv("a,b\r\n1,2\r\n3,4\r\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("3", "4"), rows[2])
    }

    @Test
    fun testParseCsv_emptyInput() {
        assertTrue(DataExporter.parseCsv("").isEmpty())
    }

    @Test
    fun testParseCsv_blankLinesIgnored() {
        val rows = DataExporter.parseCsv("a,b\n\n1,2\n\n\n3,4\n")
        assertEquals(3, rows.size)
    }

    // ── 旧缺陷：9 列旧格式记录被整条静默丢弃 ────────────────────────────

    @Test
    fun testParseTransactions_legacyNineColumns_shouldNotBeDropped() {
        // 早期导出格式缺少 updatedAt 列
        val csv = header + "\n" +
            "1,EXPENSE,1999,3,CASH,,备注,1700000000000,1700000000000\n"
        val txs: List<Transaction> = DataExporter.parseTransactionsCsv(csv)
        assertEquals("9 列旧格式记录不应被静默丢弃", 1, txs.size)
        assertEquals("updatedAt 应回落到 createdAt", txs[0].createdAt, txs[0].updatedAt)
    }

    @Test
    fun testParseTransactions_tooFewColumns_shouldBeSkippedNotCrash() {
        val csv = header + "\n" +
            "1,EXPENSE,1999\n"
        val txs = DataExporter.parseTransactionsCsv(csv)
        assertTrue("字段严重不足的行应被跳过", txs.isEmpty())
    }

    @Test
    fun testParseTransactions_blankLinesIgnored() {
        val csv = header + "\n\n" +
            "1,EXPENSE,100,1,CASH,,a,1000,1000,1000\n\n" +
            "\n" +
            "2,EXPENSE,200,1,CASH,,b,2000,2000,2000\n"
        val txs = DataExporter.parseTransactionsCsv(csv)
        assertEquals(2, txs.size)
    }

    @Test
    fun testParseTransactions_invalidAmountRowSkippedOthersKept() {
        val csv = header + "\n" +
            "1,EXPENSE,NOT_A_NUMBER,1,CASH,,坏行,1000,1000,1000\n" +
            "2,EXPENSE,200,1,CASH,,好行,2000,2000,2000\n"
        val txs = DataExporter.parseTransactionsCsv(csv)
        assertEquals("坏行应被跳过，好行仍要保留", 1, txs.size)
        assertEquals("好行", txs[0].note)
    }
}
