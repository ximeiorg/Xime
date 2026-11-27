package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
    onNavigateToRimeFileBrowser: () -> Unit = {},
    onNavigateToSchemaDictBrowser: () -> Unit = {},
    onNavigateToImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SchemaSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // F6: 从方案市场/导入返回时自动重扫描，新装方案立即出现
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_shurufangan)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = tr(R.string.s_gengduo))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_fanganshichang)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToMarket()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Storefront, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_fangancibiao)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSchemaDictBrowser()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Description, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_wenjianguanliqi)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToRimeFileBrowser()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ActivePackageCard(
                        name = uiState.activePackageName,
                        version = uiState.activePackageVersion,
                        onClick = onNavigateToImported,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                item {
                    Text(
                        text = tr(R.string.s_yiqiyong),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                val enabledSchemas = uiState.allSchemas.filter { it.schemaId in uiState.enabledSchemas }

                if (enabledSchemas.isEmpty()) {
                    item {
                        Text(
                            text = tr(R.string.s_zanweiqiyongrenhefangan),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                items(enabledSchemas, key = { it.schemaId }) { schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = true,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = schema.schemaId == uiState.currentSchema,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = tr(R.string.s_tianjiashurufanganhou_xuyaodia_7ee14),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val disabledSchemas = uiState.allSchemas.filter { it.schemaId !in uiState.enabledSchemas }

                if (disabledSchemas.isEmpty()) {
                    item {
                        Text(
                            text = tr(R.string.s_meiyouqitakeyongfangan),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor(),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                items(disabledSchemas, key = { it.schemaId }) { schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = false,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = false,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                    )
                }
            }

            Button(
                onClick = { viewModel.deploySchema() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (uiState.isDeploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(tr(R.string.s_bushufangan))
            }
        }
    }
}

/**
 * 「当前方案集」卡片：显示正在使用的方案包，点击进入方案包页（切换/恢复内置）。
 */
@Composable
private fun ActivePackageCard(
    name: String,
    version: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr(R.string.s_dangqianfanganji),
                    style = MaterialTheme.typography.labelSmall,
                    color = settingsSecondaryColor(),
                )
                Text(
                    text = if (name.isBlank()) tr(R.string.s_xitongneizhifangan) else name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(tr(R.string.s_chakanyixiazaihuodaorudefanganbao))
                        if (version.isNotBlank()) append(" · v$version")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SchemaToggleItem(
    schema: SchemaMeta,
    enabled: Boolean,
    isCompiled: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (enabled && isCompiled) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schema.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    if (isCurrent && isCompiled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = tr(R.string.s_dangqianfangan),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (schema.version.isNotEmpty()) {
                        Text(
                            text = "${schema.version}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (schema.author.isNotEmpty()) {
                        Text(
                            text = schema.author,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (isCompiled) {
                        Text(
                            text = tr(R.string.s_yibianyi),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

