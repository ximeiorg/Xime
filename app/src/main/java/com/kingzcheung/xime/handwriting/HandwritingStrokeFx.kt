package com.kingzcheung.xime.handwriting

import com.kingzcheung.xime.handwriting.OverlappedHandwritingRecognizer.Segment

/** 停顿分割阈值（自适应基准）：笔画越多越接近写完，定型越快；起笔阶段保守防误分割。 */
const val HW_SPLIT_PAUSE_BASE_MS = 700L

/** 每多一笔，停顿阈值递减量（ms）。 */
const val HW_SPLIT_PAUSE_STEP_MS = 50L

/** 停顿分割阈值下限（ms）。 */
const val HW_SPLIT_PAUSE_MIN_MS = 500L

/** 识别窗口笔画上限：超过后固化最早段滑窗（正常分割靠停顿，此为不停顿兜底）。 */
const val HW_RECOGNIZE_WINDOW_LIMIT = 25

/**
 * 淡出的停顿门槛（ms）：段边界笔间间隔达到此值才视为"换字"，
 * 前面段笔画才变淡——连笔中途的切分抖动（如"在"=[一][丿][丨]）不触发淡出，
 * 避免单字没写完部首就消失。
 */
const val HW_FADE_GAP_MS = 400L

/** 删除键长按判定阈值（ms）：达到即视为"撤笔"，否则按删已上屏文本处理。 */
const val HW_DELETE_LONG_PRESS_MS = 500L

/** 手写页删除键的两种语义。 */
enum class HandwritingDeleteGesture {
    /** 删已上屏文本（与其它键盘的退格一致） */
    DELETE_TEXT,
    /** 撤销最后一笔未固化笔画并重新识别 */
    UNDO_STROKE,
}

/**
 * 删除键语义判定：短按删文本；长按才撤笔，且必须有笔画可撤（窗口已清空则回退为删文本）。
 *
 * @param longPress 按下到抬起的时长达到 [HW_DELETE_LONG_PRESS_MS]
 * @param hasStrokes 识别窗口内还有笔画
 */
fun handwritingDeleteGesture(longPress: Boolean, hasStrokes: Boolean): HandwritingDeleteGesture =
    if (longPress && hasStrokes) HandwritingDeleteGesture.UNDO_STROKE
    else HandwritingDeleteGesture.DELETE_TEXT

/**
 * 退格要删掉的字符数：只删最后一个字，不整段撤销活动区。
 *
 * 叠写（连写）时活动区可同时包含多个字（[activeLen] > 1），按活动区整体删除会把
 * 连着写的几个字一起清掉。识别段与上屏字一一对应，所以按最后一段的长度删。
 *
 * @param activeLen 活动区字数（尚未固化的已上屏字）
 * @param lastSegLen 最后一个识别段占的字符数（单字模型通常为 1）
 */
fun handwritingDeleteCharCount(activeLen: Int, lastSegLen: Int): Int =
    if (activeLen <= 0) 0 else lastSegLen.coerceIn(1, activeLen)

/**
 * 手写叠写共享常量与纯函数（主手写键盘与手写查词键盘共用）。
 */
object HandwritingStrokeFx {

    /**
     * 停顿分割阈值（自适应）：笔画越多字越接近写完，定型越快；
     * 起笔阶段保守（1~2 笔时停顿多半是构思，防慢写者单字中途被误分割）。
     *
     * @param windowStrokes 当前识别窗口（未固化）笔画数。
     */
    fun splitPauseMs(windowStrokes: Int): Long =
        (HW_SPLIT_PAUSE_BASE_MS - (windowStrokes - 1) * HW_SPLIT_PAUSE_STEP_MS)
            .coerceAtLeast(HW_SPLIT_PAUSE_MIN_MS)

    /** 窗口的笔间时间间隔（gaps[j] = 第 j 笔起笔与上一笔收笔的间隔，gaps[0]=0）。 */
    fun windowGaps(window: List<List<StrokePoint>>): List<Long> =
        window.mapIndexed { idx, stroke ->
            if (idx == 0) 0L else stroke.first().timeMs - window[idx - 1].last().timeMs
        }

    /**
     * 计算"已完成字"的笔画数（淡出范围）：从最后一段往前找第一个
     * 换字停顿边界（间隔 ≥ [HW_FADE_GAP_MS]），该边界之前的段全部视为已完成。
     * 找不到（全程连笔，单字书写中途）返回 0——不淡出。
     */
    fun settledStrokesBeforeCurrent(
        segments: List<Segment>,
        gaps: List<Long>,
    ): Int {
        for (k in segments.size - 1 downTo 1) {
            val start = segments[k].startStroke
            if (start < gaps.size && gaps[start] >= HW_FADE_GAP_MS) {
                return start
            }
        }
        return 0
    }
}
