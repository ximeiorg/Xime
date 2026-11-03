package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextSchemaIdTest {

    private val schemas = listOf("rime_ice", "double_pinyin", "handwriting")

    @Test
    fun `按列表顺序取下一个`() {
        assertEquals("double_pinyin", nextSchemaId(schemas, "rime_ice"))
        assertEquals("handwriting", nextSchemaId(schemas, "double_pinyin"))
    }

    @Test
    fun `末尾回到首个`() {
        assertEquals("rime_ice", nextSchemaId(schemas, "handwriting"))
    }

    @Test
    fun `当前方案不在列表时取首个`() {
        assertEquals("rime_ice", nextSchemaId(schemas, "removed_schema"))
        assertEquals("rime_ice", nextSchemaId(schemas, ""))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(nextSchemaId(emptyList(), "rime_ice"))
    }
}
