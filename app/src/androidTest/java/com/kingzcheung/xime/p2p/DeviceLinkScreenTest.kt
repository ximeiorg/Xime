package com.kingzcheung.xime.p2p

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.ui.settings.DeviceLinkSettingsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备互联页冒烟：页面结构与鸿蒙版一致（本机 / 配对设备 / 已配对设备 / 说明）。
 *
 * 期望文案一律用 [tr] 取，不写死简体：设备语言可能是繁体（zh-Hant-HK），
 * 界面走的是 values-zh-rHK 资源，写死简体会永远匹配不上。
 *
 * 注意：页面不读剪贴板同步偏好（总开关在剪贴板同步页），各分组恒显示，
 * 因此这里不做偏好准备，避免污染其它用例。
 */
@RunWith(AndroidJUnit4::class)
class DeviceLinkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 渲染本机配对入口与已配对列表() {
        compose.setContent { DeviceLinkSettingsContent(onBack = {}) }

        // 等顶栏标题（最早出现的节点）：冷启动首帧较慢，5s 不够
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(tr(R.string.s_shebeihulian)).fetchSemanticsNodes().isNotEmpty()
        }
        // 本机
        compose.onNodeWithText(tr(R.string.s_shebeimingcheng)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.s_shebeibiaoqi)).performScrollTo().assertIsDisplayed()
        // 配对设备：两个方向的入口
        compose.onNodeWithText(tr(R.string.s_zaijisuanjianshang_daima)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.s_shurujisuanjianshang_dema)).performScrollTo().assertIsDisplayed()
        // 已配对设备（标题带数量；数量取决于真机上已配对设备，不能写死 0）
        val peersTitle = tr(R.string.s_yipeiduishebei_shuliang, 0)
            .substringBefore('（')
            .substringBefore('(')
        compose.onNodeWithText(peersTitle, substring = true).performScrollTo().assertIsDisplayed()
    }
}
