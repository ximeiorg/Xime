package com.kingzcheung.xime.settings

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * multipart 分段读取：上传的大文件直接按区间交给导入管线，
 * 这里保证只读该分段、不多读也不少读（边界偏一个字节都会写出坏包）。
 */
class PartInputStreamTest {

    private fun tempFile(content: String): File =
        File.createTempFile("part-test", ".bin").apply {
            writeBytes(content.toByteArray())
            deleteOnExit()
        }

    private fun readAll(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(5)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `reads only the given region`() {
        val body = "HEADER\r\n\r\nPAYLOAD-0123456789\r\n--boundary--\r\n"
        val file = tempFile(body)
        val start = body.indexOf("PAYLOAD").toLong()
        val length = "PAYLOAD-0123456789".length.toLong()

        PartInputStream(file, start, length).use { stream ->
            assertEquals("PAYLOAD-0123456789", readAll(stream).decodeToString())
        }
    }

    @Test
    fun `empty region reads EOF immediately`() {
        val file = tempFile("anything")
        PartInputStream(file, 2, 0).use { stream ->
            assertEquals(-1, stream.read())
            assertEquals(-1, stream.read(ByteArray(4)))
            assertArrayEquals(ByteArray(0), readAll(stream))
        }
    }

    @Test
    fun `chunked reads never cross the region end`() {
        val file = tempFile("0123456789ABCDEF")
        PartInputStream(file, 3, 7).use { stream ->
            val buf = ByteArray(8)
            val first = stream.read(buf, 0, buf.size)
            assertEquals(7, first)
            assertEquals("3456789", String(buf, 0, first))
            assertEquals(-1, stream.read(buf, 0, buf.size))
        }
    }

    @Test
    fun `single byte reads match region content`() {
        val file = tempFile("xxHELLOyy")
        PartInputStream(file, 2, 5).use { stream ->
            val chars = StringBuilder()
            while (true) {
                val b = stream.read()
                if (b < 0) break
                chars.append(b.toChar())
            }
            assertEquals("HELLO", chars.toString())
        }
    }
}
