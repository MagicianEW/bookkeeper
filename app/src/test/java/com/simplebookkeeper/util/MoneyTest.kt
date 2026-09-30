package com.simplebookkeeper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun testYuanToFen_commonValues() {
        assertEquals(800L, Money.yuanToFen("8"))
        assertEquals(800L, Money.yuanToFen("8.0"))
        assertEquals(800L, Money.yuanToFen("8.00"))
        assertEquals(1999L, Money.yuanToFen("19.99"))
    }

    @Test
    fun testYuanToFen_shouldNotLoseOneCent() {
        // 旧实现 (x * 100).toLong() 在这些值上会少记 1 分
        assertEquals(1999L, Money.yuanToFen("19.99"))
        assertEquals(115L, Money.yuanToFen("1.15"))
        assertEquals(29L, Money.yuanToFen("0.29"))
        assertEquals(1234L, Money.yuanToFen("12.34"))
        assertEquals(7L, Money.yuanToFen("0.07"))
        assertEquals(556L, Money.yuanToFen("5.56"))
        assertEquals(1899L, Money.yuanToFen("18.99"))
        assertEquals(2999L, Money.yuanToFen("29.99"))
    }

    @Test
    fun testYuanToFen_shouldRoundHalfUpBeyondTwoDecimals() {
        assertEquals(268L, Money.yuanToFen("2.675"))
        assertEquals(1L, Money.yuanToFen("0.005"))
        assertEquals(0L, Money.yuanToFen("0.004"))
    }

    @Test
    fun testYuanToFen_shouldAcceptSurroundingSpaces() {
        assertEquals(1999L, Money.yuanToFen("  19.99 "))
    }

    @Test
    fun testYuanToFen_invalidInput_shouldReturnNull() {
        assertNull(Money.yuanToFen(""))
        assertNull(Money.yuanToFen("   "))
        assertNull(Money.yuanToFen("."))
        assertNull(Money.yuanToFen("abc"))
        assertNull(Money.yuanToFen("1.2.3"))
        assertNull(Money.yuanToFen("-5"))
        assertNull(Money.yuanToFen("1e3"))
        assertNull(Money.yuanToFen("99999999999999999999"))
    }

    @Test
    fun testFenToYuanText_roundTrip() {
        // 输入框回填场景：整数不带小数，非整数完整保留
        assertEquals("19.99", Money.fenToYuanText(1999))
        assertEquals("8", Money.fenToYuanText(800))
        assertEquals("0.07", Money.fenToYuanText(7))
        assertEquals("1.15", Money.fenToYuanText(115))
        assertEquals("0", Money.fenToYuanText(0))
    }

    @Test
    fun testYuanToFen_thenFenToYuanText_shouldRoundTrip() {
        val inputs = listOf("0.01", "0.29", "1.15", "8", "19.99", "123.45", "9999.99")
        for (input in inputs) {
            val fen = Money.yuanToFen(input)!!
            val back = Money.fenToYuanText(fen)
            assertEquals("往返不一致: $input -> $fen -> $back", input, back)
        }
    }
}
