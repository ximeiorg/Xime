package com.kingzcheung.xime.lexicon

/**
 * 搜狗拼音备份词库（.bin）解析器，对应 imewlconverter 的 `sgpybin`。
 *
 * 实际存在三种二进制形态：
 * - `SGPU`（0x55504753）：新版「导出词库」备份，索引区 + 词条区；
 * - 首 4 字节 0x554401D3：7.2 版以前的明文备份，0x18 处为词条数，0x30 起为分组数据；
 * - 旧版加密备份（usrDictV3）：没有魔数，按文件头的键/属性/数据偏移表 + 词条解密。
 *
 * 前两种在 [looksLike] 里用魔数认领；usrDictV3 只在直接调用 [parse] 时兜底尝试。
 */
object SougouBinLexiconParser : LexiconParser {

    override val id: String = "sgpybin"
    override val displayName: String = "搜狗拼音备份词库"
    override val extensions: List<String> = listOf(".bin")
    override val maxBytes: Int = 64 * 1024 * 1024

    internal const val MAGIC_SGPU: Long = 0x55504753L
    internal const val MAGIC_PLAIN_OLD: Long = 0x554401D3L

    /** 备份里的词频没有固定上限，截断到与导入管线一致的安全值。 */
    internal const val MAX_WEIGHT = 1_000_000

    override fun looksLike(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = SogouBinReader.magic(bytes)
        return magic == MAGIC_SGPU || magic == MAGIC_PLAIN_OLD
    }

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val raw = try {
            parseRaw(bytes)
        } catch (e: LexiconFormatException) {
            throw e
        } catch (e: Exception) {
            throw LexiconFormatException("解析搜狗拼音备份词库失败：${e.message ?: "文件已损坏"}")
        }
        val converted = convert(raw)
        return ParsedLexicon(
            formatId = id,
            formatName = displayName,
            codeSystem = LexiconCodeSystem.PINYIN,
            entries = converted.entries,
            skipped = converted.skipped,
            duplicates = converted.duplicates,
        )
    }

    private fun parseRaw(bytes: ByteArray): List<SogouBinEntry> {
        if (bytes.size < 4) {
            throw LexiconFormatException("文件太小，不是有效的搜狗拼音备份词库")
        }
        return when (SogouBinReader.magic(bytes)) {
            MAGIC_SGPU -> SogouBinSgpuParser.parse(bytes)
            MAGIC_PLAIN_OLD -> SogouBinPlainOldParser.parse(bytes)
            else -> SogouBinEncryptedParser.parse(bytes)
        }
    }

    internal data class Conversion(
        val entries: List<LexiconEntry>,
        val skipped: Int,
        val duplicates: Int,
    )

    /** 原始词条 -> Rime 词条：去空、去重、过滤会破坏码表行的字符。 */
    internal fun convert(raw: List<SogouBinEntry>): Conversion {
        val seen = HashSet<String>(raw.size * 2)
        val entries = ArrayList<LexiconEntry>(raw.size)
        var skipped = 0
        var duplicates = 0

        for (entry in raw) {
            val word = entry.word
            val code = entry.code.trim()
            if (word.isBlank() || code.isEmpty() ||
                word.any { it == '\t' || it == '\n' || it == '\r' }
            ) {
                skipped++
                continue
            }
            if (!seen.add(word + '\u0000' + code)) {
                duplicates++
                continue
            }
            entries += LexiconEntry(word = word, code = code, weight = weightOf(entry.frequency))
        }
        return Conversion(entries, skipped, duplicates)
    }

    private fun weightOf(frequency: Int): Int =
        if (frequency <= 0) 1 else frequency.coerceAtMost(MAX_WEIGHT)
}

/** 解析后的一条原始词条；[frequency] 是备份里记录的词频，可能为 0。 */
internal data class SogouBinEntry(
    val word: String,
    val code: String,
    val frequency: Int,
)

/** 搜狗拼音音节表；前 413 项是正常音节，之后是单字母、数字和 #（新格式用）。 */
internal object SogouBinPinyin {

