package com.kingzcheung.xime.p2p

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.DeviceLinkSettingsContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备互联页冒烟：页面结构与鸿蒙版一致（开关 / 本机 / 配对设备 / 已配对设备 / 说明）。
 *
 * 期望文案一律用 [tr] 取，不写死简体：设备语言可能是繁体（zh-Hant-HK），
 * 界面走的是 values-zh-rHK 资源，写死简体会永远匹配不上。
 */
@RunWith(AndroidJUnit4::class)
class DeviceLinkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun enableDeviceLink() {
        // 与「剪贴板同步」页共用同一份偏好：打开总开关并切到局域网直连，
        // 否则页面会停在「设备互联已关闭」提示上，配对入口不显示。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsPreferences.setClipboardSyncTransport(
            context,
            SettingsPreferences.ClipboardSyncTransport.P2P
        )
        SettingsPreferences.setClipboardSyncEnabled(context, true)
    }

    @Test
    fun 渲染开关本机配对入口与已配对列表() {
        compose.setContent { DeviceLinkSettingsContent(onBack = {}) }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(tr(R.string.s_shebeibiaoqi)).fetchSemanticsNodes().isNotEmpty()
        }
        // 功能开关
        compose.onNodeWithText(tr(R.string.s_qiyongshebeihulian)).performScrollTo().assertIsDisplayed()
        // 本机
        compose.onNodeWithText(tr(R.string.s_shebeimingcheng)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.s_shebeibiaoqi)).performScrollTo().assertIsDisplayed()
        // 配对设备：两个方向的入口
        compose.onNodeWithText(tr(R.string.s_zaijisuanjianshang_daima)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr(R.string.s_shurujisuanjianshang_dema)).performScrollTo().assertIsDisplayed()
        // 已配对设备（标题带数量）
        compose.onNodeWithText(tr(R.string.s_yipeiduishebei_shuliang, 0)).performScrollTo().assertIsDisplayed()
    }
}
