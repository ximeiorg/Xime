package com.kingzcheung.xime.keyboard

import android.view.KeyEvent

/**
 * 手势动作执行上下文接口。
 *
 * 由 InputMethodService 实现并注入给动作分发层，封装所有执行手势动作所需的能力。
 * 动作通过此接口操作编辑器，不直接依赖 InputConnection，便于测试和替换。
 */
interface ActionExecutor {

    /**
     * 上屏指定文本。
     * @param text 要上屏的文本内容
     */
    fun commitText(text: String)

    /**
     * 执行系统编辑器菜单动作。
     * @param actionId Android 内置动作 ID，如 [android.R.id.selectAll]、[android.R.id.copy]、
     *                 [android.R.id.cut]、[android.R.id.paste]、[android.R.id.undo]
     */
    fun performEditorMenuAction(actionId: Int)

    /**
     * 发送按键事件（用于光标移动等操作）。
     * @param keyCode 按键码，如 [KeyEvent.KEYCODE_MOVE_HOME]、[KeyEvent.KEYCODE_MOVE_END]
     */
    fun sendKeyEvent(keyCode: Int)

    /**
     * 移动光标。组合态移动编码编辑光标（与空格左右滑同一路径），空闲态移动编辑器光标。
     * @param direction 移动步数，负值向左、正值向右
     */
    fun moveCursor(direction: Int)

    /**
     * 执行内置命令。
     * @param name 命令名，如 "clear_composition"（清空输入）
     */
    fun executeCommand(name: String)

    /** 重复上一次输入。 */
    fun repeatLastInput()

    /**
     * 派发功能键语义进服务层按键路由（ImeKeyRouter），复用其全部既有状态机
     * （组合态提交、候选选择、退格合并、T9 partial 等均在路由内处理）。
     * @param key 功能键字符串，如 "enter"、"space"、"delete"、"clear_all"
     */
    fun dispatchKey(key: String)

    /** 轮换切换到下一个已启用输入方案（菜单栏方案列表顺序，末尾回到首个）。 */
    fun cycleSchema()
}

/**
 * 按键动作类型（纯类型标记，不携带执行逻辑）。
 *
 * YAML 中的 `action` 字段值通过 [fromValue] 映射到本枚举；执行体统一由
 * [KeyActionRegistry] 按 id 分发，新增动作只需登记处理体，无需改动本枚举。
 */
enum class GestureAction(val value: String) {

    /** 上屏文本，value 为上屏内容。 */
    COMMIT("commit"),

    /**
     * 提交给 rime 引擎：value 作为按键输入走引擎组合路径（与物理键盘敲键同一条
     * 路由，中文模式下字母进拼音组合、由候选选词上屏），不直接上屏。
     */
    SEND_RIME("send_rime"),

    /** 执行内置命令，value 为命令名（如 "clear_composition"）。 */
    COMMAND("command"),

    /** 全选。 */
    SELECT_ALL("select_all"),

    /** 复制。 */
    COPY("copy"),

    /** 剪切。 */
    CUT("cut"),

    /** 粘贴。 */
    PASTE("paste"),

    /** 移动到行首。 */
    LINE_START("line_start"),

    /** 移动到行尾。 */
    LINE_END("line_end"),

    /** Tab 键：发送 KEYCODE_TAB（缩进/焦点切换，平板布局行 1 左端用）。 */
    TAB("tab"),

    /** 光标左移一步（组合态移编码编辑光标，空闲态移编辑器光标）。 */
    CURSOR_LEFT("cursor_left"),

    /** 光标右移一步（组合态移编码编辑光标，空闲态移编辑器光标）。 */
    CURSOR_RIGHT("cursor_right"),

    /** 撤销。 */
    UNDO("undo"),

    /** 仅显示，无操作。 */
    NONE("none"),

    /** 重复上一次输入。 */
    REPEAT("repeat"),

    /** 切换键盘路由/面板（如打开 emoji、符号面板）。由 UI 层拦截处理。 */
    SWITCH_ROUTE("switch_route"),

    /** 切换中/英文输入模式。UI 分发层优先处理。 */
    TOGGLE_ASCII("toggle_ascii"),

    /** 轮换切换到下一个已启用输入方案（与菜单栏方案列表顺序一致，末尾回到首个）。 */
    CYCLE_SCHEMA("cycle_schema"),

    /** 删除/退格。UI 分发层优先处理。 */
    DELETE("delete"),

    /** 切换符号键盘。纯 UI 层行为。 */
    TOGGLE_SYMBOLS("toggle_symbols"),

    /** 回车键语义（组合态提交编码 / 空闲态编辑器动作）。 */
    ENTER("enter"),

    /** 纯换行语义：无视编辑器 imeOptions，固定插入一个换行（组合态先提交编码）。 */
    NEWLINE("newline"),

    /** 空格键语义（组合态选首候选 / 空闲态上屏空格）。 */
    SPACE("space"),

    /** 上滑清空：输入态清输入态 / 空闲态清空全部已上屏（记录撤回）。 */
    CLEAR_ALL("clear_all"),

    /** 下滑撤回：恢复最近一次 clear_all 清空的内容，仅空闲态有效。 */
    UNDO_CLEAR("undo_clear"),

    /** 切换大小写状态。纯 UI 层行为。 */
    TOGGLE_SHIFT("toggle_shift"),

    /** 进入语音输入态（含麦克风权限校验）。纯 UI 层行为。 */
    VOICE("voice"),

    /** 重复空格：value 为次数（默认 5），一次触发连续上屏多个空格。 */
    REPEAT_SPACE("repeat_space");

    companion object {
        /** 根据 YAML 字符串值查找对应的枚举，找不到返回 null。 */
        fun fromValue(value: String): GestureAction? = KeyActionRegistry.fromId(value)?.action
    }
}