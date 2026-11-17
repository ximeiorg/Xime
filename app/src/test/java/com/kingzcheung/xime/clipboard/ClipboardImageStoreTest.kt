package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.data.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 剪贴板图片存储与配额逻辑测试（纯 JVM，无需设备）。
 *
 * 覆盖：魔数嗅探 / MIME 映射 / 内容寻址落盘 / 相对路径换算 /
 * 孤儿清理 / 配额淘汰（数量、容量、置顶与保护集豁免、已消费优先）。
 */
class ClipboardImageStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun filesDir(): File = tempFolder.root

    private fun store(): ClipboardImageStore =
        ClipboardImageStore(ClipboardImageStore.rootDirOf(filesDir()))

    // ── 构造测试用字节 ──

    private fun png(bytes: Int = 64): ByteArray {
        val data = ByteArray(bytes)
        data[0] = 0x89.toByte(); data[1] = 0x50; data[2] = 0x4E; data[3] = 0x47
        data[4] = 0x0D; data[5] = 0x0A; data[6] = 0x1A; data[7] = 0x0A
        return data
    }

    private fun jpeg(bytes: Int = 64): ByteArray {
        val data = ByteArray(bytes)
        data[0] = 0xFF.toByte(); data[1] = 0xD8.toByte(); data[2] = 0xFF.toByte()
        return data
    }

    private fun bytesWith(prefix: ByteArray, size: Int = 64): ByteArray {
        val data = ByteArray(size)
        prefix.copyInto(data, endIndex = minOf(prefix.size, size))
        return data
    }

    // ── 魔数嗅探 ──

    @Test
    fun `魔数嗅探识别常见图片格式`() {
        assertEquals("png", ClipboardImageStore.sniffExtension(png()))
        assertEquals("jpg", ClipboardImageStore.sniffExtension(jpeg()))
        assertEquals("gif", ClipboardImageStore.sniffExtension(bytesWith("GIF89a".toByteArray())))
        assertEquals("bmp", ClipboardImageStore.sniffExtension(bytesWith("BM".toByteArray())))
        assertEquals(
            "webp",
            ClipboardImageStore.sniffExtension(
                bytesWith("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1))
            )
        )
        // HEIF：4 字节长度 + "ftyp" + brand
        assertEquals(
            "heic",
            ClipboardImageStore.sniffExtension(
                bytesWith("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1))
            )
        )
        assertEquals(
            "avif",
            ClipboardImageStore.sniffExtension(
                bytesWith("\u0000\u0000\u0000\u0018ftypavif".toByteArray(Charsets.ISO_8859_1))
            )
        )
    }

    @Test
    fun `非图片与过短字节不识别`() {
        assertNull(ClipboardImageStore.sniffExtension(bytesWith("%PDF-1.7".toByteArray())))
        assertNull(ClipboardImageStore.sniffExtension(byteArrayOf(1, 2, 3)))
        assertNull(ClipboardImageStore.sniffExtension(ByteArray(0)))
        // ftyp 但 brand 未知（如 mp4 的 isom）→ 不当作图片
        assertNull(
            ClipboardImageStore.sniffExtension(
                bytesWith("\u0000\u0000\u0000\u0018ftypisom".toByteArray(Charsets.ISO_8859_1))
            )
        )
    }

    // ── MIME 映射与判定 ──

    @Test
    fun `MIME 与扩展名互转`() {
        assertEquals("png", ClipboardImageStore.extensionForMime("image/png"))
        assertEquals("jpg", ClipboardImageStore.extensionForMime("image/jpeg"))
        assertEquals("jpg", ClipboardImageStore.extensionForMime("IMAGE/JPEG; charset=binary"))
        assertEquals("heic", ClipboardImageStore.extensionForMime("image/heic"))
        assertNull(ClipboardImageStore.extensionForMime("application/pdf"))
        assertNull(ClipboardImageStore.extensionForMime(null))

        assertEquals("image/png", ClipboardImageStore.mimeForExtension("png"))
        assertEquals("image/jpeg", ClipboardImageStore.mimeForExtension("JPG"))
        assertNull(ClipboardImageStore.mimeForExtension("pdf"))
    }

    @Test
    fun `扩展名判定优先 MIME 其次魔数`() {
        // MIME 可用：以 MIME 为准
        assertEquals("png", ClipboardImageStore.resolveExtension("image/png", jpeg()))
        // MIME 缺失或不可用：回退魔数
        assertEquals("jpg", ClipboardImageStore.resolveExtension(null, jpeg()))
        assertEquals("png", ClipboardImageStore.resolveExtension("application/octet-stream", png()))
        // 两者都不行 → 不可采集
        assertNull(ClipboardImageStore.resolveExtension("application/pdf", bytesWith("%PDF".toByteArray())))
    }

    // ── 落盘与路径 ──

    @Test
    fun `内容寻址落盘并复用同 hash 文件`() {
        val store = store()
        val data = png(128)

        val relative = store.save(data, "abc123", "png")
        assertEquals("clipboard_images/abc123.png", relative)
        val file = store.absoluteFile(relative!!)
        assertTrue(file.exists())
        assertEquals(128L, file.length())

        // 重复保存同一内容：直接复用，不报错、大小不变
        assertEquals(relative, store.save(data, "abc123", "png"))
        assertEquals(128L, file.length())
    }

    @Test
    fun `落盘拒绝空内容与超限图片`() {
        val store = store()
        assertNull(store.save(ByteArray(0), "empty", "png"))
        assertNull(
            store.save(
                ByteArray((ClipboardImageStore.MAX_IMAGE_BYTES + 1).toInt()),
                "huge",
                "png",
            )
        )
        assertFalse(store.absoluteFile("clipboard_images/huge.png").exists())
    }

    // ── 采集准入（大小限制，决策 D8：不压缩，超限拒收） ──

    @Test
    fun `大小上限常量锁定为 5MB 与 4096px`() {
        assertEquals(5L * 1024 * 1024, ClipboardImageStore.MAX_IMAGE_BYTES)
        assertEquals(4096, ClipboardImageStore.MAX_IMAGE_EDGE)
    }

    @Test
    fun `字节超限与边长超限分别给出拒收原因`() {
        val max = ClipboardImageStore.MAX_IMAGE_BYTES
        val edge = ClipboardImageStore.MAX_IMAGE_EDGE

        // 边界值本身允许通过（判定用 >）
        assertNull(ClipboardImageStore.rejectReason(max, edge, 100))
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES,
            ClipboardImageStore.rejectReason(max + 1, 100, 100),
        )
        assertNull(ClipboardImageStore.rejectReason(1024, edge, edge))
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_DIMENSION,
            ClipboardImageStore.rejectReason(1024, edge + 1, 100),
        )
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_DIMENSION,
            ClipboardImageStore.rejectReason(1024, 100, edge + 1),
        )
        // 两者都超：先报字节（提示更直白）
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES,
            ClipboardImageStore.rejectReason(max + 1, 9999, 9999),
        )
    }

    @Test
    fun `尺寸解码失败时不误杀（只按字节判定）`() {
        // BitmapFactory 解不出尺寸（如旧设备上的 AVIF/HEIC）→ 0x0，不应因此拒收
        assertNull(ClipboardImageStore.rejectReason(1024, 0, 0))
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES,
            ClipboardImageStore.rejectReason(ClipboardImageStore.MAX_IMAGE_BYTES + 1, 0, 0),
        )
    }

    // ── 可配置阈值（Phase 2：只有单张上限可配，其余为内置限制） ──

    @Test
    fun `单张上限可配置而最长边仍走内置常量`() {
        val eightMb = 8L * 1024 * 1024
        // 上限放宽到 8MB：默认 5MB 下会被拒的字节现在放行（边界用 >）
        assertNull(ClipboardImageStore.rejectReason(eightMb, 100, 100, maxBytes = eightMb))
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES,
            ClipboardImageStore.rejectReason(eightMb + 1, 100, 100, maxBytes = eightMb),
        )
        // 最长边不可配：无论字节上限怎么改，都按内置 4096px 判定
        assertNull(ClipboardImageStore.rejectReason(1024, ClipboardImageStore.MAX_IMAGE_EDGE, 100, maxBytes = eightMb))
        assertEquals(
            ClipboardImageStore.RejectReason.TOO_LARGE_DIMENSION,
            ClipboardImageStore.rejectReason(1024, ClipboardImageStore.MAX_IMAGE_EDGE + 1, 100, maxBytes = eightMb),
        )
    }

    @Test
    fun `落盘兜底校验使用传入上限且失败不留文件`() {
        val store = store()
        val data = png(2048)
        assertTrue(store.save(data, "cfg-ok", "png") != null)
        assertNull(store.save(data, "cfg-rejected", "png", maxBytes = 1024))
        assertFalse(File(ClipboardImageStore.rootDirOf(filesDir()), "cfg-rejected.png").exists())
    }

    @Test
    fun `相对路径换算仅限存储目录内`() {
        val filesDir = filesDir()
        val store = store()
        val inside = store.absoluteFile("clipboard_images/hash.png")
        inside.parentFile?.mkdirs()
        inside.writeBytes(png())

        assertEquals(
            "clipboard_images/hash.png",
            ClipboardImageStore.relativePathOf(filesDir, inside)
        )
        // files/ 根下的其它文件不属于剪贴板图片目录
        val outside = File(filesDir, "rime/xime.yaml").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        assertNull(ClipboardImageStore.relativePathOf(filesDir, outside))
    }

    @Test
    fun `删除与孤儿清理`() {
        val store = store()
        val keep = store.save(png(), "keep", "png")!!
        val orphan = store.save(png(96), "orphan", "png")!!
        // 模拟上次淘汰残留的半成品文件
        File(ClipboardImageStore.rootDirOf(filesDir()), "broken.png.tmp").writeBytes(png())

        assertTrue(store.absoluteFile(orphan).exists())
        assertTrue(store.delete(orphan))
        assertFalse(store.absoluteFile(orphan).exists())

        // 再写一个孤儿（不入库）后清理：只保留已知路径
        store.save(png(80), "orphan2", "png")
        val removed = store.cleanupOrphans(setOf(keep))
        assertTrue("应清理未入库文件与 .tmp", removed >= 1)
        assertTrue(store.absoluteFile(keep).exists())
        assertFalse(File(ClipboardImageStore.rootDirOf(filesDir()), "orphan2.png").exists())
        assertFalse(File(ClipboardImageStore.rootDirOf(filesDir()), "broken.png.tmp").exists())
    }

    // ── 配额淘汰 ──

    private fun image(
        id: Long,
        sizeBytes: Long = 1000,
        timestamp: Long = id,
        pinned: Boolean = false,
        consumed: Boolean = false,
    ) = ClipboardItem(
        id = id,
        text = "",
        timestamp = timestamp,
        isPinned = pinned,
        consumed = consumed,
        type = ClipboardEntry.TYPE_IMAGE,
        imagePath = "clipboard_images/$id.png",
        imageHash = "hash$id",
        sizeBytes = sizeBytes,
    )

    @Test
    fun `未超限时不淘汰`() {
        val images = listOf(image(1), image(2), image(3))
        assertTrue(
            ClipboardImageStore.selectEvictions(images, maxImages = 10, maxTotalBytes = 10_000)
                .isEmpty()
        )
    }

    @Test
    fun `数量超限按时间戳最旧优先淘汰`() {
        val images = listOf(image(1), image(2), image(3))
        val evicted = ClipboardImageStore.selectEvictions(images, maxImages = 2, maxTotalBytes = 10_000)
        assertEquals(listOf(1L), evicted.map { it.id })
    }

    @Test
    fun `容量超限淘汰直至满足上限`() {
        val images = listOf(image(1, sizeBytes = 600), image(2, sizeBytes = 600), image(3, sizeBytes = 600))
        val evicted = ClipboardImageStore.selectEvictions(
            images,
            maxImages = 10,
            maxTotalBytes = 700,
        )
        assertEquals(listOf(1L, 2L), evicted.map { it.id })
    }

    @Test
    fun `置顶图片永不自动淘汰`() {
        val images = listOf(image(1, pinned = true), image(2), image(3))
        // 上限 1、共 3 张（1 张置顶）：置顶豁免，只能淘汰 2、3 才可能收敛到 1
        val evicted = ClipboardImageStore.selectEvictions(images, maxImages = 1, maxTotalBytes = 10_000)
        assertEquals(listOf(2L, 3L), evicted.map { it.id })
        assertFalse("置顶图片不得出现在淘汰列表", evicted.any { it.isPinned })
    }

    @Test
    fun `保护集不淘汰（系统剪贴板仍指向该文件）`() {
        val images = listOf(image(1), image(2), image(3))
        val evicted = ClipboardImageStore.selectEvictions(
            images,
            protectedPaths = setOf("clipboard_images/1.png"),
            maxImages = 1,
            maxTotalBytes = 10_000,
        )
        assertEquals(listOf(2L, 3L), evicted.map { it.id })
    }

    @Test
    fun `不可淘汰项已超上限时不再淘汰任何条目`() {
        val images = listOf(
            image(1, pinned = true),
            image(2, pinned = true),
            image(3, pinned = true),
        )
        assertTrue(
            ClipboardImageStore.selectEvictions(images, maxImages = 1, maxTotalBytes = 10_000)
                .isEmpty()
        )
    }

    @Test
    fun `已消费的图片优先淘汰`() {
        // id=3 虽然更新，但已被消费 → 优先淘汰
        val images = listOf(image(1), image(2), image(3, consumed = true))
        val evicted = ClipboardImageStore.selectEvictions(images, maxImages = 2, maxTotalBytes = 10_000)
        assertEquals(listOf(3L), evicted.map { it.id })
    }

    @Test
    fun `文本条目不参与图片配额统计`() {
        val text = ClipboardItem(id = 9, text = "hello", timestamp = 1, sizeBytes = 999_999)
        val images = listOf(image(1), text)
        assertTrue(
            ClipboardImageStore.selectEvictions(images, maxImages = 1, maxTotalBytes = 10_000)
                .isEmpty()
        )
    }
}