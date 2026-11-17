package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.io.File
import java.util.Locale

data class DictEntry(
    val word: String,
    val code: String,
    val weight: Int? = null
)

object DictionaryHelper {
    private const val TAG = "DictionaryHelper"

    /** 解析一个 .dict.yaml 文本里 `...` 之后的词条（`词<TAB>码`，也容忍空格分隔）。纯函数。 */
    fun parseDictEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        var inData = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!inData) {
                if (line == "...") inData = true
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            // 优先按 Tab 切：拼音码本身含空格（如 "ni hao"），不能再按空格拆。
            val tabParts = line.split('\t')
            val parts = if (tabParts.size >= 2) {
                tabParts
            } else {
                line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            if (parts.size >= 2) {
                val weight = if (tabParts.size >= 3) tabParts[2].toIntOrNull() else null
                out.add(DictEntry(parts[0], parts[1], weight))
            }
        }
        return out
    }

    /**
     * 解析文本码表（`词<TAB>码[<TAB>频率]`，无 `...` 分隔符、无头部要求）。
     * 来源有两处，格式一致：librime 用户词库（userdb）读出的码表文本、
     * 以及 custom_phrase 等 stabledb 文本表。纯函数。
     */
    fun parseCodeTable(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            }
        }
        return out
    }

    /** 解析 .dict.yaml 头部的 `import_tables`（块式 `- x` 或内联 `[a, b]`）。纯函数。 */
    fun parseImportTables(text: String): List<String> {
        val tables = linkedSetOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line == "...") break // 头部结束，后面是词条
            if (line.startsWith("import_tables:")) {
                val inline = line.substringAfter(":").trim()
                if (inline.startsWith("[")) {
                    inline.trim('[', ']').split(",")
                        .map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                        .forEach { tables.add(it) }
                } else {
                    var j = i + 1
                    while (j < lines.size && lines[j].trim().startsWith("- ")) {
                        tables.add(lines[j].trim().removePrefix("- ").trim().trim('"'))
                        j++
                    }
                    i = j - 1
                }
            }
            i++
        }
        return tables.toList()
    }

    /**
     * 跟随 `import_tables` 递归收集词条（注入读取器，便于单测；按表名去重防环）。
     * 修复"主词典靠 import_tables 组装时(如 quick5/cangjie5)词库查看器为空"。
     * [extraRoots] 用于方案声明的个人词库（`translator.packs`），与主词典同样递归展开。
     */
    fun collectEntries(
        rootDict: String,
        extraRoots: List<String> = emptyList(),
        readDict: (String) -> String?,
    ): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(rootDict) + extraRoots)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            val text = readDict(name) ?: continue
            out.addAll(parseDictEntries(text))
            for (t in parseImportTables(text)) if (t !in seen) queue.addLast(t)
        }
        return out
    }

    /**
     * 从方案 `.schema.yaml` 读取 `translator.packs` 中声明的个人词库名（如 pinyin_simp 的
     * `user_simp`），没有则返回空。这些表同样是方案里的静态码表，与主词典一并展示。
     */
    internal fun readSchemaPacks(rimeDir: File, schemaId: String): List<String> {
        val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return emptyList()
        val text = try {
            schemaFile.readText(Charsets.UTF_8).trimStart('\uFEFF')
        } catch (_: Exception) {
            return emptyList()
        }
        // kaml 解析（schema.yaml 是 YAML，避免脆弱的正则）；
        // 第三方方案的 .schema.yaml 可能含锚点/别名等非严格语法导致解析失败，此时回退正则。
        val viaKaml = readSchemaPacksKaml(text)
        if (viaKaml != null) return viaKaml
        return readSchemaPacksRegex(text)
    }

    private fun readSchemaPacksKaml(text: String): List<String>? {
        return try {
            val root = SchemaManager.yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
            val packs = (root["translator"] as? YamlMap)?.get("packs") as? YamlList ?: return emptyList()
            packs.items.mapNotNull { (it as? YamlScalar)?.content }
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSchemaPacksRegex(text: String): List<String> {
        return try {
            val block = Regex("""^translator:[\s\S]*?^  packs:\s*\n([\s\S]*?)(?=^\S|\Z)""", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1) ?: return emptyList()
            Regex("""^\s*-\s*"?(\w+)"?""", RegexOption.MULTILINE).findAll(block)
                .map { it.groupValues[1] }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadDictionary(context: Context, schemaId: String): List<DictEntry> {
        val dictName = SchemaManager.getReferencedDictName(context, schemaId) ?: schemaId
        val dir = SchemaManager.getRimeDir(context)
        return try {
            collectEntries(dictName, readSchemaPacks(dir, schemaId)) { name ->
                val f = File(dir, "$name.dict.yaml")
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary for $schemaId", e)
            emptyList()
        }
    }

    fun searchDictionary(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries.take(100)
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) || it.code.contains(query) || it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }.take(100)
    }
}
