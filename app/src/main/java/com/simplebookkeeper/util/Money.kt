package com.simplebookkeeper.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额工具 —— 统一"元"与"分"的换算。
 *
 * 全项目金额一律以「分」为单位的 Long 存储与汇总（见 Transaction.amount /
 * Saving.amount），只有输入和展示才用「元」。这里集中处理换算，避免各处
 * 出现 `(x * 100).toLong()` 这类浮点写法。
 *
 * 为什么不能用浮点：19.99 * 100 在 IEEE-754 下等于 1998.9999999999998，
 * 强转 Long 会得到 1998 —— 记账类应用静默少记 1 分是硬伤。
 */
object Money {

    /** 金额小数位上限（与输入框正则保持一致） */
    const val MAX_DECIMALS = 2

    /**
     * 解析用户输入的金额（单位：元）→ 分。
     *
     * 纯字符串解析，不经过 Double：
     * - "19.99" -> 1999
     * - "1.15"  -> 115
     * - "8"    -> 800
     * - "0.005" -> 1（超出 2 位小数时按四舍五入取整，与财务习惯一致）
     *
     * @return 分；输入非法（空、非数字、负数）时返回 null
     */
    fun yuanToFen(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 只接受数字与至多一个小数点，杜绝 "1e3"、"abc"、"1.2.3" 等
        if (!Regex("^\\d*(\\.\\d*)?$").matches(trimmed)) return null
        if (trimmed.isEmpty() || trimmed == ".") return null

        return try {
            val fen = BigDecimal(trimmed)
                .setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
                .movePointRight(MAX_DECIMALS)
            // 必须用 longValueExact()：BigDecimal.toLong() 对溢出是静默取低 64 位，
            // 不会抛异常，会把 "99999999999999999999" 变成一个看似正常的脏金额
            fen.longValueExact()
        } catch (_: Exception) {
            // 超出 Long 范围（用户误输入超长数字）
            null
        }
    }

    /** 分 → "元" 的字符串，最多 2 位小数（整数不补 .00，用于输入框回填） */
    fun fenToYuanText(fen: Long): String {
        val yuan = BigDecimal.valueOf(fen, MAX_DECIMALS)
        val stripped = yuan.stripTrailingZeros()
        // stripTrailingZeros 会把 100 变成 1E+2，这里还原成普通写法
        return if (stripped.scale() < 0) stripped.setScale(0).toPlainString()
        else stripped.toPlainString()
    }

    /** 分 → 展示用字符串，固定 2 位小数 */
    fun formatFen(fen: Long): String =
        String.format("%.2f", fen / 100.0)
}
