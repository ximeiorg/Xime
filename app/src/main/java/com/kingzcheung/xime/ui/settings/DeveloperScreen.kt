package com.kingzcheung.xime.ui.settings

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
                title = { Text("开发者选项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
                            title = "手写数据采集",
                            onClick = onNavigateToHandwritingCapture
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = "插件开发模式",
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
                                                "已开启。请再开启系统 USB 调试，电脑端执行 xipm dev 连接"
                                            } else {
                                                "已关闭：adb 无法再注入插件"
                                            },
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        )
                        Text(
                            text = "电脑热调试前置：手机开启 USB 调试（系统开发者选项）+ 电脑安装 adb，" +
                                "然后用 `xipm dev <插件目录>` 连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = "按键坐标日志",
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
                                            if (enabled) "已开启：每次按键将记录触摸坐标（仅本地）"
                                            else "已关闭",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        )
                        Text(
                            text = "开启后记录每次按键的触摸坐标到本地日志，用于训练相邻键纠错模型。" +
                                "不上传、不含输入内容，日志位于 files/logs/key_tap.jsonl。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                        )
                    }
                }
            }
            item {
                Text(
                    text = "开发者功能仅用于数据采集与插件调试，普通用户无需开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
