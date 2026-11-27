package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.ui.ContentMaxWidth
import com.kingzcheung.xime.ui.TwoPaneMinWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep {
    EnableIme,
    SelectSchemas,
    SwitchToIme
}

/** 步骤序号（0 基），用于判断完成/当前状态。 */
private val SetupStep.stepIndex: Int get() = SetupStep.entries.indexOf(this)

/** 横向指示器上的短标签。 */
private val SetupStep.shortLabel: String
    get() = when (this) {
        SetupStep.EnableIme -> tr(R.string.s_qiyong)
        SetupStep.SelectSchemas -> tr(R.string.s_xuanfangan)
        SetupStep.SwitchToIme -> tr(R.string.s_qiehuan)
    }

/** 双栏导航项标题。 */
private val SetupStep.navTitle: String
    get() = when (this) {
        SetupStep.EnableIme -> tr(R.string.s_qiyongshurufa)
        SetupStep.SelectSchemas -> tr(R.string.s_xuanzefangan)
        SetupStep.SwitchToIme -> tr(R.string.s_qiehuanshurufa)
    }

/** 双栏导航项说明。 */
private val SetupStep.navDescription: String
    get() = when (this) {
        SetupStep.EnableIme -> tr(R.string.s_zaixitongshezhizhongqiyongxima_44550)
        SetupStep.SelectSchemas -> tr(R.string.s_xuanzebingbushushurufangan)
        SetupStep.SwitchToIme -> tr(R.string.s_qiehuandaoximashurufa)
    }

/** 步骤正文标题。 */
private val SetupStep.stepTitle: String
    get() = when (this) {
        SetupStep.EnableIme -> tr(R.string.s_buzhou_1_qiyongshurufa)
        SetupStep.SelectSchemas -> tr(R.string.s_buzhou_2_xuanzeshurufangan)
        SetupStep.SwitchToIme -> tr(R.string.s_buzhou_3_qiehuanshurufa)
    }

