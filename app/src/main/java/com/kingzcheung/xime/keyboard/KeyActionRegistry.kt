package com.kingzcheung.xime.keyboard

import android.view.KeyEvent

/**
 * 按键动作的执行域。
 *
 * 用于声明一个动作应由哪一层负责执行，便于统一分发时不出现跨层误用。
 */
enum class ActionDomain {
    /** 服务层：走按键路由 / 编辑器操作 / 内置命令。 */
    SERVICE,

    /** UI 层：仅操作键盘界面与状态（布局切换、Overlay、语音态等）。 */
    UI,

    /** 跨层：UI 触发 + 服务层副作用组合。 */
    CROSS,
}

/**
 * UI 层动作能力端口。
 *
 * 由承载键盘界面与视图状态的宿主实现，向需要操作界面的动作提供能力，
 * 使动作定义无需直接依赖具体的界面组件。
 */
interface UiActionHost {
    /** 切换大小写状态。 */
    fun toggleShift()

    /** 单击 Shift：OFF 切单击态，其余归 OFF。 */
    fun singleTapShift()

    /** 双击 Shift：切换锁定大写。 */
    fun doubleTapShift()

    /** 切换主键盘类型。 */
    fun switchMain(type: MainType)

    /** 进入数字/常用符号面板。 */
    fun enterPanel(type: PanelType)

    /** 退出面板回到主键盘。 */
    fun exitPanel()

    /** 打开 Overlay 页面。 */
    fun showOverlay(route: OverlayRoute)

    /** 进入语音输入态。 */
    fun enterVoice()

    /** 退出语音输入态。 */
    fun exitVoice()
}

/**
 * 动作执行上下文。
 *
 * @param service 服务层能力端口
 * @param ui UI 层能力端口，UI 域动作使用；服务域动作可为 null
 */
class KeyActionContext(
    val service: ActionExecutor,
    val ui: UiActionHost? = null,
)

/**
 * 统一按键动作注册表。
 *
 * 动作类型（[GestureAction]）与 `action: command` 的命令名都登记在此，按 id 分发执行体。
 * 新增动作只需登记处理体，无需改动枚举，做到「动作类型与执行逻辑分离」。
 */
object KeyActionRegistry {

    /** 一个动作的登记项。 */
    class Handler(
        val id: String,
        val domain: ActionDomain,
        /** 由 [GestureAction] 承载的动作回指类型；命令名动作可为 null。 */
        val action: GestureAction?,
        val execute: (KeyActionContext, String) -> Unit,
    )

    private val byId = LinkedHashMap<String, Handler>()

    init {
        register("commit", ActionDomain.SERVICE, GestureAction.COMMIT) { c, v -> c.service.commitText(v) }
        register("send_rime", ActionDomain.SERVICE, GestureAction.SEND_RIME) { c, v -> c.service.dispatchKey(v) }
        // command 由 UI 手势层按命令名白名单走按键路由；此处仅保留服务域兜底执行体
        register("command", ActionDomain.SERVICE, GestureAction.COMMAND) { c, v -> c.service.executeCommand(v) }
        register("select_all", ActionDomain.SERVICE, GestureAction.SELECT_ALL) { c, _ ->
            c.service.performEditorMenuAction(android.R.id.selectAll)
        }
        register("copy", ActionDomain.SERVICE, GestureAction.COPY) { c, _ ->
            c.service.performEditorMenuAction(android.R.id.copy)
        }
        register("cut", ActionDomain.SERVICE, GestureAction.CUT) { c, _ ->
            c.service.performEditorMenuAction(android.R.id.cut)
        }
        register("paste", ActionDomain.SERVICE, GestureAction.PASTE) { c, _ ->
            c.service.performEditorMenuAction(android.R.id.paste)
        }
        register("line_start", ActionDomain.SERVICE, GestureAction.LINE_START) { c, _ ->
            c.service.sendKeyEvent(KeyEvent.KEYCODE_MOVE_HOME)
        }
        register("line_end", ActionDomain.SERVICE, GestureAction.LINE_END) { c, _ ->
            c.service.sendKeyEvent(KeyEvent.KEYCODE_MOVE_END)
        }
        register("cursor_left", ActionDomain.SERVICE, GestureAction.CURSOR_LEFT) { c, _ ->
            c.service.moveCursor(-1)
        }
        register("cursor_right", ActionDomain.SERVICE, GestureAction.CURSOR_RIGHT) { c, _ ->
            c.service.moveCursor(1)
        }
        register("tab", ActionDomain.SERVICE, GestureAction.TAB) { c, _ ->
            c.service.sendKeyEvent(KeyEvent.KEYCODE_TAB)
        }
        register("undo", ActionDomain.SERVICE, GestureAction.UNDO) { c, _ ->
            c.service.performEditorMenuAction(android.R.id.undo)
        }
        register("none", ActionDomain.SERVICE, GestureAction.NONE) { _, _ -> }
        register("repeat", ActionDomain.SERVICE, GestureAction.REPEAT) { c, _ -> c.service.repeatLastInput() }

        register("switch_route", ActionDomain.UI, GestureAction.SWITCH_ROUTE) { _, _ -> }
        register("toggle_symbols", ActionDomain.UI, GestureAction.TOGGLE_SYMBOLS) { _, _ -> }
        register("toggle_shift", ActionDomain.UI, GestureAction.TOGGLE_SHIFT) { _, _ -> }
        register("voice", ActionDomain.UI, GestureAction.VOICE) { c, _ -> c.ui?.enterVoice() }

        register("toggle_ascii", ActionDomain.CROSS, GestureAction.TOGGLE_ASCII) { c, _ ->
            c.service.dispatchKey("ime_switch")
        }
        register("cycle_schema", ActionDomain.SERVICE, GestureAction.CYCLE_SCHEMA) { c, _ ->
            c.service.cycleSchema()
        }
        register("delete", ActionDomain.CROSS, GestureAction.DELETE) { c, _ -> c.service.dispatchKey("delete") }

        register("enter", ActionDomain.SERVICE, GestureAction.ENTER) { c, _ -> c.service.dispatchKey("enter") }
        register("newline", ActionDomain.SERVICE, GestureAction.NEWLINE) { c, _ -> c.service.dispatchKey("newline") }
        register("space", ActionDomain.SERVICE, GestureAction.SPACE) { c, _ -> c.service.dispatchKey("space") }
        register("repeat_space", ActionDomain.SERVICE, GestureAction.REPEAT_SPACE) { c, v ->
            val count = v.toIntOrNull()?.coerceIn(1, 100) ?: 5
            repeat(count) { c.service.dispatchKey("space") }
        }
        register("clear_all", ActionDomain.SERVICE, GestureAction.CLEAR_ALL) { c, _ -> c.service.dispatchKey("clear_all") }
        register("undo_clear", ActionDomain.SERVICE, GestureAction.UNDO_CLEAR) { c, _ -> c.service.dispatchKey("undo_clear") }
    }

    private fun register(
        id: String,
        domain: ActionDomain,
        action: GestureAction,
        execute: (KeyActionContext, String) -> Unit,
    ) {
        byId[id] = Handler(id, domain, action, execute)
    }

    /** 登记/覆盖一个动作。 */
    fun register(handler: Handler) {
        byId[handler.id] = handler
    }

    /** 按 YAML `action` 取值查询动作，未知取值返回 null。 */
    fun fromId(id: String): Handler? = byId[id]

    /** 执行一个动作类型。 */
    fun execute(action: GestureAction, context: KeyActionContext, value: String) {
        fromId(action.value)?.execute(context, value)
    }
}