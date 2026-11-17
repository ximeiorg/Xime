package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgramTrieTest {

    @Test
    fun `insert with count equals repeated insert`() {
        val once = NgramTrie().apply { insert(listOf("a", "b"), 3) }
        val repeated = NgramTrie().apply { repeat(3) { insert(listOf("a", "b")) } }

        assertEquals(repeated.getFrequency(listOf("a", "b")), once.getFrequency(listOf("a", "b")), 1e-6f)
        assertEquals(repeated.getAllEntries(), once.getAllEntries())

        val mixed = NgramTrie().apply {
            insert(listOf("a", "b"), 3)
            insert(listOf("a", "c"), 1)
        }
        assertEquals(4, mixed.size()) // size() 统计的是累计次数
    }

    @Test
    fun `zero or negative count is ignored`() {
        val trie = NgramTrie()
        trie.insert(listOf("a", "b"), 0)
        assertEquals(0, trie.size())
        assertTrue(trie.getAllEntries().isEmpty())
    }
}
