package com.kingzcheung.xime.model

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 索引演进兼容性：新增字段不能让**不认识这些字段的老客户端**解析失败。
 *
 * 这里用一组只声明旧字段的数据类模拟老版本客户端（它没有 platforms / tags /
 * warning / currentVersion，也没有任何未知字段的兜底），直接走 kaml 的
 * `strictMode = false` 解码路径，验证新索引仍能完整解析。
 */
class LegacyIndexCompatTest {

    @Serializable
    data class LegacyFile(val name: String, val url: String = "")

    @Serializable
    data class LegacyVersion(
        val version: String? = null,
        val name: String? = null,
        val date: String = "",
        val files: List<LegacyFile> = emptyList(),
    )

    @Serializable
    data class LegacyModel(
        val id: String,
        val name: String,
        val category: String = "other",
        val size: String = "",
        val versions: List<LegacyVersion> = emptyList(),
    )

    @Serializable
    data class LegacyRoot(val models: List<LegacyModel> = emptyList())

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Test
    fun `legacy client still parses entry carrying new fields`() {
        val text = """
            models:
              - id: qwen3-asr-0.6b
                name: Qwen3-ASR 0.6B
                description: 桌面端专用
                category: asr
                size: 1.75 GB
                tags:
                  - 语音
                  - 大模型
                platforms:
                  - macos
                  - windows
                  - linux
                warning: 仅桌面端
                currentVersion: v1.0
                extra:
                  nested:
                    deep: true
                  ratio: 0.75
                  enabled: false
                  empty:
                versions:
                  - version: "v1.0"
                    date: "2026-11-18"
                    files:
                      - name: config.json
                        url: "https://example.com/config.json"
        """.trimIndent()

        val root = yaml.decodeFromString(LegacyRoot.serializer(), text)

        assertEquals(1, root.models.size)
        assertEquals("qwen3-asr-0.6b", root.models.single().id)
        assertEquals("1.75 GB", root.models.single().size)
        assertEquals(1, root.models.single().versions.single().files.size)
    }
}
