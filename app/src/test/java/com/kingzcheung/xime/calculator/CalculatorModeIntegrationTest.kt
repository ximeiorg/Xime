package com.kingzcheung.xime.calculator

import org.junit.Assert.*
import org.junit.Test

/**
 * 计算器模式集成测试 — 验证键盘布局与 CalculatorEngine 的交互行为。
 *
 * 直接测试生产代码 [routeCalculatorKey] 函数，确保：
 * - Number 键盘下的数字/运算符先经计算器引擎再直接提交（不绕过计算器）
 * - CommonSymbol 键盘下的符号直接提交（不经过计算器）
 * - 全键盘下的计算器键返回 NotHandled（由下游 Rime 管线处理）
 *
 * 如果将来有人修改 [XimeInputMethodService.handleKeyPress] 中的计算器路由逻辑，
 * 必须同步修改 [routeCalculatorKey]，这组测试会立即暴露不匹配行为。
 */
class CalculatorModeIntegrationTest {

    @Test
    fun `上滑字面数字继续已有计算器表达式`() {
        val engine = CalculatorEngine()
        engine.handleDigit("7")
        engine.handleOperator("-")
        engine.handleDigit("2")

        val result = routeCalculatorKey(
            key = "1",
            isNumberKeyboard = false,
            isCommonSymbolKeyboard = false,
            calculatorEngine = engine,
            isLiteralSwipeDigit = true,
        )

        assertEquals(CalculatorRouteResult.Handled("1"), result)
        assertEquals("7-21 = -14", engine.getCandidate())
    }

    @Test
    fun `Number键盘_数字触发计算器并提交`() {
        val engine = CalculatorEngine()

        val result = routeCalculatorKey(
            key = "5",
            isNumberKeyboard = true,
            isCommonSymbolKeyboard = false,
            calculatorEngine = engine,
        )

        assertTrue("Number keyboard key should be handled", result is CalculatorRouteResult.Handled)
        assertEquals("5", (result as CalculatorRouteResult.Handled).commitText)
        assertNull("Only digit entered, calculator should not be active", engine.getCandidate())
    }

    @Test
    fun `Number键盘_数字加运算符进入计算器模式`() {
        val engine = CalculatorEngine()

        routeCalculatorKey("1", true, false, engine)
        routeCalculatorKey("+", true, false, engine)
        routeCalculatorKey("2", true, false, engine)

        assertTrue(engine.isActive())
        assertEquals("1+2 = 3", engine.getCandidate())
    }

    @Test
    fun `Number键盘_链式计算`() {
        val engine = CalculatorEngine()

        routeCalculatorKey("4", true, false, engine)
        routeCalculatorKey("*", true, false, engine)
        routeCalculatorKey("5", true, false, engine)
        routeCalculatorKey("+", true, false, engine)
        routeCalculatorKey("2", true, false, engine)

        assertTrue(engine.isActive())
        assertEquals("4*5+2 = 22", engine.getCandidate())
    }

    @Test
    fun `Number键盘_小数计算`() {
        val engine = CalculatorEngine()

        routeCalculatorKey("0", true, false, engine)
        routeCalculatorKey(".", true, false, engine)
        routeCalculatorKey("1", true, false, engine)
        routeCalculatorKey("+", true, false, engine)
        routeCalculatorKey("0", true, false, engine)
        routeCalculatorKey(".", true, false, engine)
        routeCalculatorKey("2", true, false, engine)

        assertTrue(engine.isActive())
        assertEquals("0.1+0.2 = 0.3", engine.getCandidate())
    }

    @Test
    fun `CommonSymbol键盘_直接提交不经过计算器`() {
        val engine = CalculatorEngine()

        val result = routeCalculatorKey(
            key = "@",
            isNumberKeyboard = false,
            isCommonSymbolKeyboard = true,
            calculatorEngine = engine,
        )

        assertTrue("CommonSymbol key should be handled", result is CalculatorRouteResult.Handled)
        assertEquals("@", (result as CalculatorRouteResult.Handled).commitText)
        assertNull("Calculator engine should not be affected", engine.getCandidate())
        assertFalse(engine.isActive())
    }

    @Test
    fun `CommonSymbol键盘_多个符号直接提交`() {
        val engine = CalculatorEngine()

        for (sym in listOf("#", "\$", "%", "&", "?")) {
            val result = routeCalculatorKey(sym, false, true, engine)
            assertTrue("Symbol $sym should be handled", result is CalculatorRouteResult.Handled)
            assertEquals(sym, (result as CalculatorRouteResult.Handled).commitText)
        }

        assertNull(engine.getCandidate())
        assertFalse(engine.isActive())
    }

    @Test
    fun `全键盘_计算器键返回NotHandled交下游管线`() {
        val engine = CalculatorEngine()

        val result = routeCalculatorKey(
            key = "5",
            isNumberKeyboard = false,
            isCommonSymbolKeyboard = false,
            calculatorEngine = engine,
        )

        assertTrue("Full keyboard key should NOT be handled", result is CalculatorRouteResult.NotHandled)
        assertNull("Calculator engine should not be touched", engine.getCandidate())
    }
}
