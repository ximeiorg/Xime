package com.kingzcheung.xime.calculator

private val calculatorKeyPattern = Regex("[0-9]")
private val calculatorKeys = listOf("+", "-", "*", "/", ".")

/**
 * 计算器模式按键路由的结果。
 */
sealed class CalculatorRouteResult {
    /** 按键已被计算器路由处理，调用者应跳过后续 Rime 管线 */
    data class Handled(val commitText: String) : CalculatorRouteResult()
    /** 按键不由计算器路由处理，调用者应按正常流程继续 */
    data object NotHandled : CalculatorRouteResult()
}

/**
 * 计算器模式按键路由 — 决定按键应由计算器引擎处理还是直接提交。
 *
 * 返回 [CalculatorRouteResult.Handled] 表示按键已被路由处理，
 * 调用者应提交 [commitText] 并跳过后续 Rime 管线。
 * 返回 [CalculatorRouteResult.NotHandled] 表示调用者应按正常流程继续处理。
 *
 * 路由规则：
 * - 上滑字面数字：追加到计算器表达式并直接提交
 * - CommonSymbol 键盘：所有单字符键直接提交，不经过计算器（符号无计算器支持）
 * - Number 键盘：数字/运算符/小数点先经计算器引擎，再直接提交（不经过 Rime）
 * - 其他键盘：返回 [NotHandled]
 */
fun routeCalculatorKey(
    key: String,
    isNumberKeyboard: Boolean,
    isCommonSymbolKeyboard: Boolean,
    calculatorEngine: CalculatorEngine,
    isLiteralSwipeDigit: Boolean = false,
): CalculatorRouteResult {
    if (isLiteralSwipeDigit) {
        calculatorEngine.handleDigit(key)
        return CalculatorRouteResult.Handled(key)
    }

    if (key.length == 1 && isCommonSymbolKeyboard) {
        return CalculatorRouteResult.Handled(key)
    }

    if (isNumberKeyboard) {
        if (key.matches(calculatorKeyPattern) || key in calculatorKeys) {
            if (key.matches(calculatorKeyPattern) || key == ".") {
                calculatorEngine.handleDigit(key)
            } else {
                calculatorEngine.handleOperator(key)
            }
        }
        return CalculatorRouteResult.Handled(key)
    }

    return CalculatorRouteResult.NotHandled
}
