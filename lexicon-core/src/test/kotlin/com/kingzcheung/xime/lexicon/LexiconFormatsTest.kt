package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconFormatsTest {

    @Test
    fun `resolves scel case insensitively`() {
        assertEquals("sogou-scel", LexiconFormats.findByFileName("a.scel")?.id)
        assertEquals("sogou-scel", LexiconFormats.findByFileName("A.SCEL")?.id)
    }

    @Test
    fun `does not claim rime dictionary or unknown files`() {
        assertNull(LexiconFormats.findByFileName("wubi86.dict.yaml"))
        assertNull(LexiconFormats.findByFileName("something.txt"))
    }

    @Test
    fun `supported description lists registered formats`() {
        assertTrue(LexiconFormats.supportedDescription().contains("搜狗细胞词库"))
        assertTrue(LexiconFormats.registered().any { it.id == "sogou-scel" })
    }
}
