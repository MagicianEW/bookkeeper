package com.simplebookkeeper.util

import java.util.Calendar
import java.util.Date

/**
 * 日期换算工具 —— 统一 Material3 DatePicker（UTC 语义）与本地时间之间的转换。
 *
 * 背景：Compose Material3 的 DatePickerState.selectedDateMillis 是 **UTC 零点**
 * 语义（例如 2026-09-30 对应 2026-09-30T00:00:00Z），而账本数据按本地时间
 * 归月（DAO 里用 strftime(..., 'localtime')）。直接把 millis 当本地时间用，
 * 在东八区会把记录挪到当天 08:00、在美区会整日偏移一天。
 */
object DateTimeUtils {

    /**
     * DatePicker 返回的 millis（UTC 零点）→ 本地当天 00:00:00.000
     *
     * 这是取"用户选了哪一天"的正确姿势：不依赖运行环境时区偏移。
     */
    fun utcPickerMillisToLocalDate(millis: Long): Date {
        val utc = Calendar.getInstance(UTC).apply { timeInMillis = millis }
        return localDate(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 本地某一天 → 传给 DatePicker 的 millis（UTC 零点）
     *
     * 反向操作，保证"打开选择器时高亮的就是当前显示的日期"。
     */
    fun localDateToUtcPickerMillis(date: Date): Long {
        val local = Calendar.getInstance().apply { time = date }
        val utc = Calendar.getInstance(UTC).apply {
            clear()
            set(
                local.get(Calendar.YEAR),
                local.get(Calendar.MONTH),
                local.get(Calendar.DAY_OF_MONTH),
                0, 0, 0
            )
        }
        return utc.timeInMillis
    }

    /** 本地日期 → 当天 00:00:00.000 的毫秒数（用作搜索/统计的区间起点） */
    fun toStartOfDayMillis(date: Date): Long =
        localDate(
            cal(date).get(Calendar.YEAR),
            cal(date).get(Calendar.MONTH),
            cal(date).get(Calendar.DAY_OF_MONTH)
        ).time

    /** 本地日期 → 当天 23:59:59.999 的毫秒数（用作区间终点，含当天全部记录） */
    fun toEndOfDayMillis(date: Date): Long = toStartOfDayMillis(date) + 86_399_999L

    private fun localDate(year: Int, month: Int, dayOfMonth: Int): Date =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, 0, 0, 0)
        }.time

    private fun cal(date: Date): Calendar = Calendar.getInstance().apply { time = date }

    private val UTC: java.util.TimeZone = java.util.TimeZone.getTimeZone("UTC")
}
