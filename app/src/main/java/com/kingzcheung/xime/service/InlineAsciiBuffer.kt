package com.kingzcheung.xime.service

/** inline_ascii 的纯文本状态操作，供按键路由与单元测试共用。 */
internal object InlineAsciiBuffer {
    internal fun append(current: String, text: String): String? {
        if (text.isEmpty() || !text.all { it == ' ' || it in '!'..'~' }) return null
        return current + text
    }

    internal fun deleteLast(current: String): String = current.dropLast(1)
}
