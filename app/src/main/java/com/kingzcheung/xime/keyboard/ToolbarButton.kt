package com.kingzcheung.xime.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Assignment
import androidx.compose.material.icons.automirrored.twotone.LastPage
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.FirstPage
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material.icons.twotone.LastPage
import androidx.compose.material.icons.twotone.Paid
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", tr(R.string.s_biaoqing), Icons.TwoTone.EmojiEmotions),
    CLIPBOARD("clipboard", tr(R.string.s_jiantieban), Icons.AutoMirrored.TwoTone.Assignment),
    SCHEMA("schema", tr(R.string.s_fanganxuanze), Icons.TwoTone.KeyboardAlt),
    SWITCHES("switches", tr(R.string.s_fangankaiguan), Icons.TwoTone.Tune),
    QUICK_PHRASE("quick_phrase", tr(R.string.s_kuaijiefasong), Icons.TwoTone.Quickreply),
    SYMBOL("symbol", tr(R.string.s_fuhao), Icons.TwoTone.Paid),
    FLOAT("float", tr(R.string.s_xuanfu), Icons.Default.PictureInPicture),
    SELECT_ALL("select_all", tr(R.string.s_quanxuan), Icons.TwoTone.SelectAll),
    COPY("copy", tr(R.string.s_fuzhi), Icons.TwoTone.ContentCopy),
    PASTE("paste", tr(R.string.s_zhantie), Icons.TwoTone.ContentPaste),
    HOME("home", tr(R.string.s_duanshou), Icons.TwoTone.FirstPage),
    END("end", tr(R.string.s_duanwei), Icons.AutoMirrored.TwoTone.LastPage),
    HANDWRITING_LOOKUP("handwriting_lookup", tr(R.string.s_shouxiezhaozi), Icons.Outlined.Gesture),
    EDIT("edit", tr(R.string.s_bianji), Icons.TwoTone.Edit),
    VOICE("voice", tr(R.string.s_yuyin), Icons.TwoTone.Mic);

    companion object {
        val DEFAULT_VISIBLE = emptySet<ToolbarButton>()

        fun fromId(id: String): ToolbarButton? =
            entries.find { it.id == id }
    }
}

/**
 * 内置工具栏按钮当前是否可用。
 *
 * 不可用的按钮既要从候选栏隐藏（KeyboardView），也要从定制列表里去掉
 * （ToolbarCustomizeView）——两处必须同一口径，否则会出现「定制里能勾选、
 * 键盘上却不显示」的幽灵按钮。
 *
 * [hasHandwritingModel] 按需取值：只有「手写找字」需要探测本地模型。
 */
fun isBuiltinToolbarButtonAvailable(
    button: ToolbarButton,
    isSttEnabled: Boolean,
    hasHandwritingModel: () -> Boolean,
): Boolean = when (button) {
    ToolbarButton.HANDWRITING_LOOKUP -> hasHandwritingModel()
    ToolbarButton.VOICE -> isSttEnabled
    else -> true
}

data class ToolbarAction(
    val item: ToolbarButtonItem,
    val onClick: () -> Unit
)
