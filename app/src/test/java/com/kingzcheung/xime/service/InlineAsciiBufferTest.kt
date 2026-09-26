package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineAsciiBufferTest {
    @Test
    fun `append keeps printable characters including spaces and symbols`() {
        var text = InlineAsciiBuffer.append("Ab", " 3!_")
        assertEquals("Ab 3!_", text)
        text = InlineAsciiBuffer.append(text!!, "")
        assertNull(text)
    }

    @Test
    fun `backspace removes one code unit and empty stays empty`() {
        assertEquals("ab ", InlineAsciiBuffer.deleteLast("ab !"))
        assertEquals("", InlineAsciiBuffer.deleteLast("a"))
        assertEquals("", InlineAsciiBuffer.deleteLast(""))
    }

    @Test
    fun `control and non ascii input are rejected`() {
        assertNull(InlineAsciiBuffer.append("", "enter".replace("enter", "\n")))
        assertNull(InlineAsciiBuffer.append("", "中"))
    }
}
