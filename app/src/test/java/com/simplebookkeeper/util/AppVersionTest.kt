package com.simplebookkeeper.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {

    @Test
    fun testFormat_shouldNotDoublePrefixV() {
        // 回归用例：versionName 本身已带 v 前缀，再拼一个会显示成 "vv0.4.7"
        assertEquals("v0.4.7", AppVersion.format("v0.4.7"))
    }

    @Test
    fun testFormat_shouldAddMissingPrefix() {
        assertEquals("v0.4.7", AppVersion.format("0.4.7"))
    }

    @Test
    fun testFormat_shouldKeepSinglePrefixForOtherCases() {
        assertEquals("v1.0", AppVersion.format("v1.0"))
        assertEquals("v10.2.33", AppVersion.format("v10.2.33"))
    }

    @Test
    fun testFormat_shouldTrimWhitespace() {
        assertEquals("v0.4.7", AppVersion.format("  v0.4.7  "))
        assertEquals("v0.4.7", AppVersion.format(" 0.4.7 "))
    }

    @Test
    fun testFormat_emptyOrNull_shouldReturnEmpty() {
        assertEquals("", AppVersion.format(null))
        assertEquals("", AppVersion.format(""))
        assertEquals("", AppVersion.format("   "))
    }
}