/** 向导内主操作按钮：限制最大宽度，避免平板上被拉伸得过宽。 */
private fun Modifier.wizardButton(): Modifier =
    this.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    visible: Boolean = true,
    onNavigateToSchemaSettings: () -> Unit,
    onCompleted: () -> Unit
) {
    var currentStep by remember { mutableStateOf(SetupStep.EnableIme) }
    var hasBeenToSettings by remember { mutableStateOf(false) }
    var deployReminder by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从设置页返回后检查已启用的方案（初始为空，用户必须主动选择）
    val enabledSchemas = remember { mutableStateOf(if (hasBeenToSettings) SchemaManager.getEnabledSchemas(context) else emptyList()) }

    // 每次向导重新可见时（从设置页返回），刷新方案列表
    LaunchedEffect(visible) {
        if (visible) {
            enabledSchemas.value = SchemaManager.getEnabledSchemas(context)
        }
    }

    if (visible) {
        // 步骤正文：手机单栏与平板双栏共用同一份内容。
        val stepContent: @Composable (SetupStep) -> Unit = { step ->
            when (step) {
                SetupStep.EnableIme -> EnableImeStep(
                    onNext = { currentStep = SetupStep.SelectSchemas }
                )
                SetupStep.SelectSchemas -> SelectSchemasStep(
                    enabledSchemas = enabledSchemas.value,
                    deployReminder = deployReminder,
                    onNavigateToSchemaSettings = {
                        hasBeenToSettings = true
                        deployReminder = null
                        onNavigateToSchemaSettings()
                    },
                    onNext = {
                        enabledSchemas.value = SchemaManager.getEnabledSchemas(context)
                        if (enabledSchemas.value.isNotEmpty()) {
                            // 产物校验会扫 build 目录，移到 IO 线程避免主线程卡顿
                            scope.launch {
                                val complete = withContext(Dispatchers.IO) {
                                    RimeConfigHelper.isDeploymentComplete(context)
                                }
                                if (!complete) {
                                    deployReminder = tr(R.string.s_fanganyixuanze_danshangweibush_80e59)
                                } else {
                                    deployReminder = null
                                    currentStep = SetupStep.SwitchToIme
                                }
                            }
                        } else {
                            deployReminder = null
                            currentStep = SetupStep.SwitchToIme
                        }
                    }
                )
                SetupStep.SwitchToIme -> SwitchToImeStep(
                    onCompleted = {
                        // 将当前方案设为第一个已启用的方案
                        // 否则 currentSchema 保持默认值 "wubi86"，
                        // 用户可能根本没启用 wubi86，导致键盘无法输入中文
                        val enabled = SchemaManager.getEnabledSchemas(context)
                        if (enabled.isNotEmpty()) {
                            SchemeStore.setCurrent(context, enabled.first())
                        }
                        SettingsPreferences.setSetupCompleted(context, true)
                        onCompleted()
                    }
                )
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(tr(R.string.s_shezhixiangdao)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(padding)
            // 双栏分档按当前窗口实际宽度（BoxWithConstraints）：手机横屏（600dp+）
            // 也得双栏（左导航 + 右内容），竖屏矮视口不再被横向指示器+居中内容挤压；
            // 平板任何方向与此前一致。isTablet() 是设备分档（sw600dp，不随方向变），
            // 无法覆盖手机横屏，故不再用。
            BoxWithConstraints(modifier = contentModifier) {
                if (maxWidth >= TwoPaneMinWidth) {
                    TwoPaneWizardLayout(
                        currentStep = currentStep,
                        stepContent = stepContent
                    )
                } else {
                    SinglePaneWizardLayout(
                        currentStep = currentStep,
                        stepContent = stepContent
                    )
                }
            }
        }
    }
}

/** 窄容器单栏：顶部横向步骤指示器 + 可滚动的步骤内容。 */
@Composable
private fun SinglePaneWizardLayout(
    currentStep: SetupStep,
    stepContent: @Composable (SetupStep) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalStepIndicator(
            currentStep = currentStep,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        )
        WizardStepHost(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            currentStep = currentStep,
            stepContent = stepContent
        )
    }
}

/** 宽容器双栏：左侧竖向步骤导航 + 右侧可滚动的步骤内容。 */
@Composable
private fun TwoPaneWizardLayout(
    currentStep: SetupStep,
    stepContent: @Composable (SetupStep) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 左栏在横屏矮视口下放不下全部步骤（标题区+3 步+连接线 ≈ 340dp），
        // 与右侧内容一样允许滚动，避免被裁切
        VerticalStepNavigation(
            currentStep = currentStep,
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        WizardStepHost(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
            currentStep = currentStep,
            stepContent = stepContent
        )
    }
}

/** 步骤内容的淡入淡出切换宿主。 */
@Composable
private fun WizardStepHost(
    modifier: Modifier,
    currentStep: SetupStep,
    stepContent: @Composable (SetupStep) -> Unit
) {
    AnimatedContent(
        targetState = currentStep,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "step_content",
        modifier = modifier
    ) { step ->
        StepPane { stepContent(step) }
    }
}

/**
 * 步骤正文容器：内容限宽居中；空间不足时可滚动。
 *
 * 用 [BoxWithConstraints] 拿到视口高度，让内容至少铺满视口以保持垂直居中，
 * 同时允许内容超出视口时滚动（横屏/小高度不再被裁切）。
 */
@Composable
private fun StepPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

/** 手机端横向步骤指示器。 */
@Composable
private fun HorizontalStepIndicator(
    currentStep: SetupStep,
    modifier: Modifier = Modifier
) {
    val steps = SetupStep.entries
    val currentIndex = currentStep.stepIndex

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val isCompleted = index < currentIndex
            val isCurrent = index == currentIndex

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StepBadge(
                    number = index + 1,
                    isCompleted = isCompleted,
                    isCurrent = isCurrent
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = step.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (index < steps.size - 1) {
                HorizontalDivider(
                    modifier = Modifier
                        .width(48.dp)
                        .padding(bottom = 20.dp),
                    color = if (index < currentIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

/** 平板端竖向步骤导航。 */
@Composable
private fun VerticalStepNavigation(
    currentStep: SetupStep,
    modifier: Modifier = Modifier
) {
    val steps = SetupStep.entries
    val currentIndex = currentStep.stepIndex

    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 32.dp)) {
        Text(
            text = tr(R.string.s_ximashurufa),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tr(R.string.s_wanchengyixiabuzhoujikekaishis_a88af),
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryColor()
        )
        Spacer(Modifier.height(28.dp))

        steps.forEachIndexed { index, step ->
            val isCompleted = index < currentIndex
            val isCurrent = index == currentIndex

            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(
                    number = index + 1,
                    isCompleted = isCompleted,
                    isCurrent = isCurrent,
                    size = 40.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.navTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = step.navDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                }
            }

            if (index < steps.size - 1) {
                // 竖向连接线，对齐到徽标圆心（徽标 40dp，线宽 2dp）
                Box(
                    modifier = Modifier
                        .padding(start = 19.dp, top = 4.dp, bottom = 4.dp)
                        .width(2.dp)
                        .height(32.dp)
                        .background(
                            if (index < currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
    }
}

/** 步骤序号徽标。 */
@Composable
private fun StepBadge(
    number: Int,
    isCompleted: Boolean,
    isCurrent: Boolean,
    size: Dp = 36.dp
) {
    Surface(
        shape = CircleShape,
        color = when {
            isCompleted -> MaterialTheme.colorScheme.primary
            isCurrent -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$number",
                color = when {
                    isCompleted -> MaterialTheme.colorScheme.onPrimary
                    isCurrent -> MaterialTheme.colorScheme.onSecondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EnableImeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    val scope = rememberCoroutineScope()

    Text(
        text = tr(R.string.s_ximashurufa),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = tr(R.string.s_jiyu_rime_yinqingde_android_sh_4b613),
        style = MaterialTheme.typography.bodyMedium,
        color = settingsSecondaryColor()
    )
    Spacer(Modifier.height(48.dp))

    Text(
        text = SetupStep.EnableIme.stepTitle,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = tr(R.string.s_qingzaixitongshezhizhongqiyong_f6c5a),
        style = MaterialTheme.typography.bodyMedium,
        color = settingsSecondaryColor(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))

    Button(
        onClick = {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        },
        modifier = Modifier.wizardButton()
    ) {
        Text(tr(R.string.s_quxitongshezhiqiyong))
    }

    if (isEnabled) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = tr(R.string.s_ximashurufayiqiyong),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.wizardButton()
        ) {
            Text(tr(R.string.s_xiayibu))
        }
    } else {
        Spacer(Modifier.height(12.dp))
        Text(
            text = tr(R.string.s_qingxianzaixitongshezhizhongqi_97b2c),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                scope.launch { isEnabled = checkImeEnabled(context) }
            },
            modifier = Modifier.wizardButton()
        ) {
            Text(tr(R.string.s_jianchazhuangtai))
        }
    }

    LaunchedEffect(Unit) {
        while (!isEnabled) {
            delay(2000)
            if (checkImeEnabled(context)) {
                isEnabled = true
                break
            }
        }
    }
}

private fun checkImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

@Composable
private fun SelectSchemasStep(
    enabledSchemas: List<String>,
    deployReminder: String? = null,
    onNavigateToSchemaSettings: () -> Unit,
    onNext: () -> Unit
) {
    Text(
        text = SetupStep.SelectSchemas.stepTitle,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = tr(R.string.s_dianjixiafanganniuqianwangshez_77d28),
        style = MaterialTheme.typography.bodyMedium,
        color = settingsSecondaryColor(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = tr(R.string.s_qingzhishaoxuanzeyigefanganhou_e4dfa),
        style = MaterialTheme.typography.bodySmall,
        color = settingsSecondaryColor(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))

    Button(
        onClick = { onNavigateToSchemaSettings() },
        modifier = Modifier.wizardButton()
    ) {
        Text(tr(R.string.s_qushezhixuanzefangan))
    }

    if (enabledSchemas.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = tr(R.string.s_yixuanze_gefangan, enabledSchemas.size),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        if (deployReminder != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = deployReminder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.wizardButton()
        ) {
            Text(tr(R.string.s_xiayibu))
        }
    }
}

private suspend fun doCompile(
    context: Context,
    enabledSchemas: List<String>,
    onProgress: (String) -> Unit,
    onDone: () -> Unit,
    onError: () -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            onProgress(tr(R.string.s_zhengzaichushihuashurufayinqing))

            // 1. 初始化 Rime 引擎
            val (userDataDir, sharedDataDir) =
                RimeConfigHelper.initializeRimeDataAsync(context)
            val engine = RimeEngine.getInstance()
            engine.initialize(userDataDir, sharedDataDir)

            // 2. 部署 = 重启维护（销毁/重建会话）+ 产物校验（一步完成）
            onProgress(tr(R.string.s_zhengzaibianyiciku))
            RimeConfigHelper.ensureDeployment(context, fullCheck = true)

            onDone()
        } catch (e: Exception) {
            Log.e("SetupWizard", "Compile failed", e)
            onProgress(tr(R.string.s_cuowu, e.message))
            onError()
        }
    }
}

@Composable
private fun SwitchToImeStep(onCompleted: () -> Unit) {
    val context = LocalContext.current

    Text(
        text = SetupStep.SwitchToIme.stepTitle,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = tr(R.string.s_yiqiezhunbeijiuxu_qingqiehuand_3e15e),
        style = MaterialTheme.typography.bodyMedium,
        color = settingsSecondaryColor(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))

    Button(
        onClick = {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        },
        modifier = Modifier.wizardButton()
    ) {
        Text(tr(R.string.s_danchushurufaxuanzeqi))
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onCompleted,
        modifier = Modifier.wizardButton()
    ) {
        Text(tr(R.string.s_wanchengshezhi))
    }
}
