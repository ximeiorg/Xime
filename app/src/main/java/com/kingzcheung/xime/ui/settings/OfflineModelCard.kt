package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.AsrModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 集成在语音转文本设置页内的离线模型状态卡片。
 * 模型由"模型中心"下载（filesDir/models/<id>/），本卡片展示已安装模型的
 * 状态，并提供已安装模型间的切换（切换即写偏好并卸载常驻引擎，下次语音生效）。
 * 生效模型只从已安装模型里选；未下载任何模型时不默认指定模型，整卡可点跳转
 * 扩展商店的模型页（[onNavigateToDownload]）。
 */
@Composable
internal fun OfflineModelCard(
    onNavigateToDownload: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { AsrModelManager(context) }

    var showModelPicker by remember { mutableStateOf(false) }

    // 模型清单来自「模型中心」远程索引；进入本页时确保索引已加载，
    // 并订阅 StateFlow，索引返回后自动重组刷新可选项
    val indexedModels by ModelManager.modelsFlow.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ModelManager.loadFromRemote(context)
        }
    }

    // 生效模型：用户所选（已安装）优先，否则回退到首个已安装模型；未下载任何模型时为 null
    val models = remember(indexedModels) { modelManager.getAsrModels() }
    val installedModels = models.filter { modelManager.isModelInstalled(it.id) }
    val model = modelManager.getSelectedModelInfo()
    val downloaded = model != null

    // 有多个已安装模型时才需要切换入口
    val switchable = installedModels.size > 1

    Card(
        onClick = {
            // 无已安装模型：点击跳扩展商店下载；否则打开已安装模型选择（与"在线服务"一致的交互）
            if (installedModels.isEmpty()) onNavigateToDownload() else showModelPicker = true
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (downloaded)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (downloaded)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr(R.string.s_lixianyuyinshibie),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = model?.let { "${it.name} · ${it.size}" } ?: tr(R.string.s_shangweianzhuangmoxing),
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                }

                if (downloaded) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = tr(R.string.s_yianzhuang),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                when {
                    switchable -> {
                        Text(
                            text = tr(R.string.s_qiehuan),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 无已安装模型：右侧给出去下载入口，与整卡点击一致
                    installedModels.isEmpty() -> {
                        Text(
                            text = tr(R.string.s_quxiazai),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = if (model != null)
                    tr(R.string.s_shiyong_lixianshibie_wuwangluo_58a45, model.name)
                else
                    tr(R.string.s_shangweianzhuangmoxing_dianjiq_fd6b5),
                style = MaterialTheme.typography.bodyMedium,
                color = settingsSecondaryColor()
            )

            // 已安装模型切换：与"在线服务"一致的单选弹窗交互。
            // 切换后卸载常驻引擎，下一次语音会话即按新模型加载。
            if (showModelPicker) {
                SettingsSingleChoiceDialog(
                    title = tr(R.string.s_xuanzelixianyuyinmoxing),
                    options = installedModels
                        .map {
                            SettingsChoiceOption(
                                id = it.id,
                                title = it.name,
                                subtitle = "${it.size} · ${it.language}"
                            )
                        },
                    selectedId = model?.id,
                    onSelect = { id ->
                        showModelPicker = false
                        if (id != model?.id) {
                            modelManager.setModel(id)
                            scope.launch(Dispatchers.IO) {
                                AsrBackendFactory.releaseModel()
                            }
                        }
                    },
                    onDismiss = { showModelPicker = false }
                )
            }
        }
    }
}
