package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 浏览器上传白名单：与网页 accept 列表保持同一份清单（issue #1333 的 .gram 曾被漏掉）。 */
class ImportManagerUploadTest {

    @Test
    fun `grammar model is accepted`() {
        assertTrue(ImportManager.isUploadSupported("wanxiang-lts-zh-hans.gram"))
        assertTrue(ImportManager.isUploadSupported("WANXIANG.GRAM"))
    }

    @Test
    fun `scheme package lexicon plugin resource are accepted`() {
        listOf(
            "xime.custom.yaml",
            "wanxiang.schema.yaml",
            "base.dict.yaml",
            "rime-wanxiang-base.zip",
            "rime-ice.tar.gz",
            "rime-ice.tgz",
            "demo.xipk",
            "ciku.scel",
            "ciku.bdict",
            "english_trie.bin",
            "ciku.dict",
            "custom_phrase.txt",
            "bg.jpg",
            "bg.png",
            "font.ttf",
            "font.woff2",
        ).forEach { assertTrue(it, ImportManager.isUploadSupported(it)) }
    }

    @Test
    fun `unrelated types are rejected`() {
        listOf("app.apk", "xime.db", "notes.md", "noext", "a.gram.zipx").forEach {
            assertFalse(it, ImportManager.isUploadSupported(it))
        }
    }
}
