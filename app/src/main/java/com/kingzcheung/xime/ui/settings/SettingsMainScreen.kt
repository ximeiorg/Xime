package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.i18n.tr

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.LibraryBooks
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.AutoFixHigh
import androidx.compose.material.icons.twotone.Backup
import androidx.compose.material.icons.twotone.Ballot

import androidx.compose.material.icons.twotone.Build
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.GraphicEq
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material.icons.twotone.Straighten
import androidx.compose.material.icons.twotone.TableChart
import androidx.compose.material.icons.twotone.ToggleOn
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material.icons.twotone.TypeSpecimen
import androidx.compose.material.icons.twotone.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsMainContent(
    onNavigateToSchema: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
    onNavigateToTheme: () -> Unit,
    onNavigateToKeyEffect: () -> Unit,
    onNavigateToLayoutDisplay: () -> Unit,
    onNavigateToDictionary: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToModelLocal: () -> Unit = {},
    onNavigateToSmartPrediction: () -> Unit,
    onNavigateToCorrection: () -> Unit = {},
    onNavigateToFuzzyPinyin: () -> Unit = {},
    onNavigateToSpeechToText: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToClipboard: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 扩展商店可更新计数：进主页恢复缓存并按节流后台刷新（失败静默），展示在入口角标
    LaunchedEffect(Unit) {
        MarketUpdateChecker.maybeRefresh(context)
    }
    val marketUpdates by MarketUpdateChecker.summary.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = Color.Unspecified,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = Color.Unspecified
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = tr(R.string.s_shurufa), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.Keyboard,
                        title = tr(R.string.s_qiyongshurufa),
                        subtitle = tr(R.string.s_zaixitongshezhizhongqiyongxima_44550),
                        onClick = {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.ToggleOn,
                        title = tr(R.string.s_xuanzeshurufa),
                        subtitle = tr(R.string.s_jiangximasheweidangqianshurufa),
                        onClick = {
                            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                                as InputMethodManager
                            imm.showInputMethodPicker()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    var testText by remember { mutableStateOf("") }
                    var isFocused by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusEvent { isFocused = it.isFocused }
                                .clip(RoundedCornerShape(28.dp))
                                .background(
                                    if (isFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            BasicTextField(
                                value = testText,
                                onValueChange = { testText = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                singleLine = false,
                                maxLines = 3,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (testText.isEmpty() && !isFocused) {
                                            Text(
                                                tr(R.string.s_dianjicichukaishishuruceshi),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        if (testText.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { testText = "" }) {
                                    Text(
                                        tr(R.string.s_qingchu),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                })
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_appearance), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.Palette,
                        title = tr(R.string.s_zhutiyudingzhi),
                        subtitle = tr(R.string.s_zidingyiwaiguanheyangshi),
                        onClick = onNavigateToTheme,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Vibration,
                        title = tr(R.string.s_anjianxiaoguo),
                        subtitle = tr(R.string.s_anjianyinxiaohezhendongfankui),
                        onClick = onNavigateToKeyEffect,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.TableChart,
                        title = tr(R.string.s_bujuyuxianshi),
                        subtitle = tr(R.string.s_houxuancixianshi_jianpanbujudeng),
                        onClick = onNavigateToLayoutDisplay,
                        showArrow = true
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_fanganyuciku), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.KeyboardAlt,
                        title = tr(R.string.s_shurufangan),
                        subtitle = tr(R.string.s_guanlishurufangan),
                        onClick = onNavigateToSchema,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Ballot,
                        title = tr(R.string.s_cikuguanli),
                        subtitle = tr(R.string.s_yonghuciku_kuaijieduanyuyuciti_be6d2),
                        onClick = onNavigateToDictionary,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 模糊拼音改的是全拼方案的拼写规则（写 custom.yaml、需部署），归方案类
                    SettingsItem(
                        icon = Icons.TwoTone.Translate,
                        title = tr(R.string.s_mohupinyin),
                        subtitle = tr(R.string.s_mohupinyin_jianjie),
                        onClick = onNavigateToFuzzyPinyin,
                        showArrow = true
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_kuozhan), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.Storefront,
                        title = tr(R.string.s_kuozhanshangdian),
                        subtitle = tr(R.string.s_xiazaishurufangan_moxing_chajian),
                        onClick = onNavigateToMarket,
                        showArrow = true,
                        badgeText = marketUpdates.totalUpdates.takeIf { it > 0 }
                            ?.let { tr(R.string.s_xiangkegengxin, it) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Extension,
                        title = tr(R.string.s_chajianguanli),
                        subtitle = tr(R.string.s_guanliyianzhuangdechajian),
                        onClick = onNavigateToPlugins,
                        showArrow = true
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_zhineng), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.AutoAwesome,
                        title = tr(R.string.s_zhinenglianxiang),
                        subtitle = tr(R.string.s_jiyu_ai_moxingdezhinenglianxia_5cce9),
                        onClick = onNavigateToSmartPrediction,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.AutoFixHigh,
                        title = tr(R.string.s_zhinengjiucuo_shiyanxing),
                        subtitle = tr(R.string.s_ancuoxianglinjianshigeichujiuz_834ec),
                        onClick = onNavigateToCorrection,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 模型文件管理跟随使用它的智能能力（联想 / 纠错）放在一处
                    SettingsItem(
                        icon = Icons.TwoTone.Straighten,
                        title = tr(R.string.s_moxingguanli),
                        subtitle = tr(R.string.s_guanlibendixiazaidezhinengmoxing),
                        onClick = onNavigateToModelLocal,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    var sttEnabled by remember { mutableStateOf(SettingsPreferences.isSttEnabled(context)) }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.GraphicEq,
                        title = tr(R.string.s_yuyinzhuanwenben),
                        subtitle = tr(R.string.s_zaixian_asr_fuwu_xuanzhuangyuy_74a1a),
                        checked = sttEnabled,
                        showArrow = true,
                        onClick = {
                            if (sttEnabled) onNavigateToSpeechToText()
                        },
                        onCheckedChange = { enabled ->
                            sttEnabled = enabled
                            SettingsPreferences.setSttEnabled(context, enabled)
                        }
                    )
                })
            }

            item {
                // 分组含两类"本地数据/数据流向"设置：剪贴板（内容与同步）优先，其后是词典与配置的同步备份
                SettingsSection(title = tr(R.string.s_shujuyutongbu), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.ContentPaste,
                        title = tr(R.string.s_jiantieban),
                        subtitle = tr(R.string.s_tupianjilu_tijixianzhi_zhanyon_fa4c6),
                        onClick = onNavigateToClipboard,
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Backup,
                        title = tr(R.string.s_tongbuyubeifen),
                        subtitle = tr(R.string.s_duoshebeicidianhutongyupeizhib_1efa8),
                        onClick = onNavigateToBackup,
                        showArrow = true
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_guanyu), content = {
                    SettingsItem(
                        icon = Icons.TwoTone.Description,
                        title = tr(R.string.s_shiyongwendang),
                        subtitle = "ime.ximei.me",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ime.ximei.me"))
                            context.startActivity(intent)
                        },
                        showArrow = true
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Info,
                        title = tr(R.string.s_guanyuxima),
                        subtitle = tr(R.string.s_banbenxinxi_kaifazhe_lianxifangshi),
                        onClick = onNavigateToAbout,
                        showArrow = true
                    )
                })
            }
        }
    }
}
