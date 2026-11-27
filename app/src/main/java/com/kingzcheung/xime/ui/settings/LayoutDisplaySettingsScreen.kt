package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlin.math.roundToInt

/**
 * 「布局与显示」。
 *
 * 一卡一项：卡片里只有「标题 → 预览 → 控件」三层，没有组内分割线、没有行首图标，
 * 预览永远紧贴它所属的那一项，避免出现"这图到底是谁的"以及图标+分割线的重复噪音。
 * 提示文案只保留真正需要说明的短句，能由预览表达的一律不写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutDisplaySettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
) {
    val context = LocalContext.current
    val letterSizeLevels = SettingsPreferences.QWERTY_LETTER_SIZE_LEVELS
    val pageSizePref = SettingsPreferences.getPageSize(context)
        .takeIf { it > 0 } ?: SettingsPreferences.DEFAULT_PAGE_SIZE

    var hardwareKeyboardDetection by remember {
        mutableStateOf(SettingsPreferences.isHardwareKeyboardDetectionEnabled(context))
    }
    var landscapeSplitKeyboard by remember {
        mutableStateOf(SettingsPreferences.isLandscapeSplitKeyboardEnabled(context))
    }
    var keyboardFlushBottom by remember {
        mutableStateOf(SettingsPreferences.isKeyboardFlushBottom(context))
    }
    var letterSizeLevel by remember {
        mutableIntStateOf(SettingsPreferences.getQwertyLetterSizeLevel(context))
    }
    var candidateTextSize by remember {
        mutableFloatStateOf(SettingsPreferences.getCandidateTextSize(context).toFloat())
    }
    var pageSize by remember { mutableFloatStateOf(pageSizePref.toFloat()) }
    var showComments by remember {
        mutableStateOf(SettingsPreferences.showCandidateComments(context))
    }
    var inputTextLocation by remember {
        mutableStateOf(SettingsPreferences.getInputTextLocation(context))
    }
    var autoNumberKeyboard by remember {
        mutableStateOf(SettingsPreferences.isAutoNumberKeyboardEnabled(context))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_bujuyuxianshi)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingCard(
                    title = tr(R.string.s_shitijianpanjiance),
                    subtitle = tr(R.string.s_guanbihoubujianceshitijianpan_6aaf1),
                    onClick = {
                        val enabled = !hardwareKeyboardDetection
                        hardwareKeyboardDetection = enabled
                        SettingsPreferences.setHardwareKeyboardDetectionEnabled(context, enabled)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = hardwareKeyboardDetection,
                            onCheckedChange = { enabled ->
                                hardwareKeyboardDetection = enabled
                                SettingsPreferences.setHardwareKeyboardDetectionEnabled(
                                    context, enabled
                                )
                            }
                        )
                    }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_hengpingshiyongfentijianpan),
                    onClick = {
                        val enabled = !landscapeSplitKeyboard
                        landscapeSplitKeyboard = enabled
                        SettingsPreferences.setLandscapeSplitKeyboardEnabled(context, enabled)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = landscapeSplitKeyboard,
                            onCheckedChange = { enabled ->
                                landscapeSplitKeyboard = enabled
                                SettingsPreferences.setLandscapeSplitKeyboardEnabled(
                                    context, enabled
                                )
                            }
                        )
                    },
                    content = { SplitKeyboardPreview(splitEnabled = landscapeSplitKeyboard) }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_jianpantiedixianshi),
                    onClick = {
                        val enabled = !keyboardFlushBottom
                        keyboardFlushBottom = enabled
                        SettingsPreferences.setKeyboardFlushBottom(context, enabled)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = keyboardFlushBottom,
                            onCheckedChange = { enabled ->
                                keyboardFlushBottom = enabled
                                SettingsPreferences.setKeyboardFlushBottom(context, enabled)
                            }
                        )
                    },
                    content = { FlushBottomPreview(flushBottom = keyboardFlushBottom) }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_qwerty_letter_size),
                    value = "${SettingsPreferences.qwertyLetterSizePercent(letterSizeLevel)}%",
                    content = {
                        KeycapPreview(
                            letterScale = SettingsPreferences
                                .qwertyLetterSizePercent(letterSizeLevel) / 100f
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = letterSizeLevel.toFloat(),
                            onValueChange = { letterSizeLevel = it.roundToInt() },
                            onValueChangeFinished = {
                                SettingsPreferences.setQwertyLetterSizeLevel(context, letterSizeLevel)
                            },
                            valueRange = letterSizeLevels.first.toFloat()..letterSizeLevels.last.toFloat(),
                            steps = letterSizeLevels.last - letterSizeLevels.first - 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_houxuanzidaxiao),
                    value = "${candidateTextSize.roundToInt()}sp",
                    content = {
                        // 字号与编码注释共用这张预览：两项都只影响"候选长什么样"
                        CandidateLinePreview(
                            candidateTextSize = candidateTextSize.roundToInt(),
                            candidateCount = 4,
                            showComments = showComments,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = candidateTextSize,
                            onValueChange = { candidateTextSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setCandidateTextSize(
                                    context, candidateTextSize.roundToInt()
                                )
                            },
                            valueRange = 12f..22f,
                            steps = 9,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_bianmazhushi),
                    subtitle = tr(R.string.s_zaihouxuancipangxianshiduiying_de529),
                    onClick = {
                        val show = !showComments
                        showComments = show
                        SettingsPreferences.setShowCandidateComments(context, show)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = showComments,
                            onCheckedChange = { show ->
                                showComments = show
                                SettingsPreferences.setShowCandidateComments(context, show)
                            }
                        )
                    }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_meiyehouxuancishu),
                    value = tr(R.string.s_ge, pageSize.roundToInt()),
                    content = {
                        Slider(
                            value = pageSize,
                            onValueChange = { pageSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setPageSize(context, pageSize.roundToInt())
                            },
                            valueRange = 5f..50f,
                            steps = 44,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            item {
                val codeInInputBox =
                    inputTextLocation == SettingsPreferences.INPUT_TEXT_INPUT_BOX
                SettingCard(
                    title = tr(R.string.s_bianmaxianshi_zai_shurukuang),
                    subtitle = tr(R.string.s_guanbishi_xianshi_zai_houxuanlan),
                    onClick = {
                        val toInputBox = !codeInInputBox
                        inputTextLocation = if (toInputBox) {
                            SettingsPreferences.INPUT_TEXT_INPUT_BOX
                        } else {
                            SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                        }
                        SettingsPreferences.setInputTextLocation(context, inputTextLocation)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = codeInInputBox,
                            onCheckedChange = { toInputBox ->
                                inputTextLocation = if (toInputBox) {
                                    SettingsPreferences.INPUT_TEXT_INPUT_BOX
                                } else {
                                    SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                                }
                                SettingsPreferences.setInputTextLocation(
                                    context, inputTextLocation
                                )
                            }
                        )
                    },
                    content = { CodePositionPreview(codeInInputBox = codeInInputBox) }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_shuzikuangzidongshuzijianpan),
                    subtitle = tr(R.string.s_jinruhaoma_yanzhengmadengshuzi_1aed9),
                    onClick = {
                        val enabled = !autoNumberKeyboard
                        autoNumberKeyboard = enabled
                        SettingsPreferences.setAutoNumberKeyboardEnabled(context, enabled)
                    },
                    trailing = {
                        SettingSwitch(
                            checked = autoNumberKeyboard,
                            onCheckedChange = { enabled ->
                                autoNumberKeyboard = enabled
                                SettingsPreferences.setAutoNumberKeyboardEnabled(context, enabled)
                            }
                        )
                    }
                )
            }

            item {
                SettingCard(
                    title = tr(R.string.s_bujushichang),
                    onClick = onNavigateToMarket,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
    }
}

/**
 * 一项一张卡：标题（可带当前值/开关）+ 可选的预览与控件。
 *
 * 卡片本身可点（[onClick]）时整块都能触发，和右上角的开关等价。
 */
@Composable
private fun SettingCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (trailing != null) {
                if (value != null) Spacer(modifier = Modifier.width(10.dp))
                trailing()
            }
        }
        if (content != null) {
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }

    val cardModifier = modifier.fillMaxWidth()
    // 与设置首页 SettingsSection 同一套卡片规格：surfaceContainerLow + 12dp 圆角 + 零阴影
    val shape = RoundedCornerShape(12.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val inner: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp)) { body() }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = inner
        )
    } else {
        Surface(
            modifier = cardModifier,
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = inner
        )
    }
}

@Composable
private fun SettingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}
