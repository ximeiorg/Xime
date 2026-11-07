package com.kingzcheung.xime.settings

import java.io.File
import java.io.IOException

/**
 * 无线导入页的文本编辑写入策略。
 *
 * 浏览器端能改手机上的 rime 配置，但只允许改「用户配置区」的文本文件：
 * 编译产物（build/）、回收站（trash/）、用户词典（*.userdb）、二进制文件一律拒绝。
 * 单文件上限 2MB，避免把大词典整个读进内存编辑。
 */
internal object WirelessTextFilePolicy {

    const val MAX_WRITE_BYTES = 2L * 1024 * 1024

    private val TEXT_EXTENSIONS = setOf(
        "yaml", "yml", "txt", "md", "json", "conf", "lua",
    )

    /** 文件名是否属于可编辑文本（按扩展名判断，不做 IO）。 */
    fun isEditableName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /**
     * 目标是否允许写入：[rimeDir] 内的已存在文本文件，且不在 build/trash 下、
     * 体积不超过 [MAX_WRITE_BYTES]。
     */
    fun isWritableTarget(rimeDir: File, target: File): Boolean {
        val root = rimeDir.canonicalFile
        val file = target.canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return false
        if (!file.isFile) return false
        if (!isEditableName(file.name)) return false
        if (file.length() > MAX_WRITE_BYTES) return false
        // 用户词典目录/文件（leveldb）不是配置文本
        if (file.name.contains(".userdb")) return false
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val segments = relative.split('/')
        if (segments.any { it == "build" || it == "trash" || it.endsWith(".userdb") }) {
            return false
        }
        return true
    }

    /**
     * 原子写入：先写同目录临时文件，再 rename 覆盖目标。
     * 直接覆盖写到一半进程被杀会留下截断的 YAML，librime 部署解析失败。
     */
    fun writeTextAtomically(target: File, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) return false
        val parent = target.parentFile ?: return false
        val tmp = File.createTempFile(".xime_write_", ".tmp", parent)
        return try {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) {
                true
            } else {
                // 少数文件系统不支持 rename 覆盖：退化为直接写
                target.writeBytes(bytes)
                tmp.delete()
                true
            }
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }
}
