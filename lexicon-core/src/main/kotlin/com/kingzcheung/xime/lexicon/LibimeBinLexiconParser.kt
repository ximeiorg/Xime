package com.kingzcheung.xime.lexicon

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Fcitx5 / libime 二进制拼音词库（`.dict`）解析器。
 *
 * 文件布局（libime `pinyin/pinyindictionary.cpp`）：
 * - 0x00: Big-Endian Int32 magic `0x000fc613`；
 * - 0x04: Big-Endian Int32 版本，1 = 未压缩，2 = zstd 压缩（可多帧）；
 * - 0x08: `DATrie<float>` 序列化数据。
 *
 * DATrie 布局（libime `core/datrie.cpp`）：
 * `[be32 tailLen][be32 nodeCount][tail][nodeCount * (be32 base, be32 check)]
 * [be32 bheadF/bheadC/bheadO][nodeCount * (u8 sibling, u8 child)][blockCount * 20B]`，
 * 其中 blockCount = nodeCount / 256。
 *
 * 键为「每音节 2 字节拼音编码 + '!' + UTF-8 汉字」，值为 float 词频。
 */
object LibimeBinLexiconParser : LexiconParser {

    override val id: String = "libimebin"
    override val displayName: String = "Fcitx5/libime 二进制词库"
    override val extensions: List<String> = listOf(".dict")
    override val maxBytes: Int = 64 * 1024 * 1024

    /** 只认完整 8 字节头 + libime magic，避免别的 `.dict` 被误认为 libime 词库。 */
    override fun looksLike(bytes: ByteArray): Boolean =
        bytes.size >= LibimeBinFormat.HEADER_BYTES &&
            LibimeBinFormat.readMagic(bytes) == LibimeBinFormat.MAGIC

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val raw = LibimeBinFormat.readDatrie(bytes)
        val trie = CedarFloatTrie.load(raw)
        val converted = convert(trie.entries())
        return ParsedLexicon(
            formatId = id,
            formatName = displayName,
            codeSystem = LexiconCodeSystem.PINYIN,
            entries = converted.entries,
            skipped = converted.skipped,
            duplicates = converted.duplicates,
        )
    }

    internal data class Conversion(
        val entries: List<LexiconEntry>,
        val skipped: Int,
        val duplicates: Int,
    )

    /**
     * DATrie 键值 -> Rime 词条。
     *
     * 键必须是「偶数个拼音字节 + '!' + 非空汉字」；音节解不出、键结构不对的整条跳过。
     */
    internal fun convert(raw: Sequence<Pair<ByteArray, Float>>): Conversion {
        val seen = HashSet<String>()
        val entries = ArrayList<LexiconEntry>()
        var skipped = 0
        var duplicates = 0

        for ((key, value) in raw) {
            var separator = -1
            for (index in key.indices) {
                if (key[index] == HANZI_SEPARATOR) {
                    separator = index
                    break
                }
            }
            if (separator <= 0 || separator % 2 != 0 || separator == key.size - 1) {
                skipped++
                continue
            }
            val word = String(key, separator + 1, key.size - separator - 1, Charsets.UTF_8)
            val syllables = LibimePinyinTable.decodeFullPinyin(key, separator)
            if (word.isEmpty() || syllables == null) {
                skipped++
                continue
            }
            val code = syllables.joinToString(" ")
            if (!seen.add(word + '\u0000' + code)) {
                duplicates++
                continue
            }
            entries += LexiconEntry(word = word, code = code, weight = weightOf(value))
        }
        return Conversion(entries, skipped, duplicates)
    }

    /** libime 存 float 词频；非正/非有限值视为没有词频，用默认权重。 */
    private fun weightOf(value: Float): Int {
        if (!value.isFinite() || value <= 1f) return DEFAULT_WEIGHT
        return value.toInt().coerceAtMost(MAX_WEIGHT)
    }

    private const val DEFAULT_WEIGHT = 1
    private const val MAX_WEIGHT = 1_000_000
    private val HANZI_SEPARATOR = '!'.code.toByte()
}

/** libime 二进制容器头读取与 zstd 解压。 */
internal object LibimeBinFormat {

