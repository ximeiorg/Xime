package com.kingzcheung.xime.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 三类文件的判定 + 方案发现：装/卸/切换都依赖这套规则。 */
class SchemeFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `用户数据白名单`() {
        assertTrue(SchemeFiles.isUserData("xime.custom.yaml"))
        assertTrue(SchemeFiles.isUserData("themes/bg.png"))
        assertTrue(SchemeFiles.isUserData("fonts/custom.ttf"))
        assertTrue(SchemeFiles.isUserData("custom_phrase.txt"))
        assertTrue(SchemeFiles.isUserData("custom_phrase_double.txt"))
        // 导入词库的派生词典跟着 SQLite 记录走，换包必须保留
        assertTrue(SchemeFiles.isUserData("xime_import_1a2b3c4d.dict.yaml"))
        // 目录本身的判定（搬运整天目录时按相对路径传入）
        assertTrue(SchemeFiles.isUserData("themes"))
        assertTrue(SchemeFiles.isUserData("fonts"))
    }

    @Test
    fun `白名单之外的一律不算用户数据`() {
        assertFalse(SchemeFiles.isUserData("default.custom.yaml"))
        assertFalse(SchemeFiles.isUserData("pinyin_simp.custom.yaml"))
        assertFalse(SchemeFiles.isUserData("wubi86.schema.yaml"))
        assertFalse(SchemeFiles.isUserData("userdb/pinyin_simp.userdb"))
        assertFalse(SchemeFiles.isUserData("installation.yaml"))
        // 子目录里的同名文件不是"根级短语表"，不享受保留
        assertFalse(SchemeFiles.isUserData("sub/custom_phrase.txt"))
        assertFalse(SchemeFiles.isUserData("sub/xime_import_1a2b3c4d.dict.yaml"))
        assertFalse(SchemeFiles.isUserData("xime_import_1a2b3c4d.dict.yaml.bak"))
    }

    @Test
    fun `引擎产物与包内容的判定`() {
        assertTrue(SchemeFiles.isRuntime("build/wubi86.prism.bin"))
        assertTrue(SchemeFiles.isRuntime("build"))
        assertFalse(SchemeFiles.isRuntime("wubi86.schema.yaml"))
        assertTrue(SchemeFiles.isPackageContent("default.yaml"))
        assertTrue(SchemeFiles.isPackageContent("cn_dicts/base.dict.yaml"))
        assertFalse(SchemeFiles.isPackageContent("xime.custom.yaml"))
        assertFalse(SchemeFiles.isPackageContent("xime_import_1a2b3c4d.dict.yaml"))
        assertFalse(SchemeFiles.isPackageContent("build/wubi86.table.bin"))
    }

    @Test
    fun `只把根目录的 schema yaml 当方案`() {
        val rime = temp.newFolder("rime")
        File(rime, "wubi86.schema.yaml").writeText("schema:")
        File(rime, "t9_pinyin.schema.yaml").writeText("schema:")
        File(rime, "build").mkdirs()
        File(rime, "build/wubi86.schema.yaml").writeText("built artifact")

        assertEquals(
            listOf("t9_pinyin", "wubi86"),
            SchemeFiles.installedSchemaIds(rime),
        )
    }

    @Test
    fun `目录不存在时没有方案`() {
        assertEquals(emptyList<String>(), SchemeFiles.installedSchemaIds(File(temp.root, "missing")))
    }
}
