package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

data class CandidateState(
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val inputText: String = "",
    val preeditText: String = "",
    val isComposing: Boolean = false,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val associationCandidates: List<String> = emptyList(),
    val pendingEnglishText: String = "",
    /** inline_ascii 临时英文编辑态；与逐字上屏英文态 [pendingEnglishText] 分离。 */
    val isInlineAsciiActive: Boolean = false,
    val inlineAsciiText: String = "",
    val isShowingRecentClipboard: Boolean = false,
    /** 当前宿主是否支持英文候选的"回删替换"（终端等受限宿主为 false，不展示英文候选）。 */
    val englishReplaceSupported: Boolean = true,
    /** 候选词变换映射（插件 candidate_transform 能力）：与 [candidates] 平行；
     *  空列表 = 纯引擎语义（显示 index 即引擎 index）。见 CandidateAction。 */
    val candidateActions: List<CandidateAction> = emptyList(),
    /** 跨页全量候选（仅候选展开态时由服务层填充，供本地分页与单字筛选）。
     *  空列表 = 未填充或引擎无候选。全局索引用于 selectCandidateByGlobalIndex。 */
    val expandedCandidates: List<RimeCandidate> = emptyList(),
    /** 编码显示串中的光标偏移（字符，-1 = 末尾/非编辑态）。
     *  全键盘组合态滑动编辑时由服务层从 editingCaretPos 映射（快照比对自愈），供气泡竖线与输入框光标。 */
    val preeditCaretPos: Int = -1,
)