    val values: Array<String> = arrayOf(
        "a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao",
        "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo",
        "bu", "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha",
        "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou",
        "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong",
        "cou", "cu", "cuan", "cui", "cun", "cuo", "da", "dai", "dan", "dang",
        "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die",
        "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo", "e",
        "ei", "en", "eng", "er", "fa", "fan", "fang", "fei", "fen", "feng",
        "fiao", "fo", "fou", "fu", "ga", "gai", "gan", "gang", "gao", "ge",
        "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang",
        "gui", "gun", "guo", "ha", "hai", "han", "hang", "hao", "he", "hei",
        "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui",
        "hun", "huo", "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing",
        "jiong", "jiu", "ju", "juan", "jue", "jun", "ka", "kai", "kan", "kang",
        "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai",
        "kuan", "kuang", "kui", "kun", "kuo", "la", "lai", "lan", "lang", "lao",
        "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin",
        "ling", "liu", "lo", "long", "lou", "lu", "luan", "lve", "lun", "luo",
        "lv", "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng",
        "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni",
        "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu",
        "nuan", "nve", "nun", "nuo", "nv", "o", "ou", "pa", "pai", "pan",
        "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin",
        "ping", "po", "pou", "pu", "qi", "qia", "qian", "qiang", "qiao", "qie",
        "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun", "ran", "rang",
        "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan",
        "rui", "run", "ruo", "sa", "sai", "san", "sang", "sao", "se", "sen",
        "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng",
        "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo",
        "si", "song", "sou", "su", "suan", "sui", "sun", "suo", "ta", "tai",
        "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie",
        "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo", "wa", "wai",
        "wan", "wang", "wei", "wen", "weng", "wo", "wu", "xi", "xia", "xian",
        "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue",
        "xun", "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo",
        "yong", "you", "yu", "yuan", "yue", "yun", "za", "zai", "zan", "zang",
        "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao",
        "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai",
        "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan",
        "zui", "zun", "zuo",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j",
        "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
        "u", "v", "w", "x", "y", "z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "#",
    )

    /** 把索引序列转成空格分隔的音节；越界索引直接忽略。 */
    fun code(indexes: IntArray): String {
        val builder = StringBuilder()
        for (index in indexes) {
            if (index < values.size) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(values[index])
            }
        }
        return builder.toString()
    }
}

/** 带边界检查的二进制读取器；所有越界都转成 [LexiconFormatException]。 */
internal class SogouBinReader(private val data: ByteArray) {

    var position: Int = 0
        private set

    val size: Int get() = data.size

    fun seek(offset: Long) {
        if (offset < 0 || offset > data.size.toLong()) {
            throw LexiconFormatException("偏移越界：$offset")
        }
        position = offset.toInt()
    }

    fun seek(offset: Int) = seek(offset.toLong())

    fun canRead(count: Int): Boolean = count >= 0 && position <= data.size - count

    fun skip(count: Int) {
        if (!canRead(count)) throw LexiconFormatException("数据提前结束")
        position += count
    }

    fun readU16(): Int {
        if (!canRead(2)) throw LexiconFormatException("数据提前结束")
        val value = (data[position].toInt() and 0xff) or
            ((data[position + 1].toInt() and 0xff) shl 8)
        position += 2
        return value
    }

