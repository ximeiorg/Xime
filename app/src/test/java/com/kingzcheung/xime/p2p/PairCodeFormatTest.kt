package com.kingzcheung.xime.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配对码展示：原生 `code_text()` 返回的就是分组后的 `482 719`，界面若再按 3 位
 * 切一次会把空格算进分组，显示成 `482  71 9`（就是真机上看到的「中间多一个空」）。
 */
class PairCodeFormatTest {

    @Test
    fun `原生已分组的码原样保留`() {
        assertEquals("482 719", formatPairCode("482 719"))
    }

    @Test
    fun `纯数字与带连字符都归一成分组形式`() {
        assertEquals("482 719", formatPairCode("482719"))
        assertEquals("482 719", formatPairCode("482-719"))
        assertEquals("482 719", formatPairCode(" 482 719 "))
    }

    @Test
    fun `不足六位不补零`() {
        assertEquals("482", formatPairCode("482"))
    }

    @Test
    fun `空码用占位符`() {
        assertEquals("——— ———", formatPairCode(null))
        assertEquals("——— ———", formatPairCode(""))
        assertEquals("——— ———", formatPairCode("  "))
    }
}
