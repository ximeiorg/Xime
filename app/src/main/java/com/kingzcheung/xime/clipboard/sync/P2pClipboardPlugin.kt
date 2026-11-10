package com.kingzcheung.xime.clipboard.sync

import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.p2p.P2pProfile
import com.kingzcheung.xime.p2p.P2pSyncController
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.model.PluginContext

/**
 * 局域网直连（P2P）的同步传输实现。
 *
 * 与 WebDAV / ximed 插件实现同一个 [ClipboardSyncPlugin] 接口，因此去重、回声抑制、
 * 退避与 `pullOnce` 时机全部继续由 [ClipboardSyncBridge] 负责，这里只做
 * 「push 一条文本 / 取回一条文本 / 报告连接状态」。
 *
 * 第一版只同步文本：`supportsAttachments = false`，bridge 会自动跳过图片。
 */
class P2pClipboardPlugin : ClipboardSyncPlugin {

    override fun onLoad(context: PluginContext) = Unit

    override fun onUnload() = Unit

    override suspend fun push(profile: ClipboardProfile): Boolean {
        if (profile.hasData || profile.type != "text") return false
        return P2pSyncController.pushText(profile.text)
    }

    override suspend fun pull(): ClipboardProfile? {
        val remote = P2pSyncController.pollRemote() ?: return null
        return remote.toClipboardProfile()
    }

    override suspend fun testConnection(): String? = when {
        !P2pSyncController.isRunning -> tr(R.string.s_weiqidong)
        !P2pSyncController.isConnected -> tr(R.string.s_weilianjiedaoshebei)
        else -> null
    }
}

private fun P2pProfile.toClipboardProfile(): ClipboardProfile = ClipboardProfile(
    type = type,
    hash = hash,
    text = text,
    hasData = false,
    dataName = null,
    data = null,
    size = size,
    source = source
)
