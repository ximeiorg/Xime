package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.correction.CorrectionPriors
import com.kingzcheung.xime.settings.SettingsPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsPreferences.isCorrectorEnabled(context)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_zhinengjiucuo)) },
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
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                SettingsSection(title = tr(R.string.s_gongnengkaiguan), content = {
                    SettingsToggleItem(
                        icon = Icons.Default.AutoFixHigh,
                        title = tr(R.string.s_qiyongzhinengjiucuo),
                        subtitle = tr(R.string.s_ancuoxianglinjianshi_zaihouxua_df488),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            SettingsPreferences.setCorrectorEnabled(context, it)
                            // 插件版：开/关先验推送器（立即生效，无需重启）
                            CorrectionPriors.enabled = it
                        }
                    )
                })
            }

            item {
                SettingsSection(title = tr(R.string.s_shuoming), content = {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = tr(R.string.s_morenzhichi_wubi86_wubipinyin_048a6),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor()
                        )
                        Text(
                            text = tr(R.string.s_jinggao1_cigongnengshiyanxingz_daa71),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = tr(R.string.s_jinggao2_buzhichirenhezidingyi_aaabd),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = tr(R.string.s_jiuzhenghouxuanbiaozhu_jiucuo_23e5d),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = tr(R.string.s_buhuizidongtihuannishurudeneir_d0291),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                })
            }
        }
    }
}
