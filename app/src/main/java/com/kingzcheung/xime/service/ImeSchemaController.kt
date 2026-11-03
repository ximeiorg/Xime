package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轮换目标：已启用方案列表中当前方案的下一个（末尾回到首个）。
 *
 * 当前方案不在列表内（列表过期/刚部署完）时取首个；列表为空返回 null。
 */
internal fun nextSchemaId(schemaIds: List<String>, currentSchemaId: String): String? {
    if (schemaIds.isEmpty()) return null
    val index = schemaIds.indexOf(currentSchemaId)
    return schemaIds[(index + 1) % schemaIds.size]
}

/**
 * 方案管理与输入模式切换。
 *
 * 承载方案切换（switchSchema/applyPageSizeSetting）、部署（reloadConfig）、
 * 工具栏编辑动作、键盘高度与浮动模式调整。
 * 中英切换已收敛至 [AsciiModeController]。
 * 共享状态通过 service 引用访问。
 */
internal class ImeSchemaController(private val service: XimeInputMethodService) {
    internal fun reloadConfig() {

        service.mainHandler.post {
            service.requestHideSelf(0)
            android.widget.Toast.makeText(service, tr(R.string.s_fanganbushuzhong), android.widget.Toast.LENGTH_SHORT).show()
        }

        // 部署投递到 key-processing 队列：与按键/切换同队列串行执行，
        // 部署期间输入/切换操作排队等待，完成后自动恢复，
        // 不再因 rimeLock 被部署占用而失败或静默丢弃。
        service.keyRouter.postRimeJob {
            try {
                KeysConfigHelper.loadConfig(service)
                // 重新加载配色方案（用户可能在 xime.custom.yaml 中修改了 color_schemes）
                KeyboardThemes.reload(service)

                // 统一部署入口（设置页/键盘菜单/安装替换同一条路径）：
                // 内部会镜像 schema_list、补个人词库、重启维护并在维护前后重建会话。
                val result = SchemeDeployer.deploy(service)
                val deployed = result.ok
                val missing = RimeConfigHelper.missingCompiledSchemaNames(service)

                // 部署完成后重新加载配置（Rime 可能在部署过程中改写文件）
                KeysConfigHelper.loadConfig(service)
                KeyboardThemes.reload(service)

                val availableSchemas = service.rimeEngine.getAvailableSchemas()

                val savedSchema = SettingsPreferences.getCurrentSchema(service)
                // 部署刚结束，get_schema_list 就是本次部署的结果，直接按它判定。
                if (savedSchema in availableSchemas) {
                    applyPageSizeSetting(savedSchema)
                    service.rimeEngine.switchSchema(savedSchema)
                    // 部署会重建会话；当前若是 english 方案，保持其 ascii 默认值。
                    service.asciiModeController.applySchemaDefault(savedSchema)
                } else {
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "Schema $savedSchema not in deployed schema_list, " +
                            "available=${availableSchemas.joinToString()}"
                    )
                }

                // 直接在 key-processing 线程同步读取 name，避免嵌套协程的时序问题
                val currentSchemaId = service.rimeEngine.getCurrentSchema()
                val schemaName = SchemaManager.getSchemaDisplayName(
                    service, currentSchemaId
                ) ?: currentSchemaId

                // 部署会重建 build/，方案列表（含「已编译」过滤）必须重新查询，
                // 否则菜单仍显示部署前的旧快照。
                service.sessionController.updateSchemaName()

                withContext(Dispatchers.Main) {
                    service.uiState.value = service.uiState.value.copy(
                        schemaName = schemaName,
                        currentSchemaId = currentSchemaId,
                    )
                    service.updateUI()
                    val message = when {
                        !deployed -> tr(R.string.s_bushushibai)
                        missing.isNotEmpty() ->
                            tr(R.string.s_bushushibai_fanganweibianyi, missing.joinToString("、"))
                        else -> tr(R.string.s_fanganbushuwancheng)
                    }
                    android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to reload config", e)
            }
        }
    }

