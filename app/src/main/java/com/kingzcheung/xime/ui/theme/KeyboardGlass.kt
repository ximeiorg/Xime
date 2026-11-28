package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 键盘背景模糊（实验）的运行时状态。
 *
 * 模糊由系统合成器做（窗口下面的内容糊掉），输入法不读任何应用内容。但**系统随时会关**：
 * 省电模式、开发者选项里的窗口模糊开关、机型本身不支持，都会让 [isBlurActive] 变 false。
 * 取色一律问这里，别直接读偏好——否则关掉模糊的半透明键盘会把文字压在清晰画面上。
 */
object KeyboardGlass {
    /** Android 12 起才有窗口模糊 API。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 是否申请窗口模糊：配置了半径且系统放行。 */
    fun isBlurActive(context: Context): Boolean =
        isSupported() && KeysConfigHelper.getKeyboardGlass().background.blurRadius > 0 && isBlurAllowed(context)

    /** 仅看系统是否放行窗口模糊（不含用户开关），排障用。 */
    fun isBlurAllowed(context: Context): Boolean {
        if (!isSupported()) return false
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        return wm.isCrossWindowBlurEnabled
    }
}
