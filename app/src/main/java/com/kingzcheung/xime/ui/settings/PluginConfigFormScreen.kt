package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.UiNode
import com.kingzcheung.xime.plugin.core.config.UiNodeType
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginConfigFormScreen(
    pluginId: String,
    plugin: IPluginConfigurable,
    pluginName: String,
    schema: List<UiNode> = emptyList(),
    onBack: () -> Unit,
    embedded: Boolean = false,
    /** 字段未声明 section 时的分组标题（默认「插件配置」）；嵌入到多插件页面时可带上插件名标明归属。 */
    fallbackSectionTitle: String = tr(R.string.s_chajianpeizhi),
) {
    val context = LocalContext.current
    val configStore = remember(pluginId) {
        PluginConfigStoreImpl(context.applicationContext as android.app.Application, pluginId)
    }
    // 调用方已带 schema（插件设置页已在 IO 读完）时直接用；否则本组件自己在 IO 读：
    // 组合期不进插件运行时（旧的 remember 同步调用会阻塞主线程，并与插件卸载/重载竞态）。
    val needLoad = schema.isEmpty()
    val fallbackState = rememberPluginSettingsSchema(if (needLoad) plugin else null, pluginId)
    val fields = if (needLoad) fallbackState.schema else schema
    val groupedFields = remember(fields) { fields.groupBy { it.section.orEmpty() } }

    val dynamicOptions = remember(plugin) { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(plugin, fields) {
        fields.filter { it.type == UiNodeType.SELECT || it.type == UiNodeType.MULTI_SELECT }
            .filter { it.key != null }
            .forEach { field ->
                val opts = withContext(Dispatchers.IO) {
                    runCatching { plugin.getOptions(field.key!!) }.getOrNull()
                }
                if (opts != null) dynamicOptions[field.key!!] = opts
            }
    }

    @Composable
    fun sectionContent(section: String, sectionFields: List<UiNode>) {
        SettingsSection(
            title = section.ifBlank { fallbackSectionTitle },
            content = {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sectionFields.forEach { field ->
                        UiNodeEditor(
                            field = field,
                            configStore = configStore,
                            options = field.options.ifEmpty {
                                field.key?.let { dynamicOptions[it].orEmpty() } ?: emptyList()
                            },
                            plugin = plugin
                        )
                    }
                }
            }
        )
    }

    // 插件声明 allowCustomHosts 时，配置中填写的 HTTP(S) 服务器域名自动获得联网授权，
    // 兑现 manifest helpText「域名将自动获得联网授权」的承诺，无需用户再手动寻找授权入口。
    fun autoAuthorizeConfiguredHosts() {
        val allowCustom = PluginManager.getAllInstallPlugins()
            .firstOrNull { it.id == pluginId }
            ?.allowCustomHosts ?: false
        if (!allowCustom) return
        configStore.keys().forEach { key ->
            configStore.get(key)?.let { value ->
                NetworkPolicy.extractHttpHost(value)?.let { host ->
                    SettingsPreferences.authorizePluginHost(context, pluginId, host)
                }
            }
        }
    }

    @Composable
    fun saveButton() {
        Button(
            onClick = {
                var allValid = true
                fields.forEach { field ->
                    if (field.type == UiNodeType.SECRET && field.required && field.key != null) {
                        val current = configStore.get(field.key!!)
                        if (current.isNullOrBlank()) allValid = false
                    }
                }
                if (!allValid) {
                    Toast.makeText(context, tr(R.string.s_qingtianxiebitianpeizhi), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                autoAuthorizeConfiguredHosts()
                Toast.makeText(context, tr(R.string.s_peizhiyibaocun), Toast.LENGTH_SHORT).show()
                if (!embedded) onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(tr(R.string.s_baocun))
        }
    }

    if (embedded) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedFields.forEach { (section, sectionFields) ->
                sectionContent(section, sectionFields)
            }
            saveButton()
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(pluginName) },
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
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                groupedFields.forEach { (section, sectionFields) ->
                    item {
                        sectionContent(section, sectionFields)
                    }
                }

                item {
                    saveButton()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UiNodeEditor(
    field: UiNode,
    configStore: PluginConfigStore,
    options: List<String>,
    plugin: IPluginConfigurable
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 表单字段必须绑定 configStore key；无 key 的节点（展示型误入表单）不渲染
    val key = field.key ?: return
    var buttonBusy by remember(key) { mutableStateOf(false) }
    var value by rememberSaveable(key) {
        mutableStateOf(configStore.get(key) ?: field.defaultValue ?: "")
    }
    var showSecret by rememberSaveable(key) { mutableStateOf(false) }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }

    fun persist() {
        configStore.set(key, value)
    }

    when (field.type) {
        // 展示型节点（SECTION/METRIC/DIVIDER）在设置表单中忽略
        UiNodeType.SECTION, UiNodeType.METRIC, UiNodeType.DIVIDER -> Unit
        UiNodeType.TEXTAREA -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                minLines = 4,
                maxLines = 8,
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.TEXT,
        UiNodeType.NUMBER -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = if (field.type == UiNodeType.NUMBER) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.SECRET -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label ?: "") },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (showSecret) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecret) tr(R.string.s_yincang) else tr(R.string.s_xianshi)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        UiNodeType.SWITCH -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = field.label ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    field.helpText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = value == "true",
                    onCheckedChange = {
                        value = it.toString()
                        persist()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }

        UiNodeType.SELECT -> {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label ?: "") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    supportingText = if (options.isEmpty() && field.options.isEmpty())
                        { { Text(tr(R.string.s_zanwukeyongxuanxiang)) } }
                    else
                        field.helpText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                value = option
                                persist()
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        UiNodeType.MULTI_SELECT -> {
            val selected = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = field.label ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                field.helpText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (options.isEmpty()) {
                    Text(
                        text = tr(R.string.s_zanwukeyongxuanxiang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option in selected,
                                onClick = {
                                    val next = if (option in selected) {
                                        selected - option
                                    } else {
                                        selected + option
                                    }
                                    value = next.sorted().joinToString(",")
                                    persist()
                                },
                                label = { Text(option) }
                            )
                        }
                    }
                }
            }
        }

        UiNodeType.BUTTON -> {
            Button(
                onClick = {
                    val action = key
                    if (action.isNullOrBlank()) {
                        Toast.makeText(context, tr(R.string.s_weipeizhidongzuo), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    buttonBusy = true
                    scope.launch {
                        // 结果语义：null/空 = 成功（动作无错误消息）；非空 = 失败原因。
                        // 抛异常也必须报错——曾经 runCatching{}.getOrNull() 把异常吞成 null，
                        // 于是动作报错也会提示"成功"（issue #1061 假成功的一半来源）。
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { plugin.onAction(action) }
                        }
                        buttonBusy = false
                        val failure = outcome.exceptionOrNull()
                        val error = if (failure != null) {
                            tr(R.string.s_zhixingshibai, failure.message ?: failure.javaClass.simpleName)
                        } else {
                            outcome.getOrNull()
                        }
                        if (error.isNullOrBlank()) {
                            Toast.makeText(context, tr(R.string.s_chenggong), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !buttonBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (buttonBusy) tr(R.string.s_chulizhong) else field.label ?: "")
            }
        }
    }
}
