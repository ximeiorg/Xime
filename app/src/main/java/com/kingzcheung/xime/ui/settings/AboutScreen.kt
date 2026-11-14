package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.PersonOutline
import androidx.compose.material.icons.twotone.PrivacyTip
import androidx.compose.material.icons.twotone.Storage
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

data class LicenseItem(
    val name: String,
    val license: String,
    val url: String
)

object AppInfo {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
    val gitHash: String = BuildConfig.GIT_HASH
    
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    
    val dependencies = listOf(
        LicenseItem(
            name = tr(R.string.s_xima),
            license = "GPL-3.0",
            url = "https://github.com/ximeiorg/Xime"
        ),
        LicenseItem(
            name = "librime",
            license = "BSD-3-Clause",
            url = "https://github.com/rime/librime"
        ),
        LicenseItem(
            name = "AndroidX Core",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx"
        ),
        LicenseItem(
            name = "Jetpack Compose",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose"
        ),
        LicenseItem(
            name = "Material Design Icons",
            license = "Apache-2.0",
            url = "https://fonts.google.com/icons"
        ),
        LicenseItem(
            name = "Kotlin",
            license = "Apache-2.0",
            url = "https://kotlinlang.org"
        ),
        LicenseItem(
            name = "Navigation Compose",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose/navigation"
        ),
        LicenseItem(
            name = "Lifecycle",
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
        ),
        LicenseItem(
            name = "Boost",
            license = "BSL-1.0",
            url = "https://www.boost.org"
        ),
        LicenseItem(
            name = "OpenCC (Open Chinese Convert)",
            license = "Apache-2.0",
            url = "https://github.com/BYVoid/OpenCC"
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(
    onBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToDeveloper: () -> Unit = {},
    onNavigateToStorageSpace: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var verboseLoggingEnabled by remember {
        mutableStateOf(SettingsPreferences.isVerboseLoggingEnabled(context))
    }
    // 彩蛋入口：1.5 秒内连点"设备信息"卡片 7 次解锁"开发者选项"（平时隐藏，
    // 页内收纳手写数据采集 / 插件开发模式等开发者功能）
    var captureTapCount by remember { mutableStateOf(0) }
    var lastCaptureTapMs by remember { mutableStateOf(0L) }
    var devUnlocked by rememberSaveable { mutableStateOf(false) }
    fun onDeviceInfoTapped() {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTapMs > 1500L) captureTapCount = 0
        lastCaptureTapMs = now
        captureTapCount++
        if (captureTapCount >= 7) {
            captureTapCount = 0
            devUnlocked = true
            android.widget.Toast.makeText(context, tr(R.string.s_yijiesuokaifazherukou), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_guanyu)) },
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
            // App 信息
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = tr(R.string.s_xima_xime),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tr(R.string.s_jiyurime_goujian_zhichiwubi_pi_a444c),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = tr(R.string.s_banben, AppInfo.versionName, AppInfo.versionCode),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = tr(R.string.s_goujian, AppInfo.gitHash),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { uriHandler.openUri("https://github.com/ximeiorg/Xime/releases") },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tr(R.string.s_chakanzuixinbanben),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            // 作者信息
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = tr(R.string.s_zuozhe),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { uriHandler.openUri("https://github.com/kingzcheung") },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.PersonOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kor1 (kingzcheung)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "github.com/kingzcheung",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.TwoTone.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            
            // 源代码
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            uriHandler.openUri("https://github.com/ximeiorg/Xime")
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(R.string.s_yuandaima),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "github.com/ximeiorg/Xime",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            // 链接项
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column {
                        SettingsItem(
                            icon = Icons.TwoTone.PrivacyTip,
                            title = tr(R.string.s_yinsicelve),
                            onClick = onNavigateToPrivacy
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.Description,
                            title = tr(R.string.s_kaiyuanxukezheng),
                            onClick = onNavigateToLicenses
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.BugReport,
                            title = tr(R.string.s_rizhichakanqi),
                            onClick = onNavigateToLogViewer
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            icon = Icons.TwoTone.Storage,
                            title = tr(R.string.s_cunchukongjian),
                            onClick = onNavigateToStorageSpace
                        )
                        if (devUnlocked) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            SettingsItem(
                                icon = Icons.Default.Code,
                                title = tr(R.string.s_kaifazhexuanxiang),
                                onClick = onNavigateToDeveloper
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            SettingsItem(
                                icon = Icons.TwoTone.BugReport,
                                title = tr(R.string.s_diaoshirizhi),
                                onClick = {},
                                trailing = {
                                    Switch(
                                        checked = verboseLoggingEnabled,
                                        onCheckedChange = { enabled ->
                                            verboseLoggingEnabled = enabled
                                            SettingsPreferences.setVerboseLoggingEnabled(context, enabled)
                                            FileLogger.setVerboseLoggingEnabled(enabled)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            
            // 设备信息
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceInfoTapped() }
                            .padding(16.dp)
                    ) {
                        Text(
                            text = tr(R.string.s_shebeixinxi),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        InfoRow(label = tr(R.string.s_xitongbanben), value = AppInfo.androidVersion)
                        InfoRow(label = tr(R.string.s_shebeixinghao), value = AppInfo.deviceModel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyContent(
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_yinsicelve)) },
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
                .padding(horizontal = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = tr(R.string.s_ximayinsicelve),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        PrivacySection(
                            title = tr(R.string.s_yinsicelvzhaiyao),
                            content = tr(R.string.s_yinsicelvzhaiyaoshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_shiyongfanwei),
                            content = tr(R.string.s_shiyongfanweishuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_shujushouji),
                            content = tr(R.string.s_ximabushoujirenhegerenshenfenx_7e0f8)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_shuruneirong),
                            content = tr(R.string.s_nindesuoyoushuruneirongjinbaoc_31812)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_bendicunchu),
                            content = tr(R.string.s_ximajiangyonghushezhi_yonghuci_e9dfb)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_yingyongquanxian),
                            content = tr(R.string.s_yingyongquanxianshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_wangluoquanxian),
                            content = tr(R.string.s_ximaxuyaowangluoquanxianyongyu_5544d)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_kexuanchajian),
                            content = tr(R.string.s_kexuanchajianshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_yuyinhemaikefeng),
                            content = tr(R.string.s_yuyinhemaikefengshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_jiantiebanhetongbu),
                            content = tr(R.string.s_jiantiebangongnengjinzainindes_cfa37)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_shujugongxiang),
                            content = tr(R.string.s_shujugongxiangshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_shujuanquan),
                            content = tr(R.string.s_shujuanquanshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_nidekongzhi),
                            content = tr(R.string.s_nidekongzhishuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_weichengnianren),
                            content = tr(R.string.s_weichengnianrenshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_celuegengxin),
                            content = tr(R.string.s_celuegengxinshuoming)
                        )
                        
                        PrivacySection(
                            title = tr(R.string.s_kaiyuan),
                            content = tr(R.string.s_ximashikaiyuanruanjian_yuandai_3c8a7)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = tr(R.string.s_gengxinriqi_2026nian4yue),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesContent(
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_kaiyuanxukezheng)) },
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
            items(AppInfo.dependencies) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            uriHandler.openUri(item.url)
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.license,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}