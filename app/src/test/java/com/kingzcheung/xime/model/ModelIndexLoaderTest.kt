package com.kingzcheung.xime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelIndexLoaderTest {

    @Test
    fun `parses multiple versions from models index`() {
        val yaml = """
            models:
              - id: ochwpro
                name: 手写识别
                description: 本地手写模型
                category: handwriting
                size: 20 MB
                versions:
                  - version: "2.0"
                    date: "2026-07-01"
                    changelog: 更快更准
                    size: 22 MB
                    archive:
                      url: "https://example.com/ochwpro-2.0.tar.bz2"
                    files:
                      - name: ochwpro.onnx
                        url: "https://example.com/ochwpro-2.0.onnx"
                  - version: "1.0"
                    date: "2025-12-01"
                    size: 20 MB
                    files:
                      - name: ochwpro.onnx
                        url: "https://example.com/ochwpro-1.0.onnx"
                      - name: char_index.json
                        url: "https://example.com/char_index-1.0.json"
        """.trimIndent()

        val models = ModelIndexLoader.parseModelsIndex(yaml)

        assertEquals(1, models.size)
        val model = models.first()
        assertEquals("ochwpro", model.id)
        assertEquals(ModelCategory.HANDWRITING, model.category)

        assertEquals(2, model.versions.size)
        val v2 = model.versions[0]
        assertEquals("2.0", v2.version)
        assertEquals("2026-07-01", v2.date)
        assertEquals("更快更准", v2.changelog)
        assertEquals("22 MB", v2.size)
        assertEquals("https://example.com/ochwpro-2.0.tar.bz2", v2.archiveUrl)
        assertEquals(1, v2.files.size)

        val v1 = model.versions[1]
        assertEquals("1.0", v1.version)
        assertNull(v1.archiveUrl)
        assertEquals(2, v1.files.size)
        assertEquals("char_index.json", v1.files[1].name)
    }

    @Test
    fun `resolvedVersion returns the first version`() {
        val yaml = """
            models:
              - id: predictive-text-small
                name: 智能预测
                category: prediction
                versions:
                  - version: "1.1"
                    files:
                      - name: vocab.json
                        url: "https://example.com/vocab.json"
                  - version: "1.0"
                    files:
                      - name: vocab.json
                        url: "https://example.com/vocab-1.0.json"
        """.trimIndent()

        val model = ModelIndexLoader.parseModelsIndex(yaml).first()

        assertEquals("1.1", model.resolvedVersion()?.version)
        assertEquals("1.1", model.versions.first().version)
    }

    @Test
    fun `skips entries without versions`() {
        val yaml = """
            models:
              - id: broken
                name: 无效条目
                category: other
              - id: valid
                name: 有效
                category: other
                versions:
                  - version: "1.0"
                    files:
                      - name: a.bin
                        url: "https://example.com/a.bin"
        """.trimIndent()

        val models = ModelIndexLoader.parseModelsIndex(yaml)

        assertEquals(1, models.size)
        assertEquals("valid", models.first().id)
    }

    /** 索引新增字段（platforms 等）不能让旧客户端解析失败。 */
    @Test
    fun `ignores unknown fields such as platforms`() {
        val yaml = """
            models:
              - id: qwen3-asr-0.6b
                name: Qwen3-ASR 0.6B
                description: 桌面端专用
                category: asr
                size: 1.75 GB
                tags:
                  - 语音
                platforms:
                  - macos
                  - windows
                  - linux
                warning: 仅桌面端
                currentVersion: v1.0
                versions:
                  - version: "v1.0"
                    date: "2026-11-18"
                    files:
                      - name: config.json
                        url: "https://example.com/config.json"
        """.trimIndent()

        val models = ModelIndexLoader.parseModelsIndex(yaml)

        assertEquals(1, models.size)
        val model = models.first()
        assertEquals("qwen3-asr-0.6b", model.id)
        assertEquals(ModelCategory.ASR, model.category)
        assertEquals("1.75 GB", model.size)
    }
}
