package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 开发者选项（设置 → 关于 → 连点设备信息 7 次解锁的统一入口）：
 * 收纳手写数据采集、插件开发模式等开发者功能，避免关于页彩蛋条目无限膨胀。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperContent(
    onBack: () -> Unit,
    onNavigateToHandwritingCapture: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pluginDevModeEnabled by remember {
        mutableStateOf(SettingsPreferences.isPluginDevModeEnabled(context))
    }
    var keyTapLogEnabled by remember {
        mutableStateOf(SettingsPreferences.isKeyTapLogEnabled(context))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_kaifazhexuanxiang)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column {
                        SettingsItem(
                            icon = Icons.Default.Edit,
                            title = tr(R.string.s_shouxieshujucaiji),
                            onClick = onNavigateToHandwritingCapture
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = tr(R.string.s_chajiankaifamoshi),
                            onClick = {},
                            trailing = {
                                Switch(
                                    checked = pluginDevModeEnabled,
                                    onCheckedChange = { enabled ->
                                        pluginDevModeEnabled = enabled
                                        SettingsPreferences.setPluginDevModeEnabled(context, enabled)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (enabled) {
                                                tr(R.string.s_yikaiqi_qingzaikaiqixitong_usb_1c395)
                                            } else {
                                                tr(R.string.s_yiguanbi_adb_wufazaizhuruchajian)
                                            },
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        )
                        Text(
                            text = tr(R.string.s_diannaorediaoshiqianzhi_shouji_8f2c4) +
                                tr(R.string.s_ranhouyong_xipm_dev_chajianmul_7938f),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = tr(R.string.s_anjianzuobiaorizhi),
                            onClick = {},
                            trailing = {
                                Switch(
                                    checked = keyTapLogEnabled,
                                    onCheckedChange = { enabled ->
                                        keyTapLogEnabled = enabled
                                        SettingsPreferences.setKeyTapLogEnabled(context, enabled)
                                        if (enabled) KeyTapLogger.init(context)
                                        KeyTapLogger.enabled = enabled
                                        android.widget.Toast.makeText(
                                            context,
                                            if (enabled) tr(R.string.s_yikaiqi_meicianjianjiangjiluch_38ff6)
                                            else tr(R.string.s_yiguanbi),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        )
                        Text(
                            text = tr(R.string.s_kaiqihoujilumeicianjiandechumo_772f1) +
                                tr(R.string.s_bushangchuan_buhanshuruneirong_3e49b),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                        )
                    }
                }
            }
            item {
                Text(
                    text = tr(R.string.s_kaifazhegongnengjinyongyushuju_9fa06),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
