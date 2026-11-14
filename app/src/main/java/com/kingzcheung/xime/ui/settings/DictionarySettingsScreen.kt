package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.WarningAmber
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.DictionaryHubViewModel

/**
 * 「词库管理」入口页。
 *
 * 以前这里是三个页签（自定义短语 / 用户词库 / 方案词库），按"数据落在哪个文件里"分类
 * —— 用户得先认识 `custom_phrase.txt`、`*.userdb`、`*.dict.yaml` 才知道点哪个。现在改成
 * 按"词库内容"下钻：
 *  - 用户词库：输入时自动积累、存在 `<词典名>.userdb` 里的词条；
 *  - 快捷短语：手写的 `custom_phrase`，随输入方案生效；
 *  - 方案自带的静态词表属于方案本身 ⇒ 移到「输入方案 → 方案词表」，这里只留一句指路。
 *
 * 每个目的地都是独立导航路由，因此系统返回键逐层正确，页面内不再需要自绘返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryHubContent(
    onBack: () -> Unit,
    onNavigateToUserDict: () -> Unit,
    onNavigateToCustomPhrase: () -> Unit,
) {
    val viewModel: DictionaryHubViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 从子页面返回时重新统计：导入过码表、新增过短语后数量会变
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_cikuguanli)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = tr(R.string.s_cikuneirong), content = {
                SettingsItem(
                    icon = Icons.TwoTone.Storage,
                    title = tr(R.string.s_yonghuciku),
                    subtitle = tr(R.string.s_shurushizidongjileidecitiao) +
                        (uiState.userDictCount?.let { tr(R.string.s_ben, it) } ?: ""),
                    onClick = onNavigateToUserDict,
                    showArrow = true
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    icon = Icons.TwoTone.DataObject,
                    title = tr(R.string.s_kuaijieduanyu),
                    subtitle = tr(R.string.s_buchongfanganmabiaozhiwaidecha_f3531) +
                        (uiState.customPhraseCount?.let { tr(R.string.s_tiao, it) } ?: ""),
                    onClick = onNavigateToCustomPhrase,
                    showArrow = true
                )
            })

            // 扩展方案带进来的快捷短语是全局的，卸载方案不会带走，是「奇怪候选」最常见的来源
            DictionaryWarningNote(
                title = tr(R.string.s_zhuyishixiang_5b7c1),
                text = tr(R.string.s_kuozhanfanganzidingyiduan_9a3d2),
            )

            Text(
                tr(R.string.s_fanganzidaidejingtaicibiao_dic_cca30),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                tr(R.string.s_kuashebeibanyun_danbencikuyong_176a2) +
                    tr(R.string.s_duoshebeianshijianchuohebingqi_0f0c1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「注意事项」条：本页的软提醒。
 *
 * 底色取 `secondaryContainer`（跟随主题强调色），不用 `errorContainer`——这里要表达的是
 * "别踩这个坑"，不是错误状态；深浅两个主题下都能读清，也不会抢走列表项的注意力。
 */
@Composable
private fun DictionaryWarningNote(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.TwoTone.WarningAmber,
            contentDescription = null,
            tint = scheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSecondaryContainer,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSecondaryContainer,
                lineHeight = 18.sp,
            )
        }
    }
}