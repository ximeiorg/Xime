package com.kingzcheung.xime.ui.settings


import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.KeyboardColorsConfig
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 0.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showArrow: Boolean = false,
    /** 行尾更新角标文案（如 "2 项可更新"）：非空时以高亮胶囊展示，优先于箭头前。 */
    badgeText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标圆角背景
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (badgeText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 开关设置行。
 *
 * [footer] 用于挂一张"效果预览"（如编码注释开关下的候选行）：放在开关下方，
 * 与内容左缘对齐，因此带预览的行和不带预览的行共用同一条左缘基线。
 */
@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick)
                    else Modifier.clickable { onCheckedChange(!checked) }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
        if (footer != null) {
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                footer()
            }
        }
    }
}

/**
 * 滑块设置行：图标 + 标题 + 当前值 + 滑块，可选副标题、两端刻度与效果预览。
 *
 * [footer] 用来挂本项的预览（候选行 / 键面），与开关行的 footer 同一位置、同一缩进，
 * 因此同一页里"滑块预览"和"开关预览"上下对齐。
 */
@Composable
fun SettingsSliderItem(
    icon: ImageVector,
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rangeStartLabel: String? = null,
    rangeEndLabel: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (rangeStartLabel != null && rangeEndLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = rangeStartLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = rangeEndLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        if (footer != null) {
            Spacer(modifier = Modifier.height(8.dp))
            footer()
        }
    }
}

/**
 * 单选列表项（Material 3 单选语义）。
 *
 * 用于"多个插件里挑一个生效"这类设置：右侧是 [RadioButton] 而不是 Switch——Switch 表达
 * 的是"开/关某样东西"，拨动即切换插件会让用户误以为是开关（此前剪贴板同步/备份页就是这样）。
 * 整行是点击目标（`selectable` + role=RadioButton），RadioButton 自身 onClick 置 null，
 * 避免出现两个语义重叠的可点击区域（TalkBack 只报一个单选节点）。
 *
 * `icon` 可为 null（对话框里的紧凑列表用不上图标时省略，避免一排重复图标）：
 * 插件各自的图标请用 `PluginIconView` 渲染，而不是这里的 ImageVector。
 */
@Composable
fun SettingsRadioItem(
    title: String,
    subtitle: String = "",
    selected: Boolean,
    onSelect: () -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/** 单选弹窗的一项（[SettingsSingleChoiceDialog]）。 */
data class SettingsChoiceOption(
    val id: String,
    val title: String,
    val subtitle: String = "",
)

/**
 * 单选弹窗：把"多个服务/插件里挑一个"从页面列表收进弹窗——页面长度不随候选数量增长。
 *
 * 点某项即回调并关闭（不设确认按钮，与 RadioButton 语义一致）；候选多时内容可滚动。
 */
@Composable
fun SettingsSingleChoiceDialog(
    title: String,
    options: List<SettingsChoiceOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    SettingsRadioItem(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = option.id == selectedId,
                        onSelect = { onSelect(option.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(R.string.s_quxiao)) }
        }
    )
}

@Composable
fun SchemaItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onUpdate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDownloaded && !isLoading, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schema.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (!isDownloaded) MaterialTheme.colorScheme.outline
                else if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (schema.description.isNotEmpty()) {
                Text(
                    text = schema.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (schema.version.isNotEmpty()) {
                    Text(
                        text = tr(R.string.s_banben_3, schema.version),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (schema.author.isNotEmpty()) {
                    Text(
                        text = tr(R.string.s_zuozhe_3, schema.author),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (!isDownloaded) {
                    Text(
                        text = tr(R.string.s_weixiazai),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (!isDownloaded) {
            OutlinedButton(
                onClick = onDownload,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) tr(R.string.s_xiazaizhong) else tr(R.string.s_xiazai))
            }
        } else {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = tr(R.string.s_yixuanze),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            OutlinedButton(
                onClick = onUpdate,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) tr(R.string.s_gengxinzhong) else tr(R.string.s_gengxin))
            }
        }
    }
}

@Composable
fun SchemaSelectDialog(
    schemas: List<SchemaInfo>,
    currentSchema: String,
    onSchemaSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s_xuanzeshurufangan)) },
        text = {
            Column {
                schemas.forEach { schema ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = schema.schemaId == currentSchema,
                            onClick = {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = schema.name, fontWeight = FontWeight.Medium)
                            Text(
                                text = schema.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(R.string.s_queding))
            }
        }
    )
}

