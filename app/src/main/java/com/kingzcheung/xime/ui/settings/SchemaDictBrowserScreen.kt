package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.viewmodel.DictionarySettingsViewModel

/**
 * 「方案词表」浏览面板（方案 `.dict.yaml` 静态码表）。
 *
 * 它随方案分发、只读，属于方案本身 ⇒ 入口放在「输入方案 → 方案词表」，
 * 不再作为「词库管理」的一个页签（那里只放用户词库与快捷短语）。
 *
 * @param viewModel 由调用方持有的视图模型；独立页面可直接用默认值。
 */
@Composable
fun SchemaDictBrowserPanel(
    viewModel: DictionarySettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        val schema = uiState.availableSchemas.find { it.schemaId == uiState.selectedSchema }
        ContextSelectorCard(
            title = schema?.name ?: uiState.selectedSchema,
            subtitle = uiState.selectedSchema.ifEmpty { tr(R.string.s_meiyoukeyongdeshurufangan) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            enabled = uiState.availableSchemas.size > 1
        ) { dismiss ->
            for (s in uiState.availableSchemas) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                s.schemaId,
                                style = MaterialTheme.typography.bodySmall,
                                color = settingsSecondaryColor()
                            )
                        }
                    },
                    onClick = {
                        dismiss()
                        viewModel.selectSchema(s.schemaId)
                    }
                )
            }
        }

        RimeSearchField(
            query = uiState.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.selectedSchema.isEmpty() && uiState.availableSchemas.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(tr(R.string.s_meiyoukeyongdeshurufangan), color = settingsSecondaryColor())
            }
        } else {
            Text(
                text = tr(R.string.s_gong_tiaocitiao, uiState.allEntries.size) +
                    if (uiState.searchQuery.isNotEmpty()) tr(R.string.s_sousuojieguo_tiao, uiState.displayedEntries.size) else "",
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryColor(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (uiState.displayedEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.searchQuery.isEmpty()) tr(R.string.s_zanwucitiao) else tr(R.string.s_weizhaodaopipeicitiao),
                        color = settingsSecondaryColor()
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
                ) {
                    items(uiState.displayedEntries, key = { "${it.word}_${it.code}" }) { entry ->
                        DictEntryItem(entry = entry)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaDictBrowserContent(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_fangancibiao)) },
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
        ) {
            SchemaDictBrowserPanel()
        }
    }
}

@Composable
private fun DictEntryItem(entry: DictEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.word,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = entry.code,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}