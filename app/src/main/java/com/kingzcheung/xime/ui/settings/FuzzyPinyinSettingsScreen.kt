package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.settings.FuzzyPinyinGroup
import com.kingzcheung.xime.settings.FuzzyPinyinManager
import com.kingzcheung.xime.settings.FuzzyPinyinRule
import com.kingzcheung.xime.settings.FuzzyPinyinRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模糊拼音设置页。
 *
 * 开关只是草稿：点右上角「应用」才落 prefs，并对账各方案 custom.yaml、部署使其生效；
 * 不点「应用」直接退出等于放弃本次修改。模糊音最终编译进 prism，所以必须走一次部署。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuzzyPinyinSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(FuzzyPinyinManager.getSelectedRules(context)) }
    var supported by remember { mutableStateOf<List<FuzzyPinyinManager.SchemaSupport>>(emptyList()) }
    var saved by remember { mutableStateOf(selected) }
    var dirty by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // 统一应用：落 prefs → 对账（写 custom.yaml 托管块）→ 需要时部署 → 刷新适用方案。
    suspend fun applyRules() {
        applying = true
        FuzzyPinyinManager.setSelectedRules(context, selected)
        val (result, deployOk) = withContext(Dispatchers.IO) {
            val reconciled = FuzzyPinyinManager.reconcile(context)
            val ok = if (reconciled.changedSchemas.isNotEmpty()) {
                SchemeDeployer.deploy(context).ok
            } else {
                true
            }
            reconciled to ok
        }
        supported = withContext(Dispatchers.IO) {
            FuzzyPinyinManager.applicableSchemas(context)
        }
        applying = false
        saved = selected
        dirty = false
        status = buildStatus(result, deployOk, supported)
    }

    LaunchedEffect(Unit) {
        // 只读加载适用方案；不自动对账/部署——未点「应用」的草稿不落地。
        supported = withContext(Dispatchers.IO) {
            FuzzyPinyinManager.applicableSchemas(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(tr(R.string.s_mohupinyin)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = tr(R.string.s_fanhui),
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { scope.launch { applyRules() } },
                            enabled = dirty && !applying,
                        ) {
                            Text(tr(R.string.s_fuzzy_apply))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                if (applying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    text = when {
                        applying -> tr(R.string.s_fuzzy_applying)
                        dirty -> tr(R.string.s_fuzzy_pending_apply)
                        else -> status ?: tr(R.string.s_fuzzy_scope_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FuzzyPinyinGroup.values().forEach { group ->
                item(key = group.name) {
                    val rules = FuzzyPinyinRules.all.filter { it.group == group }
                    SettingsSection(title = tr(group.titleRes)) {
                        rules.forEachIndexed { index, rule ->
                            FuzzyRuleRow(
                                rule = rule,
                                checked = rule.id in selected,
                                enabled = !applying,
                                onCheckedChange = { checked ->
                                    val next = if (checked) selected + rule.id else selected - rule.id
                                    dirty = next != saved
                                    // 只改草稿；prefs 在点「应用」时才写，未应用退出即放弃。
                                    selected = next
                                },
                            )
                            if (index != rules.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            val conflicts = FuzzyPinyinRules.activeConflicts(selected)

            item(key = "supported") {
                SettingsSection(title = tr(R.string.s_fuzzy_shiyongfangan)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val names = supported.filter { it.supported }.map { it.displayName }
                        Text(
                            text = if (names.isEmpty()) {
                                tr(R.string.s_fuzzy_none)
                            } else {
                                names.joinToString("、")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = tr(R.string.s_fuzzy_scope_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = tr(R.string.s_fuzzy_declare_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "hint") {
                SettingsSection(title = tr(R.string.s_shuoming)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr(R.string.s_fuzzy_transitive_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        conflicts.forEach { conflict ->
                            Text(
                                text = tr(conflict.messageRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FuzzyRuleRow(
    rule: FuzzyPinyinRule,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr(rule.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

private fun buildStatus(
    result: FuzzyPinyinManager.ReconcileResult,
    deployOk: Boolean,
    supported: List<FuzzyPinyinManager.SchemaSupport>,
): String? {
    if (!deployOk) return tr(R.string.s_fuzzy_apply_failed)
    val names = supported.filter { it.supported }.associate { it.schemaId to it.displayName }
    val parts = mutableListOf<String>()
    val applied = result.appliedSchemas.map { names[it] ?: it }
    if (applied.isNotEmpty()) {
        parts += tr(R.string.s_fuzzy_applied_to, applied.joinToString("、"))
    }
    val conflicts = result.skipped
        .filter { it.reason == FuzzyPinyinManager.SkipReason.USER_PATCH_CONFLICT }
        .map { names[it.schemaId] ?: it.schemaId }
    if (conflicts.isNotEmpty()) {
        parts += tr(R.string.s_fuzzy_user_patch_conflict, conflicts.joinToString("、"))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
