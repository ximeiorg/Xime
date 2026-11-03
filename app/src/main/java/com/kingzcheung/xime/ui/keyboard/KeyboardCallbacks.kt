package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.rime.RimeComposition
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

data class KeyboardCallbacks(
    val onKeyPress: (String, Boolean) -> Unit,
    val onKeyPressDown: ((String) -> Unit)? = null,
    val onKeyRelease: ((String) -> Unit)? = null,
    val onCandidateSelect: (Int) -> Unit,
    // 长按候选删除自造词：index 为候选栏显示索引（候选栏→服务层透传）
    val onCandidateDelete: ((Int) -> Unit)? = null,
    /**
     * 候选展开页点选（本地分页）：globalIndex 为跨页全量候选列表中的索引，
     * 服务层走 select_candidate 全局索引（区别于候选栏的当前页内索引）。
     */
    val onGlobalCandidateSelect: ((Int) -> Unit)? = null,
    /** 候选展开页长按删除自造词：globalIndex 为跨页全量候选列表中的索引。 */
    val onGlobalCandidateDelete: ((Int) -> Unit)? = null,
    /** 用户展开候选页时请求服务层拉取跨页全量候选（本地分页数据源）。 */
    val onRequestExpandedCandidates: (() -> Unit)? = null,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onClearAssociation: (() -> Unit)? = null,
    val onToggleDarkMode: (() -> Unit)? = null,
    val onClipboard: (() -> Unit)? = null,
    val onClipboardSelect: ((String) -> Unit)? = null,
    /** 快捷发送条目点选（只上屏，不写回系统剪贴板）。 */
    val onQuickSendSelect: ((String) -> Unit)? = null,
    /**
     * 剪贴板**图片**条目点选：服务层先尝试 `commitContent` 直插宿主，
     * 宿主不支持图片 MIME 时降级为写入系统剪贴板并提示用户长按输入框粘贴。
     */
    val onClipboardImageSelect: ((com.kingzcheung.xime.clipboard.ClipboardItem) -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val onDeleteText: ((Int) -> Unit)? = null,
    val onQuickSend: (() -> Unit)? = null,
    val onKeyboardResize: (() -> Unit)? = null,
    val onReloadConfig: (() -> Unit)? = null,
    val onSettings: (() -> Unit)? = null,
    val onSwitchSchema: ((String) -> Unit)? = null,
    val onToggleSchemaSwitch: ((SchemaSwitchUiState) -> Unit)? = null,
    val onHideKeyboard: (() -> Unit)? = null,
    val onSwitchKeyboard: (() -> Unit)? = null,
    val onToolbarEditingAction: ((String) -> Unit)? = null,
    val onCommitImage: ((String) -> Unit)? = null,
    val onVoiceModeChange: ((Boolean) -> Unit)? = null,
    val onVoiceStickyToggle: (() -> Unit)? = null,
    val onPageDown: (() -> Unit)? = null,
    val onPageUp: (() -> Unit)? = null,
    val onCursorMove: ((Int) -> Unit)? = null,
    val onGestureAction: ((GestureAction, String) -> Unit)? = null,
    val onUpdateToolbarButtons: ((List<String>) -> Unit)? = null,
    /**
     * 插件工具栏按钮点击（manifest 声明的 action，如 open_panel）触发。
     * 宿主据此打开对应插件的通用面板。
     */
    val onOpenToolPanel: ((pluginId: String) -> Unit)? = null,
    /**
     * 通用工具面板关闭。
     */
    val onToolPanelClose: (() -> Unit)? = null,
    /**
     * 通用工具面板候选条目点击（AI 生成结果上屏，宿主处理选区替换/追加）。
     */
    val onToolPanelItemClick: ((com.kingzcheung.xime.plugin.core.api.PluginResultItem) -> Unit)? = null,
    /**
     * passive 纯展示面板的 action 节点点击（InfoPanel）：通知插件 actionId 并刷新面板数据。
     */
    val onToolPanelAction: ((actionId: String) -> Unit)? = null,
    /**
     * direct 控件行字段变更（文本输入/下拉选择）：实时通知插件（key = ui 节点 key）。
     */
    val onToolPanelFieldInput: ((key: String, value: String) -> Unit)? = null,
    /**
     * 通用工具面板输入框焦点变化。
     */
    val onToolPanelFocusChange: ((Boolean) -> Unit)? = null,
    val onKeyboardModeChange: ((Boolean) -> Unit)? = null,
    val onDismissDeploying: (() -> Unit)? = null,
    val onFloatingModeChange: ((Boolean) -> Unit)? = null,
    val onFloatingKeyboardDrag: ((dx: Float, dy: Float) -> Unit)? = null,
    val onFloatingKeyboardDragEnd: (() -> Unit)? = null,
    val onT9ReplaceFullPinyin: ((String) -> Unit)? = null,
    /**
     * 回退最近一次 T9 半提交：清除累积的半提交文本（及输入框中已上屏的文字）。
     * @param count 需回删的文本长度（由 C++ T9UndoManager 计算并消费）
     */
    val onT9RightCommitUndone: ((Int) -> Unit)? = null,
    /**
     * 右侧候选词即将被 RIME select 前同步通知 T9 控制器。
     * 返回 true 表示控制器判断输入序列已被该候选词完整消费。
     * @param pinyin RIME 候选词注释
     * @param text 候选词文本（可空），用于 C++ (comment, text) 双条件精确定位
     * @param textLength 候选词文字长度（汉字数），0 表示未知
     */
    var onT9RightCandidateWillBeSelected: ((String?, String?, Int) -> Boolean)? = null,
    /**
     * T9 键盘切换离开（至数字/英文键盘）时调用。
     * 服务层负责提交首位候选词并清理 T9 状态。
     */
    val onT9SwitchAway: (() -> Unit)? = null,
    /**
     * 强制 T9 控制器重新发送当前 inputBuffer 到 RIME。
     * 用于右侧候选 partial commit 后，RIME composition 被清除需要重新构建。
     */
    var onT9ForceSendToRime: (() -> Unit)? = null,
    /**
     * T9 候选词过滤器。服务层在获取 RIME 候选词后调用，由键盘层根据
     * [com.kingzcheung.xime.rime.T9InputController.selectionHistory] 过滤不匹配的候选词。
     * @param candidates 候选词文本列表
     * @param comments 候选词拼音注释列表
     * @return 过滤后的 (候选词列表, 注释列表)
     */
    var onFilterT9Candidates: ((List<String>, List<String>) -> Pair<List<String>, List<String>>)? = null,
    /**
     * T9 键盘状态变更后通知服务层刷新 UI（候选区、preedit 等）。
     * 携带 flush 后一次取回的 composition，服务层直接应用，避免内部重复 getComposition。
     */
    var onT9RefreshComposition: ((RimeComposition, List<com.kingzcheung.xime.service.T9CandidateInjection>) -> Unit)? = null,
    /** T9 候选词变换（hotPath 插件能力）：T9 控制器后台取数后调用，
     *  返回带引擎锚点的插件候选注入列表（text 追加项），null/空 = 不干预。 */
    val onTCandidateTransform: ((RimeProcessResult) -> List<com.kingzcheung.xime.service.T9CandidateInjection>?)? = null,
    val onShowQuickSendForm: (() -> Unit)? = null,
    /**
     * 从剪贴板面板主动拉取远端剪贴板内容（仅剪贴板同步已启用时非空）。
     * 由剪贴板面板导航头的「拉取」按钮触发。
     */
    val onClipboardPullRemote: (() -> Unit)? = null,
    val onHideQuickSendForm: (() -> Unit)? = null,
    val onQuickSendEditItem: ((Long, String, String) -> Unit)? = null,
    val onQuickSendFormFocusChange: ((Boolean) -> Unit)? = null,
    /** 快捷发送表单"触发编码"输入框焦点变化（决定按键输入路由到编码框）。 */
    val onQuickSendCodeFocusChange: ((Boolean) -> Unit)? = null,
    /**
     * 全键盘（中文/英文）切离至其他键盘（数字/符号）时调用。
     * 服务层负责上屏首位候选词或待确认英文，再由键盘层切换布局。
     */
    val onCommitCandidateBeforeModeChange: (() -> Unit)? = null,
    /**
     * 手写叠写自动上屏：把屏上手写尾部 [expectedTail] 替换为 [newTail]
     * （静默上屏——不触发联想推理）。返回是否成功：
     * 期望校验失败（用户移动过光标/退格过）返回 false 且不上屏，
     * 调用方应重置手写尾部状态（后续识别以追加模式重建）。需在主线程调用。
     */
    val onHandwritingAutoCommit: ((newTail: String, expectedTail: String) -> Boolean)? = null,
    /**
     * 手写活动区固化（停顿/点选确认）后触发联想推理（基于已上屏文本）。
     */
    val onHandwritingFinalize: (() -> Unit)? = null,
)
