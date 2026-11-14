package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Comment
import androidx.compose.material.icons.automirrored.twotone.Input
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.AlignVerticalBottom
import androidx.compose.material.icons.twotone.Dialpad
import androidx.compose.material.icons.twotone.FormatSize
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.ScreenRotation
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material.icons.twotone.TextFields
import androidx.compose.material.icons.twotone.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlin.math.roundToInt

/**
 * 「布局与显示」。
 *
 * 结构按键盘的视觉栈排：键盘 → 候选栏 → 输入框。**每一个看得见效果的选项都自带一张预览**
 * （横屏分体、贴底显示、键面字号、候选字号、每页候选数、编码注释、编码位置），七张预览共用
 * [SettingsPreviewFrame] 的外框与样例文案，所以样式统一、位置固定，但每张只反映它自己那一项：
 * 调字号就看候选字，调密度就看候选数，不必在几项之间来回对照。
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

    // —— 所有可调项的当前值：控件写入、本项预览读取 ——
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = tr(R.string.s_jianpanbuju), content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Keyboard,
                        title = tr(R.string.s_shitijianpanjiance),
                        subtitle = tr(R.string.s_guanbihoubujianceshitijianpan_6aaf1),
                        checked = hardwareKeyboardDetection,
                        onCheckedChange = { enabled ->
                            hardwareKeyboardDetection = enabled
                            SettingsPreferences.setHardwareKeyboardDetectionEnabled(context, enabled)
                        },
                        footer = { HardwareKeyboardPreview(detectionEnabled = hardwareKeyboardDetection) }
                    )
                    SectionDivider()
                    SettingsToggleItem(
                        icon = Icons.TwoTone.ScreenRotation,
                        title = tr(R.string.s_hengpingshiyongfentijianpan),
                        subtitle = tr(R.string.s_guanbihou_hengpingjiangxianshi_e98c9),
                        checked = landscapeSplitKeyboard,
                        onCheckedChange = { enabled ->
                            landscapeSplitKeyboard = enabled
                            SettingsPreferences.setLandscapeSplitKeyboardEnabled(context, enabled)
                        },
                        footer = { SplitKeyboardPreview(splitEnabled = landscapeSplitKeyboard) }
                    )
                    SectionDivider()
                    SettingsToggleItem(
                        icon = Icons.TwoTone.AlignVerticalBottom,
                        title = tr(R.string.s_jianpantiedixianshi),
                        subtitle = tr(R.string.s_yincangdibudaohanglanhuoshoush_daf00),
                        checked = keyboardFlushBottom,
                        onCheckedChange = { enabled ->
                            keyboardFlushBottom = enabled
                            SettingsPreferences.setKeyboardFlushBottom(context, enabled)
                        },
                        footer = { FlushBottomPreview(flushBottom = keyboardFlushBottom) }
                    )
                    SectionDivider()
                    SettingsSliderItem(
                        icon = Icons.TwoTone.TextFields,
                        title = tr(R.string.s_qwerty_letter_size),
                        valueText = "${SettingsPreferences.qwertyLetterSizePercent(letterSizeLevel)}%",
                        value = letterSizeLevel.toFloat(),
                        onValueChange = { letterSizeLevel = it.roundToInt() },
                        onValueChangeFinished = {
                            SettingsPreferences.setQwertyLetterSizeLevel(context, letterSizeLevel)
                        },
                        valueRange = letterSizeLevels.first.toFloat()..letterSizeLevels.last.toFloat(),
                        steps = letterSizeLevels.last - letterSizeLevels.first - 1,
                        rangeStartLabel = "${SettingsPreferences.qwertyLetterSizePercent(letterSizeLevels.first)}%",
                        rangeEndLabel = "${SettingsPreferences.qwertyLetterSizePercent(letterSizeLevels.last)}%",
                        footer = {
                            KeycapPreview(
                                letterScale = SettingsPreferences
                                    .qwertyLetterSizePercent(letterSizeLevel) / 100f
                            )
                        }
                    )
                    SectionDivider()
                    SettingsItem(
                        icon = Icons.TwoTone.Storefront,
                        title = tr(R.string.s_bujushichang),
                        subtitle = tr(R.string.s_qushangdiangengduobuju),
                        onClick = onNavigateToMarket,
                        showArrow = true,
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_houxuanci), content = {
                    SettingsSliderItem(
                        icon = Icons.TwoTone.FormatSize,
                        title = tr(R.string.s_houxuanzidaxiao),
                        valueText = "${candidateTextSize.roundToInt()}sp",
                        value = candidateTextSize,
                        onValueChange = { candidateTextSize = it },
                        onValueChangeFinished = {
                            SettingsPreferences.setCandidateTextSize(
                                context, candidateTextSize.roundToInt()
                            )
                        },
                        valueRange = 12f..22f,
                        steps = 9,
                        rangeStartLabel = "12sp",
                        rangeEndLabel = "22sp",
                        footer = {
                            // 只变字号：候选数与注释固定，避免看不出是本项还是别项在起作用
                            CandidateLinePreview(
                                candidateTextSize = candidateTextSize.roundToInt(),
                                candidateCount = 4,
                                showComments = false,
                            )
                        }
                    )
                    SectionDivider()
                    SettingsToggleItem(
                        icon = Icons.AutoMirrored.TwoTone.Comment,
                        title = tr(R.string.s_bianmazhushi),
                        subtitle = tr(R.string.s_zaihouxuancipangxianshiduiying_de529),
                        checked = showComments,
                        onCheckedChange = { show ->
                            showComments = show
                            SettingsPreferences.setShowCandidateComments(context, show)
                        },
                        footer = {
                            CandidateLinePreview(
                                candidateTextSize = 18,
                                candidateCount = 4,
                                showComments = showComments,
                            )
                        }
                    )
                    SectionDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = tr(R.string.s_bianmaxianshi),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = tr(R.string.s_xuanzeshurubianmadexianshiweizhi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SettingsRadioItem(
                        icon = Icons.AutoMirrored.TwoTone.Input,
                        title = tr(R.string.s_xianshizaishurukuang),
                        selected = inputTextLocation == SettingsPreferences.INPUT_TEXT_INPUT_BOX,
                        onSelect = {
                            inputTextLocation = SettingsPreferences.INPUT_TEXT_INPUT_BOX
                            SettingsPreferences.setInputTextLocation(
                                context, SettingsPreferences.INPUT_TEXT_INPUT_BOX
                            )
                        }
                    )
                    SettingsRadioItem(
                        icon = Icons.TwoTone.ViewAgenda,
                        title = tr(R.string.s_xianshizaihouxuanlan),
                        selected = inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR,
                        onSelect = {
                            inputTextLocation = SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                            SettingsPreferences.setInputTextLocation(
                                context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                            )
                        }
                    )
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        // 两个容器同框：编码落在输入框还是候选栏，一眼可见
                        CodePositionPreview(
                            codeInInputBox = inputTextLocation ==
                                SettingsPreferences.INPUT_TEXT_INPUT_BOX
                        )
                    }
                    SectionDivider()
                    SettingsSliderItem(
                        icon = Icons.AutoMirrored.TwoTone.ViewList,
                        title = tr(R.string.s_meiyehouxuancishu),
                        valueText = tr(R.string.s_ge, pageSize.roundToInt()),
                        value = pageSize,
                        onValueChange = { pageSize = it },
                        onValueChangeFinished = {
                            SettingsPreferences.setPageSize(context, pageSize.roundToInt())
                        },
                        valueRange = 5f..50f,
                        steps = 44,
                        subtitle = tr(R.string.s_xiugaihouxudaofanganshezhizhon_603c8),
                        footer = {
                            // 只变数量：字号固定 18sp
                            CandidateLinePreview(
                                candidateTextSize = 18,
                                candidateCount = pageSize.roundToInt(),
                                showComments = false,
                            )
                        }
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_shurukuangshipei), content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Dialpad,
                        title = tr(R.string.s_shuzikuangzidongshuzijianpan),
                        subtitle = tr(R.string.s_jinruhaoma_yanzhengmadengshuzi_1aed9),
                        checked = autoNumberKeyboard,
                        onCheckedChange = { enabled ->
                            autoNumberKeyboard = enabled
                            SettingsPreferences.setAutoNumberKeyboardEnabled(context, enabled)
                        }
                    )
                })
            }
        }
    }
}

/** 组内分割线：与主设置页一致，从图标右缘起缩进（40dp 图标 + 16dp 行内边距）。 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}