@Composable
fun ThemeCard(
    title: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSystem: Boolean = false,
    accentColor: Color? = null,
    keyBgColor: Color? = null,
) {
    val backgroundColor = if (isDark) Color(0xFF202124) else Color(0xFFE8EAED)
    val keyColor = keyBgColor ?: if (isDark) Color(0xFF35363A) else Color(0xFFFFFFFF)
    val specialKeyColor = accentColor ?: if (isDark) Color(0xFF4A4A4A) else Color(0xFFD3E3FD)
    val textColor = if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)
    val candidateBarColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8F9FA)

    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                if (isSystem) {
                    // 跟随系统: 左半浅色 + 右半深色
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // 左半浅色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                                    .background(Color(0xFFE8EAED))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFFF8F9FA))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(accentColor ?: Color(0xFF1A73E8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(1.dp))
                            // 右半深色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                                    .background(Color(0xFF202124))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(Color(0xFF8AB4F8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF35363A))
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(backgroundColor)
                            .padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(candidateBarColor)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            accentColor ?: if (isDark) Color(0xFF8AB4F8) else Color(
                                                0xFF1A73E8
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            repeat(3) { rowIndex ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 1.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    val keysInRow = if (rowIndex == 2) 3 else 10
                                    repeat(keysInRow) { keyIndex ->
                                        val isSpecialKey =
                                            (rowIndex == 2 && (keyIndex == 0 || keyIndex == 2))
                                        val isSpaceKey = (rowIndex == 2 && keyIndex == 1)
                                        Box(
                                            modifier = Modifier
                                                .weight(if (isSpaceKey) 4f else 1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(if (isSpecialKey) specialKeyColor else keyColor)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeyboardThemeCard(
    theme: KeyboardColorScheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val globalKeyBgLight = KeyboardThemes.getKeyBgColorOverride(theme.id, false)
        ?: longToColor(kbColors.keyBgColor)
    val globalKeyBgDark = KeyboardThemes.getKeyBgColorOverride(theme.id, true)
        ?: longToColor(kbColors.keyBgColorDark)
    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = theme.accentLight,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ThemeHalfPreview(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        keyboardBackground = theme.keyboardBackground,
                        isDark = false,
                        fallbackBg = theme.keyboardBgLight,
                        candidateBarColor = theme.candidateBarBgLight,
                        accentColor = theme.accentLight,
                        keyColor = globalKeyBgLight,
                        specialKeyColor = theme.specialKeyLight,
                        isLeft = true,
                    )
                    ThemeHalfPreview(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        keyboardBackground = theme.keyboardBackground,
                        isDark = true,
                        fallbackBg = theme.keyboardBgDark,
                        candidateBarColor = theme.candidateBarBgDark,
                        accentColor = theme.accentDark,
                        keyColor = globalKeyBgDark,
                        specialKeyColor = theme.specialKeyDark,
                        isLeft = false,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.accentLight)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title ?: theme.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) theme.accentLight else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThemeHalfPreview(
    modifier: Modifier = Modifier,
    keyboardBackground: BackgroundConfig?,
    isDark: Boolean,
    fallbackBg: Color,
    candidateBarColor: Color,
    accentColor: Color,
    keyColor: Color,
    specialKeyColor: Color,
    isLeft: Boolean,
    single: Boolean = false,
) {
    val shape = when {
        single -> RoundedCornerShape(8.dp)
        isLeft -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        else -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .keyboardBackground(keyboardBackground, isDark, fallbackBg)
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLeft) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            repeat(3) { rowIndex ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                ) {
                    val keysInRow = if (rowIndex == 2) 2 else 5
                    repeat(keysInRow) { keyIndex ->
                        val isSpecial =
                            (isLeft && rowIndex == 2 && (keyIndex == 0)) || (!isLeft && rowIndex == 2 && keyIndex == 1)
                        val isSpace = (rowIndex == 2 && keyIndex == if (isLeft) 1 else 0)
                        Box(
                            modifier = Modifier
                                .weight(if (isSpace) 3f else 1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(if (isSpecial) specialKeyColor else keyColor)
                        )
                    }
                }
            }
        }
    }
}
