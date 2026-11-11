package com.kingzcheung.xime.service

/**
 * 判断光标/选区变化（onUpdateSelection）是否应打断待上屏组合态。
 *
 * 用户移动光标后，旧编码继续与新按键合并上屏会造成错位；应丢弃组合态，
 * 让下一键从新光标处重新开始。两类非用户移动的选区变化除外：
 * 选区未变化；光标仍在编辑器 composing 区域内（input-box 模式
 * setComposingText 自身引起的光标回调）。
 */
internal fun shouldBreakCompositionOnSelectionChange(
    oldSelStart: Int,
    oldSelEnd: Int,
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Boolean {
    if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return false
    // 无有效选区信息（宿主文本不可见等）时不打断
    if (newSelStart < 0 || newSelEnd < 0) return false
    // 光标仍在 composing 区域内：自身写入编码引起，非用户移动
    if (candidatesStart >= 0 && newSelStart >= candidatesStart && newSelEnd <= candidatesEnd) return false
    return true
}

/**
 * onUpdateSelection 的重入闸门。
 *
 * 宿主（如 Compose）会在输入法写完选区后同步回调 updateSelection，
 * 若不在回调里拦住这次自触发，就会无限递归导致栈溢出。
 */
internal class SelectionUpdateGate {
    private var handling = false

    /** 进入处理区；已在处理中返回 false（自触发回调，调用方直接返回即可）。 */
    fun tryEnter(): Boolean {
        if (handling) return false
        handling = true
        return true
    }

    /** 退出处理区，需放在 try/finally 中。 */
    fun exit() {
        handling = false
    }
}

/**
 * 自身写入（上屏/删除）引起的光标位移识别。
 *
 * 上屏后宿主回传的 onUpdateSelection 与「用户移动光标」无法区分，会把同一次按键刚建立的
 * 组合态丢弃（满码上屏后再按的字母被吞）。这里记录自身写入的预期位移，回调命中即认定
 * 不是用户移动；超时、方向相反或位移超出预期时仍按真实移动处理。
 */
internal class SelfWriteCaretGuard(
    /** 预期位移的保留时间：宿主回调可能迟一两帧。 */
    private val windowMs: Long = 800L,
    /** 写入后极短时间内的回调一律算自身写入（位移可能被宿主合并/规范化）。 */
    private val recentWriteMs: Long = 120L,
) {
    private var expectedDelta = 0
    private var lastWriteAt = 0L

    /** 记录一次自身写入：[caretDelta] 为预期光标位移（上屏 +长度，删除 -长度）。 */
    fun noteWrite(caretDelta: Int, now: Long) {
        if (caretDelta == 0) return
        expectedDelta += caretDelta
        lastWriteAt = now
    }

    /** 该次选区变化是否由自身写入引起（命中后按观测值消费预期，支持分批/合并回调）。 */
    fun consumedBySelfWrite(oldSelStart: Int, newSelStart: Int, now: Long): Boolean {
        if (expectedDelta == 0) return false
        if (now - lastWriteAt > windowMs) {
            expectedDelta = 0
            return false
        }
        // 旧光标不可用（部分输入框首帧为 -1）：算不出位移，按自身写入处理
        if (oldSelStart < 0) {
            expectedDelta = 0
            lastWriteAt = 0L
            return true
        }
        val observed = newSelStart - oldSelStart
        if (observed == 0) return false
        val elapsed = now - lastWriteAt
        val sameDirection = (observed > 0) == (expectedDelta > 0)
        if (!sameDirection || kotlin.math.abs(observed) > kotlin.math.abs(expectedDelta)) {
            // 位移对不上预期：写入后极短时间内的回调仍算自身写入
            if (elapsed <= recentWriteMs) {
                expectedDelta = 0
                lastWriteAt = 0L
                return true
            }
            expectedDelta = 0
            return false
        }
        expectedDelta -= observed
        if (expectedDelta == 0) lastWriteAt = 0L
        return true
    }

    /** 输入会话变化时清空。 */
    fun reset() {
        expectedDelta = 0
        lastWriteAt = 0L
    }
}

/**
 * 组合区起点：由「刚写完时的光标（组合区末尾）− 编码长度」反推。
 *
 * 编码显示在输入框时，它是和上屏文字混在同一段文本里的，输入法侧再维护一份
 * 编码内容/光标（影子状态）必然在上屏、撤销、宿主改选区后失真，用失真状态算起点
 * 会让光标乱跳。因此起点一律用宿主刚回报的真实光标反推，且必须在批处理结束后读取
 * （`beginBatchEdit` 期间读到的是旧选区）。
 *
 * @param regionEnd 刚写完编码后宿主回报的光标（组合区末尾）
 * @param textLength 刚写入的编码长度（含音节分隔符）
 * @return 组合区起点；读数缺失或不自洽（光标比编码还靠前）时返回 null，
 *         调用方退化为不动光标
 */
internal fun composingStartFromRegionEnd(regionEnd: Int, textLength: Int): Int? {
    if (regionEnd < 0 || textLength < 0) return null
    val start = regionEnd - textLength
    return if (start < 0) null else start
}

/**
 * 丢弃输入框内的未上屏编码后应恢复的光标位置；null = 不删除编码文本。
 *
 * 编码在输入框里以 composing 区域存在（宿主不会在光标移出时定稿），
 * 借 setComposingText("") 可原地删除；但它会把光标带到编码起点，故需返回
 * 用户位置在删除后的等效坐标。拖动选区（不动文本）、无组合区、
 * 光标仍在编码内（策略已跳过，此处兜底）时返回 null。
 */
internal fun restoreCaretAfterInputBoxComposingRemoval(
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Int? {
    if (candidatesStart < 0 || candidatesEnd <= candidatesStart) return null
    if (newSelStart != newSelEnd) return null
    if (newSelStart > candidatesStart && newSelStart < candidatesEnd) return null
    return if (newSelStart >= candidatesEnd) {
        newSelStart - (candidatesEnd - candidatesStart)
    } else {
        newSelStart
    }
}
