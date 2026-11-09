package com.kingzcheung.xime.p2p

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.settings.DeviceLinkSettingsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设备互联页冒烟：能渲染、能读到本机身份，配对入口在。 */
@RunWith(AndroidJUnit4::class)
class DeviceLinkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 渲染本机标识与配对入口() {
        compose.setContent { DeviceLinkSettingsContent(onBack = {}) }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("本机标识").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("本机标识").assertIsDisplayed()
        compose.onNodeWithText("显示配对码").assertIsDisplayed()
        compose.onNodeWithText("输入配对码").assertIsDisplayed()
        compose.onNodeWithText("已配对设备").assertIsDisplayed()
    }
}