    internal fun openSettings() {
        try {
            val intent = Intent(service, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to open settings", e)
        }
    }
    
    private var editSelAnchor = -1

    internal fun handleToolbarEditingAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "home" -> ic.setSelection(0, 0)
            "end" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                ic.setSelection(before.length + after.length, before.length + after.length)
            }
            "arrow_up" -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0))
                ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0))
            }
            "arrow_down" -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
            }
            "arrow_left" -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0))
                ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
            }
            "arrow_right" -> {
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
                ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            }

            "select_begin" -> {
                editSelAnchor = (ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: "").length
            }
            "select_end" -> {
                editSelAnchor = -1
            }
            "select_arrow_left" -> extendSelection(ic, -1)
            "select_arrow_right" -> extendSelection(ic, 1)
            "select_arrow_up" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val pos = before.length
                if (pos > 0) {
                    val prevNewline = before.lastIndexOf('\n', pos - 2)
                    val lineStart = if (prevNewline >= 0) prevNewline + 1 else 0
                    ic.beginBatchEdit()
                    ic.setSelection(editSelAnchor, lineStart)
                    ic.endBatchEdit()
                }
            }
            "select_arrow_down" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val pos = before.length
                val total = before.length + after.length
                if (pos < total) {
                    val nextNewline = after.indexOf('\n')
                    val lineEnd = if (nextNewline >= 0) pos + nextNewline else total
                    ic.beginBatchEdit()
                    ic.setSelection(editSelAnchor, lineEnd)
                    ic.endBatchEdit()
                }
            }
        }
    }

    private fun extendSelection(ic: InputConnection, direction: Int) {
        val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val pos = before.length
        val total = before.length + after.length
        val next = (pos + direction).coerceIn(0, total)
        if (next != pos) {
            ic.beginBatchEdit()
            ic.setSelection(editSelAnchor, next)
            ic.endBatchEdit()
        }
    }

    internal fun applyPageSizeSetting(schemaId: String) {
        val userPageSize = SettingsPreferences.getPageSize(service)
        if (userPageSize > 0) {
            service.rimeEngine.setPageSize(schemaId, userPageSize)
        }
    }

    internal fun switchSchema(schemaId: String) {
        if (isHandwritingSchema(schemaId)) {
            // 检查手写模型文件是否已下载
            if (!com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service)) {
                FileLogger.w(XimeInputMethodService.TAG, "Handwriting model not found, redirecting to download")
                android.widget.Toast.makeText(
                    service, tr(R.string.s_qingxianxiazaishouxiemoxing), android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(
                    service, com.kingzcheung.xime.MainActivity::class.java
                ).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("open_fragment", "model_management")
                }
                service.startActivity(intent)
                return
            }
            service.previousSchemaId = service.rimeEngine.getCurrentSchema()
            SchemeStore.setCurrent(service, schemaId)
            service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
            // 手写模型按"用键盘时加载"管理：切到手写方案即展示手写键盘，
            // 由 HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载，
            // 此处不重复加载（避免与布局初始化并发双 bind/load）
            service.sessionController.updateSchemaName()
            return
        }
        // 切离手写方案时释放手写引擎
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
        try {
            SchemeStore.setCurrent(service, schemaId)
            // 用户自定义候选词数：先写 custom.yaml 再切方案，Rime 会自动加载
            applyPageSizeSetting(schemaId)
            // 部署/编译进行中 switchSchema 返回 false（不阻塞等待），
            // 此时不应继续触发其他 native 调用进入编译中的引擎
            if (!service.rimeEngine.switchSchema(schemaId)) {
                if (service.rimeEngine.isMaintaining()) {
                    FileLogger.w(XimeInputMethodService.TAG, "switchSchema skipped: deployment in progress")
                    Toast.makeText(service, tr(R.string.s_cikubushuzhong_qingshaohouzaiq_7d5ce), Toast.LENGTH_SHORT).show()
                    return
                }
                // 引擎切换失败（常见：方案未部署/不在 schema_list，老版本升级残留）。
                // 必须回滚偏好到引擎实际方案：偏好留着目标值会让 UI 状态与引擎
                // 永久脱节（键盘已显示九键但数字键无人处理，候选栏始终 IDLE）。
                // getCurrentSchema 无会话时返回空串——此时不动偏好，保留原值由
                // IME 重启的恢复逻辑兜底，避免把空串写进偏好与 user.yaml
                val actual = service.rimeEngine.getCurrentSchema()
                if (actual.isNotEmpty()) {
                    SchemeStore.setCurrent(service, actual)
                }
                FileLogger.w(XimeInputMethodService.TAG, "switchSchema failed: target=$schemaId actual=$actual")
                Toast.makeText(service, tr(R.string.s_fanganweibushu_qingzaifangangu_63cf5), Toast.LENGTH_SHORT).show()
                return
            }
            val ascii = service.asciiModeController.applySchemaDefault(schemaId)
            // 标点默认值也由方案声明：ascii_punct reset:1 时不覆盖；
            // 未声明或 reset:0 的普通中文方案保持原有「非 ascii 时用中文标点」。
            if (!ascii && SchemaManager.schemaSwitchReset(service, schemaId, "ascii_punct") != 1) {
                service.rimeEngine.setOption("ascii_punct", false)
            }
            service.uiState.value = service.uiState.value.copy(isAsciiMode = ascii)
            service.sessionController.updateSchemaName()
            service.updateUI()
            // 确保键盘布局与方案匹配（如 T9 九键不应被 switchMain 重置为全键盘）
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(
                    ascii, schemaId
                )
            )
            Toast.makeText(service, tr(R.string.s_yiqiehuanshurufangan), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to switch schema", e)
        }
    }
    
    /**
     * 轮换切换到下一个已启用方案（菜单栏方案列表顺序）。
     *
     * 复用 [switchSchema]：中英模式、键盘类型、手写模型校验等行为与在菜单里选方案一致。
     * 只有一个方案（或列表尚未就绪）时不做任何事。
     */
    internal fun cycleSchema() {
        val state = service.uiState.value
        val target = nextSchemaId(state.schemas.map { it.schemaId }, state.currentSchemaId)
        if (target == null || target == state.currentSchemaId) {
            FileLogger.i(
                XimeInputMethodService.TAG,
                "cycleSchema skipped: current=${state.currentSchemaId}, " +
                    "enabled=${state.schemas.joinToString { it.schemaId }}"
            )
            return
        }
        switchSchema(target)
    }

    private fun downloadSchema(schemaId: String) {
        service.serviceScope.launch(Dispatchers.IO) {
            service.notifyDeploymentStatus(true, tr(R.string.s_zhengzaixiazai, schemaId))
            
            val success = SchemaConfigHelper.downloadSchema(service, schemaId)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(service, tr(R.string.s_xiazaichenggong_qingdianjibushu, schemaId), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(service, tr(R.string.s_xiazaishibai_2, schemaId), Toast.LENGTH_SHORT).show()
                }
                service.notifyDeploymentStatus(false, "")
            }
        }
    }
    
    internal fun setKeyboardHeight(heightDp: Int) {
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setKeyboardHeightDp(service, heightDp, isLandscape)
        service.uiState.value = service.uiState.value.copy(keyboardHeightDp = heightDp)
        // 不弹 Toast：调节界面本身就是实时预览，确定时键盘已经变成目标高度，
        // 再提示"键盘高度已调整"是多余噪音
    }

    internal fun toggleFloatingMode(enabled: Boolean, floatingMinY: Int = 0) {
        if (enabled && service.isHostConstrained()) {
            // 独立 IME 面板：承载区比键盘还小，浮动卡片没有可拖动/可停靠的空间
            android.widget.Toast.makeText(
                service,
                tr(R.string.s_dangqianxianshiquyubuzhichifud_d72e1),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 任何路径切悬浮都清掉拖动提示态（正常由拖动松手消费，这里兜底防光效残留）
        service.floatingExitHintState.value = false
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setFloatingMode(service, enabled, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(service, isLandscape)
        val screenW = service.resources.configuration.screenWidthDp
        val screenH = service.resources.configuration.screenHeightDp
        val portraitWidth = minOf(screenW, screenH)
        val halfMargin = FloatingCardGeometry.halfMarginDp(screenW, portraitWidth)
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        service.uiState.value = service.uiState.value.copy(
            isFloatingMode = enabled,
            floatingOffsetX = clampedX,
            // 开启悬浮归位到"拖动可达的最低点"：拖动钳制下界是 floatingMinY（导航栏高），
            // 归位到 0 会停在拖动到不了的位置（表现为比可达范围偏下一截）
            floatingOffsetY = if (enabled) floatingMinY else 0,
        )
        if (enabled) {
            service.closeToolPanel()
            // 清掉上一轮的实测矩形：新卡片矩形未建立前，触摸区/钳制走 FloatingCardGeometry 兜底
            service.floatingCardBounds = null
            service.currentFloatingCardHeightDp = 0
        }
        service.applyWindowBackground()
    }

}