    const val MAGIC: Int = 0x000fc613
    const val HEADER_BYTES: Int = 8

    private const val VERSION_LEGACY = 1
    private const val VERSION_ZSTD = 2
    private const val MAX_DATRIE_BYTES = 256 * 1024 * 1024
    private const val ZSTD_BUFFER_BYTES = 64 * 1024

    fun readMagic(bytes: ByteArray): Int =
        if (bytes.size >= 4) readInt32BigEndian(bytes, 0) else 0

    /** 校验文件头并返回未压缩的 DATrie 字节流。 */
    fun readDatrie(bytes: ByteArray): ByteArray {
        if (bytes.size < HEADER_BYTES) {
            corrupt("文件太小，不是有效的 libime 二进制词库")
        }
        val magic = readInt32BigEndian(bytes, 0)
        if (magic != MAGIC) {
            corrupt("不是 libime 二进制词库：magic=0x%08x".format(magic))
        }
        return when (val version = readInt32BigEndian(bytes, 4)) {
            VERSION_LEGACY -> bytes.copyOfRange(HEADER_BYTES, bytes.size)
            VERSION_ZSTD -> unzstd(bytes, HEADER_BYTES)
            else -> corrupt("不支持的 libime 二进制版本：${version.toLong() and 0xffffffffL}")
        }
    }

