package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

data class InputUIState(
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    val enterKeyText: String = tr(R.string.s_fasong),
    val darkMode: Int = 0,
    val themeId: String = "ocean_blue",
    val isSttEnabled: Boolean = false,
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    /** 键盘贴底显示：忽略底部系统 inset（隐藏导航栏/手势条时把键盘下移到屏幕底边）。 */
    val keyboardFlushBottom: Boolean = false,
    /** 键盘底板不透明度（已按深浅主题解析）；1 = 不透明。 */
    val glassAlpha: Float = 1f,
    /** 键帽不透明度上限（已按深浅主题解析）；1 = 沿用颜色自带 alpha。 */
    val glassKeyAlpha: Float = 1f,
    /** 键盘左边距（dp）：>0 时键盘左缘内收（宽度调节的落盘值，左右独立可偏移）。 */
    val keyboardMarginStartDp: Int = 0,
    /** 键盘右边距（dp）：>0 时键盘右缘内收。 */
    val keyboardMarginEndDp: Int = 0,
    val showKeyboardResize: Boolean = false,
    val resizePreviewHeightDp: Int = 0,
    /** 键盘调节进行中的左右边距预览值（确认后写入 keyboardMarginStart/EndDp）。 */
    val resizePreviewMarginStartDp: Int = 0,
    val resizePreviewMarginEndDp: Int = 0,
    val associationEnabled: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
    val stretchFactor: Float = 1f,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val inputSessionId: Long = 0,
    /** 本次 inputSessionId 变化是否为 restarting（同一编辑框刷新）：键盘 UI 据此跳过 ascii 记忆 reset。 */
    val isInputSessionRestarting: Boolean = false,
    val t9ResetSignal: Long = 0,
    val swipeCancelEpoch: Long = 0,
    val t9RightCandidateSelectedCount: Long = 0,
    val t9SelectedCandidatePinyin: String = "",
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    /** 已启用插件声明的工具栏按钮（候选池）。渲染时与内置按钮合并，插件禁用/卸载后自动不显示。 */
    val toolbarPluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    val isCompact: Boolean = false,
    val isFloatingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val cursorVisible: Boolean = false,
    val showQuickSendForm: Boolean = false,
    val quickSendFormFocused: Boolean = false,
    /** 快捷发送表单内焦点是否在"触发编码"输入框（false=在文本输入框）；决定按键输入路由目标。 */
    val quickSendCodeFocused: Boolean = false,
    val quickSendEditingItemId: Long? = null,
    val quickSendEditingItemText: String = "",
    val quickSendEditingItemCode: String = "",
    /** 通用工具面板是否显示（候选栏上方，与快捷发送同位置）。 */
    val toolPanelVisible: Boolean = false,
    /** 面板输入框是否聚焦（聚焦时按键路由注入面板 EditText）。 */
    val toolPanelInputFocused: Boolean = false,
    /** 当前打开面板的插件 id。 */
    val toolPanelPluginId: String = "",
    /** 面板标题（插件名）。 */
    val toolPanelTitle: String = "",
    /** 面板输入框预填内容（上下文收集结果）。 */
    val toolPanelPrefillText: String = "",
    /** 面板候选条目（AI 生成结果，点击上屏）。 */
    val toolPanelItems: List<PluginResultItem> = emptyList(),
    /** 面板是否正在生成中（流式生成期间为 true，面板展示 loading）。 */
    val toolPanelLoading: Boolean = false,
    /** 面板请求代际号（防旧结果回填闪动）。 */
    val toolPanelRequestEpoch: Long = 0,
    /** 面板展示模式（manifest display.name）：PASSIVE 时以 Overlay 全屏渲染纯展示 InfoPanel（与表情/符号同级）。 */
    val toolPanelDisplay: String? = null,
    /** passive 纯展示节点树（getPanelState.ui，统一 UiNode 声明式模型）。 */
    val toolPanelUiNodes: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
    val clipboardSyncEnabled: Boolean = false,
)