    fun readI32(): Int {
        if (!canRead(4)) throw LexiconFormatException("数据提前结束")
        val value = (data[position].toInt() and 0xff) or
            ((data[position + 1].toInt() and 0xff) shl 8) or
            ((data[position + 2].toInt() and 0xff) shl 16) or
            ((data[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    fun readU32(): Long {
        if (!canRead(4)) throw LexiconFormatException("数据提前结束")
        val value = (data[position].toLong() and 0xff) or
            ((data[position + 1].toLong() and 0xff) shl 8) or
            ((data[position + 2].toLong() and 0xff) shl 16) or
            ((data[position + 3].toLong() and 0xff) shl 24)
        position += 4
        return value
    }

    fun readUtf16(byteCount: Int): String {
        if (byteCount < 0 || !canRead(byteCount)) {
            throw LexiconFormatException("数据提前结束")
        }
        val text = String(data, position, byteCount, Charsets.UTF_16LE)
        position += byteCount
        return text
    }

    fun readBytes(byteCount: Int): ByteArray {
        if (byteCount < 0 || !canRead(byteCount)) {
            throw LexiconFormatException("数据提前结束")
        }
        val bytes = data.copyOfRange(position, position + byteCount)
        position += byteCount
        return bytes
    }

    companion object {
        fun magic(bytes: ByteArray): Long =
            (bytes[0].toLong() and 0xff) or
                ((bytes[1].toLong() and 0xff) shl 8) or
                ((bytes[2].toLong() and 0xff) shl 16) or
                ((bytes[3].toLong() and 0xff) shl 24)
    }
}

/** 新版 SGPU 备份：0x38 处是索引/词条区的位置与数量。 */
internal object SogouBinSgpuParser {

    private const val HEADER_OFFSET = 0x38
    private const val MAX_WORD_COUNT = 500_000
    private const val MAX_PINYIN_BYTES = 8_192

    fun parse(bytes: ByteArray): List<SogouBinEntry> {
        val reader = SogouBinReader(bytes)
        reader.seek(HEADER_OFFSET)

        val idxBegin = reader.readU32()
        val idxSize = reader.readU32()
        val wordCount = reader.readU32()
        val dictBegin = reader.readU32()
        reader.readU32() // 词条区预分配容量，未使用
        val dictSize = reader.readU32()

        val fileSize = bytes.size.toLong()
        if (idxBegin > fileSize || idxSize > fileSize - idxBegin) {
            throw LexiconFormatException("索引区越界")
        }
        if (wordCount > MAX_WORD_COUNT || wordCount > idxSize / 4) {
            throw LexiconFormatException("词条数异常：$wordCount")
        }
        if (dictBegin > fileSize || dictSize > fileSize - dictBegin) {
            throw LexiconFormatException("词条区越界")
        }

        val entries = ArrayList<SogouBinEntry>(wordCount.toInt())
        val idxBeginInt = idxBegin.toInt()
        for (i in 0 until wordCount.toInt()) {
            reader.seek(idxBeginInt + 4L * i)
            val recordOffset = reader.readU32()
            if (recordOffset >= dictSize) {
                throw LexiconFormatException("词条偏移越界：$recordOffset")
            }

            reader.seek(dictBegin + recordOffset)
            val frequency = reader.readU16()
            reader.skip(2)
            reader.skip(5)

            val pinyinBytes = reader.readU16()
            if (pinyinBytes % 2 != 0 || pinyinBytes > MAX_PINYIN_BYTES) {
                throw LexiconFormatException("拼音长度异常：$pinyinBytes")
            }
            val indexes = IntArray(pinyinBytes / 2) { reader.readU16() }
            reader.skip(2)

            val wordBytes = reader.readU16()
            if (wordBytes % 2 != 0) {
                throw LexiconFormatException("词条长度异常：$wordBytes")
            }
            val word = reader.readUtf16(wordBytes)
            entries += SogouBinEntry(
                word = word,
                code = SogouBinPinyin.code(indexes),
                frequency = frequency,
            )
        }
        return entries
    }
}

/**
 * 7.2 版以前的明文备份（首 4 字节 0x554401D3）。
 *
 * 0x18: Int32 词条总数；0x30 起按分组：
 * (UInt16 同音词数, UInt16 未知, UInt16 拼音字节数, UInt16 音节索引...),
 * 每个词条是 (UInt16 词字节数, UTF-16LE 词, UInt16 词频, UInt16 未知, Int32 未知)。
 */
internal object SogouBinPlainOldParser {

    private const val WORD_COUNT_OFFSET = 0x18
    private const val DATA_OFFSET = 0x30
    private const val MAX_WORD_COUNT = 500_000
    private const val MAX_PINYIN_BYTES = 8_192

    fun parse(bytes: ByteArray): List<SogouBinEntry> {
        val reader = SogouBinReader(bytes)
        reader.seek(WORD_COUNT_OFFSET)
        val wordCount = reader.readI32()
        if (wordCount < 0 || wordCount > MAX_WORD_COUNT) {
            throw LexiconFormatException("词条数异常：$wordCount")
        }

        reader.seek(DATA_OFFSET)
        val entries = ArrayList<SogouBinEntry>(wordCount)
        while (entries.size < wordCount) {
            val samePinyinCount = reader.readU16()
            reader.skip(2)
            val pinyinBytes = reader.readU16()
            if (samePinyinCount <= 0 || pinyinBytes % 2 != 0 ||
                pinyinBytes > MAX_PINYIN_BYTES
            ) {
                throw LexiconFormatException("分组数据损坏")
            }

            val indexes = IntArray(pinyinBytes / 2) { reader.readU16() }
            val code = SogouBinPinyin.code(indexes)
            val codeSyllables = if (code.isEmpty()) 0 else code.count { it == ' ' } + 1
            repeat(samePinyinCount) {
                val wordBytes = reader.readU16()
                if (wordBytes % 2 != 0) {
                    throw LexiconFormatException("词条长度异常：$wordBytes")
                }
                val word = reader.readUtf16(wordBytes)
                val frequency = reader.readU16()
                reader.skip(6)

                // 旧实现要求音节数等于字的个数，否则视为无效数据。
                val valid = codeSyllables > 0 && codeSyllables == word.length
                entries += SogouBinEntry(
                    word = word,
                    code = if (valid) code else "",
                    frequency = frequency,
                )
            }
        }
        return entries
    }
}

/**
 * 旧版加密备份（usrDictV3），逐段对应 imewlconverter 的 `SougouBinParser` 旧格式分支。
 * 文件头没有魔数，只能先校验偏移表再读取；任何越界都按格式损坏处理。
 */
internal object SogouBinEncryptedParser {

    private const val USER_HEADER_SIZE = 0x4c
    private const val MAX_KEYS = 64
    private const val MAX_ATTRS = 4_096
    private const val MAX_AINTS = 1_000_000
    private const val MAX_HEADER_ITEMS = 65_536
    private const val MAX_DATA_ITEMS = 4_096
    private const val MAX_HASH_STORES = 100_000
    private const val MAX_ATTR_COUNT = 500_000
    private const val MAX_ENTRIES = 500_000
    private const val MAX_VISITS = 2_000_000L
    private const val MAX_CODE_UNITS = 4_096

    private val DATATYPE_HASH_SIZE = intArrayOf(0, 27, 414, 512, -1, -1, 512, 0)
    private val KEY_ITEM_DATA_TYPE_SIZE = intArrayOf(4, 1, 1, 2, 1, 2, 2, 4, 4, 8, 4, 4, 4, 0, 0, 0)

    fun parse(bytes: ByteArray): List<SogouBinEntry> {
        if (bytes.size < USER_HEADER_SIZE) {
            throw LexiconFormatException("文件太小，不是有效的搜狗旧版备份")
        }
        val reader = SogouBinReader(bytes)
        val size = bytes.size.toLong()

        reader.seek(4)
        val configSize = reader.readU32()
        val keyCount = reader.readU32()
        val attrCount = reader.readU32()
        val aintCount = reader.readU32()

        if (configSize > size || configSize < 12) {
            throw LexiconFormatException("配置区长度异常：$configSize")
        }
        if (keyCount !in 1..MAX_KEYS.toLong() ||
            attrCount !in 1..MAX_ATTRS.toLong() ||
            aintCount > MAX_AINTS
        ) {
            throw LexiconFormatException("词库表数量异常")
        }

        reader.seek(20)
        val keys = ArrayList<KeyItem>(keyCount.toInt())
        repeat(keyCount.toInt()) { keys += readKey(reader, attrCount.toInt()) }
        val attributes = ArrayList<AttributeItem>(attrCount.toInt())
        repeat(attrCount.toInt()) { attributes += readAttribute(reader) }
        repeat(aintCount.toInt()) { reader.readU32() }

        val configEnd = configSize + 8
        if (reader.position.toLong() != configEnd) {
            throw LexiconFormatException("配置区长度不一致")
        }

        reader.seek(configEnd + 8)
        reader.readU32() // 总大小，只作校验用途，读取后忽略
        val idxHeaderCount = reader.readU32()
        val attrHeaderCount = reader.readU32()
        val dataHeaderCount = reader.readU32()
        if (idxHeaderCount > MAX_HEADER_ITEMS ||
            attrHeaderCount > MAX_HEADER_ITEMS ||
            dataHeaderCount > MAX_DATA_ITEMS
        ) {
            throw LexiconFormatException("数据表数量异常")
        }

        val headerItemsIdx = ArrayList<HeaderItem>(idxHeaderCount.toInt())
        repeat(idxHeaderCount.toInt()) { headerItemsIdx += readHeaderItem(reader) }
        val headerItemsAttr = ArrayList<HeaderItem>(attrHeaderCount.toInt())
        repeat(attrHeaderCount.toInt()) { headerItemsAttr += readHeaderItem(reader) }
        val dataStore = ArrayList<HeaderItem>(dataHeaderCount.toInt())
        repeat(dataHeaderCount.toInt()) { dataStore += readHeaderItem(reader) }
        val dsBase = reader.position.toLong()

        val headerSize = 12L * (keyCount + attrCount + aintCount) + 24
        if (dsBase != configEnd + headerSize) {
            throw LexiconFormatException("数据区偏移不一致")
        }

        reader.seek(size - USER_HEADER_SIZE)
        reader.skip(56)
        val p2 = reader.readU32()
        val p3 = reader.readU32()

        val dict = DictData(keys, attributes, headerItemsIdx, headerItemsAttr, dataStore, dsBase)
        dict.initialize()
        return readAllWords(reader, dict, p2, p3, size)
    }

    private fun readKey(reader: SogouBinReader, attrCount: Int): KeyItem {
        val dictTypeDef = reader.readU16()
        val dataTypeCount = reader.readU16()
        if (dataTypeCount > 64) throw LexiconFormatException("数据类型数量异常")
        val dataTypes = IntArray(dataTypeCount) {
            val type = reader.readU16()
            if (type >= KEY_ITEM_DATA_TYPE_SIZE.size) {
                throw LexiconFormatException("数据类型异常：$type")
            }
            type
        }
        val attrIdx = reader.readI32()
        val keyDataIdx = reader.readI32()
        val dataIdx = reader.readI32()
        reader.readU32() // v6，未使用
        if (attrIdx >= attrCount || attrIdx < -1) {
            throw LexiconFormatException("属性索引异常：$attrIdx")
        }
        return KeyItem(dictTypeDef, dataTypes, attrIdx, keyDataIdx, dataIdx)
    }

    private fun readAttribute(reader: SogouBinReader): AttributeItem {
        val count = reader.readI32()
        reader.readU32() // a2
        val dataId = reader.readI32()
        reader.readU32() // b2
        if (count < 0) throw LexiconFormatException("属性数量异常：$count")
        return AttributeItem(count, dataId)
    }

    private fun readHeaderItem(reader: SogouBinReader): HeaderItem {
        val offset = reader.readU32()
        val dataSize = reader.readI32()
        val usedDataSize = reader.readI32()
        return HeaderItem(offset, dataSize, usedDataSize)
    }

    private fun readAllWords(
        reader: SogouBinReader,
        dict: DictData,
        p2: Long,
        p3: Long,
        size: Long,
    ): List<SogouBinEntry> {
        val key = dict.keys[0]
        val attrIdx = key.attrIdx
        if (attrIdx !in dict.attributes.indices) {
            throw LexiconFormatException("首个键缺少属性表")
        }
        val attrHeader = dict.headerItemsAttr.getOrNull(attrIdx)
            ?: throw LexiconFormatException("属性表索引越界")
        val attrCount = if (attrHeader.usedDataSize == 0) {
            attrHeader.dataSize
        } else {
            attrHeader.usedDataSize
        }
        if (attrCount !in 0..MAX_ATTR_COUNT) {
            throw LexiconFormatException("属性数量异常：$attrCount")
        }

        val hashStoreCount = dict.baseHashSize[0]
        if (hashStoreCount !in 1..MAX_HASH_STORES) {
            throw LexiconFormatException("哈希表数量异常：$hashStoreCount")
        }
        val dataId = dict.attributes[attrIdx].dataId
        if (dataId !in dict.dataStore.indices) {
            throw LexiconFormatException("数据表索引越界：$dataId")
        }

        val entries = ArrayList<SogouBinEntry>()
        var hashPosition = dict.hashStorePosition(0)
        var visited = 0L
        repeat(hashStoreCount) {
            reader.seek(hashPosition)
            val hashOffset = reader.readI32()
            val hashCount = reader.readI32()
            hashPosition = reader.position.toLong()
            if (hashCount < 0 || hashCount > MAX_ENTRIES) {
                throw LexiconFormatException("哈希项数量异常：$hashCount")
            }

            repeat(hashCount) { attrId ->
                val attrBase = dict.attributePositionFromIndex(0, attrId, hashOffset.toLong())
                reader.seek(attrBase + dict.dataTypeSize[0] - 4)
                var nextOffset = reader.readI32().toLong()

                for (ia2 in 0 until attrCount) {
                    visited++
                    if (visited > MAX_VISITS) {
                        throw LexiconFormatException("属性链过长")
                    }
                    val attr2Base = dict.attributePositionFromAttribute(0, nextOffset)
                    if (attr2Base < 0 || attr2Base > size) break

                    reader.seek(attr2Base + dict.attrSize[attrIdx] - 4)
                    nextOffset = reader.readI32().toLong()
                    entries += readWord(reader, dict, attrBase, attr2Base, p2, p3)
                    if (entries.size > MAX_ENTRIES) {
                        throw LexiconFormatException("词条数量超过上限")
                    }
                    if (nextOffset == -1L) break
                }
            }
        }
        return entries
    }

    private fun readWord(
        reader: SogouBinReader,
        dict: DictData,
        attriPos1: Long,
        attriPos2: Long,
        p2: Long,
        p3: Long,
    ): SogouBinEntry {
        reader.seek(attriPos1)
        val pinyinOffset = reader.readU32()
        val pinyinPosition = dict.dataPosition(dict.keys[0].keyDataIdx, pinyinOffset)
        val code = decryptPinyin(reader, pinyinPosition)

        reader.seek(attriPos2)
        val offset = reader.readI32().toLong()
        val frequency = reader.readU16()
        reader.readU16() // aFlag
        reader.readU32() // i8
        val p1 = reader.readU16()
        reader.readI32() // iE
        reader.skip(4) // 下一属性偏移

        val dataId = dict.attributes[dict.keys[0].attrIdx].dataId
        val wordPosition = dict.dataPosition(dataId, offset)
        reader.seek(wordPosition)
        val word = decryptWords(reader, p1, p2, p3)
        return SogouBinEntry(word, code, frequency)
    }

    private fun decryptPinyin(reader: SogouBinReader, position: Long): String {
        reader.seek(position)
        val count = reader.readU16() / 2
        if (count > MAX_CODE_UNITS) throw LexiconFormatException("拼音数量异常：$count")
        val builder = StringBuilder()
        repeat(count) {
            val index = reader.readU16()
            if (index < SogouBinPinyin.values.size) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(SogouBinPinyin.values[index])
            }
        }
        return builder.toString()
    }

    private fun decryptWords(
        reader: SogouBinReader,
        p1: Int,
        p2: Long,
        p3: Long,
    ): String {
        val xk = ((((p1.toLong() + p2) shl 2) + ((p1.toLong() + p3) shl 2)) and 0xffffL).toInt()
        val count = reader.readU16() / 2
        if (count > MAX_CODE_UNITS) throw LexiconFormatException("词条长度异常：$count")
        if (!reader.canRead(count * 2)) {
            throw LexiconFormatException("词条数据提前结束")
        }
        val raw = ByteArray(count * 4)
        val shift = (p2 % 8).toInt()
        for (i in 0 until count) {
            val ch = reader.readU16()
            val rotated = ((ch shl (16 - shift)) or (ch shr shift)) and 0xffff
            val dch = rotated xor xk
            raw[i * 4] = dch.toByte()
            raw[i * 4 + 1] = (dch ushr 8).toByte()
            // 高 16 位为零，与参考实现的 4 字节小端写法一致。
        }
        return String(raw, Charsets.UTF_32LE)
    }

    private data class KeyItem(
        val dictTypeDef: Int,
        val dataTypes: IntArray,
        val attrIdx: Int,
        val keyDataIdx: Int,
        val dataIdx: Int,
    )

    private data class AttributeItem(
        val count: Int,
        val dataId: Int,
    )

    private data class HeaderItem(
        val offset: Long,
        val dataSize: Int,
        val usedDataSize: Int,
    )

    private class DictData(
        val keys: List<KeyItem>,
        val attributes: List<AttributeItem>,
        val headerItemsIdx: List<HeaderItem>,
        val headerItemsAttr: List<HeaderItem>,
        val dataStore: List<HeaderItem>,
        private val dsBase: Long,
    ) {
        val dataTypeSize: IntArray = IntArray(keys.size)
        val attrSize: IntArray = IntArray(attributes.size)
        val baseHashSize: IntArray = IntArray(keys.size)

        fun initialize() {
            val keyHashSize = IntArray(10).also { it[0] = 500 }
            for (i in keys.indices) {
                val key = keys[i]
                val maskedTypeDef = key.dictTypeDef and TYPE_DEF_MASK
                val hashSize = if (i < keyHashSize.size && keyHashSize[i] > 0) {
                    keyHashSize[i]
                } else {
                    DATATYPE_HASH_SIZE.getOrNull(maskedTypeDef)
                        ?.takeIf { it > 0 }
                        ?: throw LexiconFormatException("词库类型异常：$maskedTypeDef")
                }
                baseHashSize[i] = hashSize

                var size = (key.dictTypeDef shr 2) and 4
                if (key.attrIdx < 0) {
                    for (j in key.dataTypes.indices) {
                        if (j > 0 || maskedTypeDef != 4) size += dataTypeSizeOf(key.dataTypes[j])
                    }
                    if (key.attrIdx == -1) size += 4
                    dataTypeSize[i] = size
                } else {
                    val attribute = attributes[key.attrIdx]
                    val nonAttributeCount = key.dataTypes.size - attribute.count
                    if (nonAttributeCount < 0) {
                        throw LexiconFormatException("属性数据类型数量异常")
                    }
                    for (j in 0 until nonAttributeCount) {
                        if (j > 0 || maskedTypeDef != 4) size += dataTypeSizeOf(key.dataTypes[j])
                    }
                    if ((key.dictTypeDef and 0x60) > 0) size += 4
                    size += 4
                    dataTypeSize[i] = size

                    var attributeSize = 0
                    for (j in nonAttributeCount until key.dataTypes.size) {
                        attributeSize += dataTypeSizeOf(key.dataTypes[j])
                    }
                    if ((key.dictTypeDef and 0x40) == 0) attributeSize += 4
                    attrSize[key.attrIdx] = attributeSize
                }
            }
        }

        fun hashStorePosition(indexId: Int): Long {
            val header = headerItemsIdx.getOrNull(indexId)
                ?: throw LexiconFormatException("索引表越界：$indexId")
            return dsBase + header.offset - 8L * baseHashSize[indexId]
        }

        fun attributePositionFromIndex(indexId: Int, attrId: Int, offset: Long): Long {
            val header = headerItemsIdx.getOrNull(indexId)
                ?: throw LexiconFormatException("索引表越界：$indexId")
            return dsBase + header.offset + offset + attrId.toLong() * dataTypeSize[indexId]
        }

        fun attributePositionFromAttribute(keyId: Int, offset: Long): Long {
            val key = keys.getOrNull(keyId)
                ?: throw LexiconFormatException("键表越界：$keyId")
            val header = headerItemsAttr.getOrNull(key.attrIdx)
                ?: throw LexiconFormatException("属性表越界：${key.attrIdx}")
            return dsBase + header.offset + offset
        }

        fun dataPosition(dataId: Int, offset: Long): Long {
            val header = dataStore.getOrNull(dataId)
                ?: throw LexiconFormatException("数据表越界：$dataId")
            return dsBase + header.offset + offset
        }

        private fun dataTypeSizeOf(type: Int): Int {
            if (type !in KEY_ITEM_DATA_TYPE_SIZE.indices) {
                throw LexiconFormatException("数据类型异常：$type")
            }
            return KEY_ITEM_DATA_TYPE_SIZE[type]
        }

        private companion object {
            /** 0xFFFFFF8F 作为 Int 的补码表示。 */
            const val TYPE_DEF_MASK = -0x71
        }
    }
}
