package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsContent(
    onBack: () -> Unit,
    onThemeChanged: () -> Unit = {}
) {
    val viewModel: ThemeSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_zhutiyudingzhi)) },
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
        var previewTheme by remember { mutableStateOf<KeyboardColorScheme?>(null) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = tr(R.string.s_xianshimoshi),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                val currentTheme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: uiState.colorThemes.first()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardThemeCard(
                        theme = currentTheme,
                        isSelected = uiState.darkMode == 2,
                        onClick = {
                            viewModel.setDarkMode(2)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f),
                        title = tr(R.string.s_gensuixitong)
                    )
                    ThemeCard(
                        title = tr(R.string.s_qianse),
                        isSelected = uiState.darkMode == 0,
                        isDark = false,
                        accentColor = currentTheme.accentLight,
                        keyBgColor = currentTheme.keyBgLight,
                        onClick = {
                            viewModel.setDarkMode(0)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeCard(
                        title = tr(R.string.s_shense),
                        isSelected = uiState.darkMode == 1,
                        isDark = true,
                        accentColor = currentTheme.accentDark,
                        keyBgColor = currentTheme.keyBgDark,
                        onClick = {
                            viewModel.setDarkMode(1)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_peisefangan),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            item {
                Text(
                    text = tr(R.string.s_dianjipeiseyulanwanzhengjianpa_7949f),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            uiState.colorThemes.chunked(3).forEach { rowThemes ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            KeyboardThemeCard(
                                theme = theme,
                                isSelected = uiState.colorTheme == theme.id,
                                onClick = {
                                    previewTheme = theme
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowThemes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_tishi_peiseqiehuanhoushezhiyem_f78e8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        previewTheme?.let { theme ->
            ThemePreviewSheet(
                theme = theme,
                onApply = {
                    viewModel.setColorTheme(theme.id)
                    previewTheme = null
                    onThemeChanged()
                },
                onDismiss = { previewTheme = null },
            )
        }
    }
}