    private fun unzstd(bytes: ByteArray, offset: Int): ByteArray {
        try {
            ZstdInputStream(
                ByteArrayInputStream(bytes, offset, bytes.size - offset),
            ).setContinuous(true).use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(ZSTD_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (out.size().toLong() + read > MAX_DATRIE_BYTES) {
                        corrupt("libime 词库解压后超过上限：$MAX_DATRIE_BYTES 字节")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        } catch (e: LexiconFormatException) {
            throw e
        } catch (e: IOException) {
            corrupt("zstd 解压失败：${e.message ?: "压缩数据损坏"}")
        } catch (e: RuntimeException) {
            corrupt("zstd 解压失败：${e.message ?: "压缩数据损坏"}")
        } catch (e: UnsatisfiedLinkError) {
            corrupt("当前设备缺少 zstd 运行库：${e.message ?: "native 加载失败"}")
        }
    }

    private fun readInt32BigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}

/**
 * libime `DATrie<float>` 的只读移植（cedar 双数组 Trie）。
 *
 * 只保留 Load / Enumerate 路径：构建期的 block 链表与 bhead 只校验长度后跳过，
 * 遍历只用 `base`、`check`、`sibling`、`child` 与 `tail`。
 */
internal class CedarFloatTrie private constructor(
    private val tail: ByteArray,
    private val base: IntArray,
    private val check: IntArray,
    private val sibling: ByteArray,
    private val child: ByteArray,
) {

    private class Cursor(
        var index: Int = 0,
        var offset: Int = 0,
        var length: Int = 0,
    )

    private val size: Int get() = base.size

    /** 按 cedar 的 DFS 顺序枚举全部键值；值保存的是 float 位模式。 */
    fun entries(): Sequence<Pair<ByteArray, Float>> = sequence {
        val cursor = Cursor()
        val maxSteps = size.toLong() * 4L + 1024L
        var steps = 0L
        var raw = begin(cursor)
        while (raw != NO_PATH) {
            if (raw != NO_VALUE) {
                yield(suffix(cursor) to Float.fromBits(raw))
            }
            if (++steps > maxSteps) {
                corrupt("DATrie 遍历没有终止，数据可能已损坏")
            }
            raw = next(cursor)
        }
    }

    private fun begin(cursor: Cursor): Int {
        var from = cursor.index
        var nodeBase = if (cursor.offset != 0) -cursor.offset else baseAt(from)
        if (nodeBase >= 0) {
            var label = childAt(from)
            if (from == 0) {
                label = siblingAt(nodeBase xor label)
                if (label == 0) return NO_PATH
            }
            while (label != 0 && nodeBase >= 0) {
                from = nodeBase xor label
                nodeBase = baseAt(from)
                label = childAt(from)
                if (++cursor.length > MAX_KEY_BYTES) {
                    corrupt("DATrie 键长度异常")
                }
            }
            if (nodeBase >= 0) {
                cursor.index = from
                return baseAt(nodeBase xor label)
            }
        }
        cursor.index = from
        val tailLength = strLen(-nodeBase)
        cursor.offset = -nodeBase + tailLength
        cursor.length += tailLength
        return loadTailValue(-nodeBase + tailLength + 1)
    }

    private fun next(cursor: Cursor): Int {
        var label = 0
        if (cursor.offset != 0) {
            val offset = cursor.offset
            cursor.offset = 0
            cursor.length -= offset - (-baseAt(cursor.index))
        } else {
            label = siblingAt(baseAt(cursor.index))
        }
        while (label == 0 && !(cursor.offset == 0 && cursor.index == 0)) {
            label = siblingAt(cursor.index)
            cursor.index = checkAt(cursor.index)
            if (--cursor.length < 0) {
                corrupt("DATrie 回溯越界，数据可能已损坏")
            }
        }
        if (label == 0) return NO_PATH
        cursor.index = baseAt(cursor.index) xor label
        cursor.length++
        return begin(cursor)
    }

    private fun suffix(cursor: Cursor): ByteArray {
        var length = cursor.length
        if (length !in 0..MAX_KEY_BYTES) {
            corrupt("DATrie 键长度异常：$length")
        }
        val key = ByteArray(length)
        var to = cursor.index
        val offset = cursor.offset
        if (offset != 0) {
            var tailLength = strLen(-baseAt(to))
            if (length > tailLength) {
                length -= tailLength
            } else {
                tailLength = length
                length = 0
            }
            val start = offset - tailLength
            if (start < 0 || start + tailLength > tail.size) {
                corrupt("DATrie tail 越界")
            }
            System.arraycopy(tail, start, key, length, tailLength)
        }
        while (length > 0) {
            length--
            val from = checkAt(to)
            key[length] = (baseAt(from) xor to).toByte()
            to = from
        }
        return key
    }

    private fun strLen(offset: Int): Int {
        if (offset < 0 || offset >= tail.size) {
            corrupt("DATrie tail 偏移越界：$offset")
        }
        var length = 0
        while (offset + length < tail.size) {
            if (tail[offset + length].toInt() == 0) return length
            length++
        }
        corrupt("DATrie tail 字符串未结束")
    }

    private fun loadTailValue(offset: Int): Int {
        if (offset < 0 || offset + VALUE_SIZE > tail.size) {
            corrupt("DATrie tail 词频越界：$offset")
        }
        return (tail[offset].toInt() and 0xff) or
            ((tail[offset + 1].toInt() and 0xff) shl 8) or
            ((tail[offset + 2].toInt() and 0xff) shl 16) or
            ((tail[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun baseAt(node: Int): Int =
        if (node in base.indices) base[node] else corrupt("DATrie 节点越界：$node")

    private fun checkAt(node: Int): Int =
        if (node in check.indices) check[node] else corrupt("DATrie 节点越界：$node")

    private fun siblingAt(node: Int): Int =
        if (node in sibling.indices) sibling[node].toInt() and 0xff else corrupt("DATrie 节点越界：$node")

    private fun childAt(node: Int): Int =
        if (node in child.indices) child[node].toInt() and 0xff else corrupt("DATrie 节点越界：$node")

    private class Reader(private val data: ByteArray) {
        private var position = 0

        fun readInt32(): Int {
            if (data.size - position < 4) corrupt("DATrie 数据提前结束")
            val value = ((data[position].toInt() and 0xff) shl 24) or
                ((data[position + 1].toInt() and 0xff) shl 16) or
                ((data[position + 2].toInt() and 0xff) shl 8) or
                (data[position + 3].toInt() and 0xff)
            position += 4
            return value
        }

        fun readByte(): Byte {
            if (position >= data.size) corrupt("DATrie 数据提前结束")
            return data[position++]
        }

        fun readBytes(count: Int): ByteArray {
            if (count < 0 || count > data.size - position) corrupt("DATrie 数据提前结束")
            val out = data.copyOfRange(position, position + count)
            position += count
            return out
        }

        fun skip(count: Int) {
            if (count < 0 || count > data.size - position) corrupt("DATrie 数据提前结束")
            position += count
        }
    }

    companion object {
        private const val VALUE_SIZE = 4
        private const val NO_VALUE = 0x7fc00001
        private const val NO_PATH = 0x7fc00002
        private const val BLOCK_SIZE = 256
        private const val BLOCK_BYTES = 20
        private const val MAX_NODES = 8 * 1024 * 1024
        private const val MAX_TAIL_BYTES = 64 * 1024 * 1024
        private const val MAX_KEY_BYTES = 4096

        /** 读取 DATrie 序列化数据；长度或节点数不符立即失败。 */
        fun load(data: ByteArray): CedarFloatTrie {
            val reader = Reader(data)
            val tailLength = reader.readInt32()
            val nodeCount = reader.readInt32()

            if (tailLength < VALUE_SIZE || tailLength > MAX_TAIL_BYTES) {
                corrupt("DATrie tail 长度异常：$tailLength")
            }
            if (nodeCount < BLOCK_SIZE || nodeCount % BLOCK_SIZE != 0 || nodeCount > MAX_NODES) {
                corrupt("DATrie 节点数异常：$nodeCount")
            }
            val blockCount = nodeCount / BLOCK_SIZE
            val expected = 8L + tailLength.toLong() +
                nodeCount.toLong() * 8L + 12L +
                nodeCount.toLong() * 2L + blockCount.toLong() * BLOCK_BYTES
            if (expected != data.size.toLong()) {
                corrupt("DATrie 长度不符：期望 $expected 字节，实际 ${data.size} 字节")
            }

            val tail = reader.readBytes(tailLength)
            val base = IntArray(nodeCount)
            val check = IntArray(nodeCount)
            for (index in 0 until nodeCount) {
                base[index] = reader.readInt32()
                check[index] = reader.readInt32()
            }
            // bheadF/bheadC/bheadO 与 block 链表只用于构建期节点分配，遍历不读取。
            reader.skip(12)
            val sibling = ByteArray(nodeCount)
            val child = ByteArray(nodeCount)
            for (index in 0 until nodeCount) {
                sibling[index] = reader.readByte()
                child[index] = reader.readByte()
            }
            reader.skip(blockCount * BLOCK_BYTES)
            return CedarFloatTrie(tail, base, check, sibling, child)
        }
    }
}

/**
 * libime 拼音编码表（`libime/pinyin/pinyindata.cpp` 规范条目）的解码方向。
 *
 * 每个音节存成 (声母, 韵母) 两个字符，取值从 'A' 起按 libime 枚举顺序编号。
 * `Letter_A..Letter_Z` 韵母对应大写单字母；Rime 拼音码统一小写，这里直接给小写。
 */
internal object LibimePinyinTable {

    private const val INITIAL_BASE = 65
    private const val FINAL_BASE = 65

    private val INITIALS = arrayOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r",
        "z", "c", "s", "y", "w", "",
    )

    private val FINALS = arrayOf(
        "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ong", "ou", "i", "ia", "ie",
        "iao", "iu", "ian", "in", "iang", "ing", "iong", "u", "ua", "uo", "uai", "ui", "uan", "un",
        "uang", "v", "ve", "ue", "ng", "", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k",
        "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
    )

    /** 解出全部音节；任一音节无法识别时返回 null（整条词目应跳过）。 */
    fun decodeFullPinyin(data: ByteArray, length: Int): List<String>? {
        if (length <= 0 || length % 2 != 0) return null
        val syllables = ArrayList<String>(length / 2)
        var offset = 0
        while (offset < length) {
            val initial = data[offset].toInt() and 0xff
            val final = data[offset + 1].toInt() and 0xff
            val syllable = initialText(initial) + finalText(final)
            if (syllable.isEmpty()) return null
            syllables += syllable
            offset += 2
        }
        return syllables
    }

    private fun initialText(value: Int): String {
        val index = value - INITIAL_BASE
        return if (index in INITIALS.indices) INITIALS[index] else ""
    }

    private fun finalText(value: Int): String {
        val index = value - FINAL_BASE
        return if (index in FINALS.indices) FINALS[index] else ""
    }
}

private fun corrupt(message: String): Nothing = throw LexiconFormatException(message)
