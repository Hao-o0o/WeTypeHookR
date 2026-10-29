package com.haostoo.wetypehookr

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 页面内的一级 / 二级页；全部在同一个 Scaffold 内切换。 */
private enum class Page { Main, Waveform, Module, Debug, Help, About }

/** 页面统一的水平内边距与卡片间距。 */
private val PageHorizontal = 12.dp
private val CardGap = 12.dp

/**
 * 设置页根组件。
 *
 * 运行在宿主进程内：`packageName` 天然是宿主，配置读写直接落宿主目录。
 * 顶栏标题区可点，切换查看另一个宿主的配置（只读，不跨宿主写入）。
 */
@Composable
fun SettingsApp() {
    val context = LocalContext.current
    val primaryHost = context.packageName
    val primary by SettingsStore.state.collectAsState()

    var targetHost by remember { mutableStateOf(primaryHost) }
    var showHostPicker by remember { mutableStateOf(false) }
    var external by remember { mutableStateOf<SettingsModel.HostState?>(null) }
    var externalChecked by remember { mutableStateOf(false) }

    val readOnly = targetHost != primaryHost

    LaunchedEffect(targetHost) {
        if (targetHost == primaryHost) {
            externalChecked = false
            external = null
            return@LaunchedEffect
        }
        external = withContext(Dispatchers.IO) { SettingsStore.readExternal(targetHost) }
        externalChecked = true
    }

    val state = if (readOnly) external else primary
    val dark = when (state?.ui?.colorMode ?: 2) {
        0 -> false
        1 -> true
        else -> isSystemInDarkTheme()
    }

    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        SettingsScaffold(
            host = targetHost,
            state = state,
            readOnly = readOnly,
            loaded = !readOnly || externalChecked,
            onSwitchHost = { showHostPicker = true },
            onBack = { (context as? Activity)?.finish() },
            hostPicker = {
                HostPickerDialog(
                    show = showHostPicker,
                    current = targetHost,
                    primaryHost = primaryHost,
                    onDismiss = { showHostPicker = false },
                    onSelect = {
                        targetHost = it
                        showHostPicker = false
                    },
                )
            },
        )
    }
}

@Composable
private fun SettingsScaffold(
    host: String,
    state: SettingsModel.HostState?,
    readOnly: Boolean,
    loaded: Boolean,
    onSwitchHost: () -> Unit,
    onBack: () -> Unit,
    hostPicker: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.Main) }
    var editingDown by remember { mutableStateOf<Boolean?>(null) }
    var activeDraft by remember { mutableStateOf<WaveDraft?>(null) }
    var draftWarning by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val draft = activeDraft

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    val failedText = stringResource(R.string.waveform_save_failed)
    val legacyWarn = stringResource(R.string.waveform_legacy_warn)
    val resetDone = stringResource(R.string.reset_done)
    val invalidText = stringResource(R.string.waveform_invalid)
    val downLabel = stringResource(R.string.label_down)
    val upLabel = stringResource(R.string.label_up)

    // 所有写入都走后台线程：AtomicFile + fsync 不能落在主线程。
    fun saveSettings(next: SettingsModel.SettingsState) {
        if (readOnly) return
        SettingsStore.updateAsync(context, host) { it.copy(settings = next) }
    }

    fun saveWaveform(isDown: Boolean, wave: SettingsModel.WaveformHaptic) {
        if (readOnly) return
        val base = state?.waveform ?: SettingsModel.defaultCustomHaptic()
        val merged = if (isDown) base.copy(down = wave) else base.copy(up = wave)
        SettingsStore.updateAsync(context, host) { it.copy(waveform = merged) }
        editingDown = null
        activeDraft = null
        page = Page.Main
        toast(context.getString(R.string.waveform_saved, if (isDown) downLabel else upLabel))
    }

    fun openWaveformEditor(isDown: Boolean) {
        val existing = if (isDown) state?.waveform?.down else state?.waveform?.up
        val editable = SettingsModel.isValidWaveform(existing)
        draftWarning = existing != null && !editable
        val fallback = SettingsModel.defaultCustomHaptic()
        val source = if (editable) existing!! else if (isDown) fallback.down!! else fallback.up!!
        activeDraft = WaveDraft(source.timings.map { it.toInt() }, source.amplitudes)
        editingDown = isDown
        page = Page.Waveform
    }

    val titleOverride = when (page) {
        Page.Main -> null
        Page.Waveform -> stringResource(
            if (editingDown == true) R.string.title_down_waveform else R.string.title_up_waveform,
        )
        Page.Module -> stringResource(R.string.title_module_settings)
        Page.Debug -> stringResource(R.string.title_debug_logs)
        Page.Help -> stringResource(R.string.title_help)
        Page.About -> stringResource(R.string.title_about)
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                subtitle = stringResource(HostCatalog.labelRes(host)),
                onBack = {
                    if (page == Page.Main) {
                        onBack()
                    } else {
                        page = Page.Main
                        editingDown = null
                        activeDraft = null
                    }
                },
                onSwitchHost = onSwitchHost,
                titleOverride = titleOverride,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState != Page.Main) {
                    (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
                }
            },
            label = "settings-page",
        ) { current ->
            val topInset = padding.calculateTopPadding()

            when (current) {
                Page.Main -> MainContent(
                    host = host,
                    state = state,
                    readOnly = readOnly,
                    loaded = loaded,
                    topInset = topInset,
                    onSettingsChange = { saveSettings(it) },
                    onOpenWaveformEditor = { isDown -> openWaveformEditor(isDown) },
                    onReset = { showResetDialog = true },
                    onOpenModuleSettings = { page = Page.Module },
                )

                Page.Waveform -> {
                    if (editingDown != null && draft != null) {
                        val errorText = waveErrorText(draft.validate())
                        WaveformEditorContent(
                            isDown = editingDown == true,
                            draft = draft,
                            warning = if (draftWarning) legacyWarn else null,
                            onCancel = {
                                page = Page.Main
                                editingDown = null
                                activeDraft = null
                            },
                            onSave = {
                                val built = draft.build()
                                if (built == null) {
                                    toast(errorText.ifBlank { invalidText })
                                } else {
                                    saveWaveform(editingDown == true, built)
                                }
                            },
                            modifier = Modifier.padding(top = topInset),
                        )
                    }
                }

                Page.Module -> ModuleSettingsPage(
                    state = state,
                    readOnly = readOnly,
                    topInset = topInset,
                    onOpenDebug = { page = Page.Debug },
                    onOpenHelp = { page = Page.Help },
                    onOpenAbout = { page = Page.About },
                )

                Page.Debug -> DebugPage(
                    state = state,
                    readOnly = readOnly,
                    topInset = topInset,
                    onSettingsChange = { saveSettings(it) },
                )

                Page.Help -> HelpPage(topInset = topInset)
                Page.About -> AboutPage(topInset = topInset)
            }
        }

        hostPicker()

        if (showResetDialog) {
            OverlayDialog(
                title = stringResource(R.string.action_reset_default),
                summary = stringResource(R.string.reset_confirm_message),
                show = true,
                onDismissRequest = { showResetDialog = false },
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { showResetDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.action_confirm),
                        onClick = {
                            showResetDialog = false
                            SettingsStore.updateAsync(context, host) {
                                it.copy(
                                    settings = SettingsModel.defaultSettings(),
                                    waveform = SettingsModel.defaultCustomHaptic(),
                                )
                            }
                            toast(resetDone)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainContent(
    host: String,
    state: SettingsModel.HostState?,
    readOnly: Boolean,
    loaded: Boolean,
    topInset: androidx.compose.ui.unit.Dp,
    onSettingsChange: (SettingsModel.SettingsState) -> Unit,
    onOpenWaveformEditor: (Boolean) -> Unit,
    onReset: () -> Unit,
    onOpenModuleSettings: () -> Unit,
) {
    val context = LocalContext.current
    var hostVersion by remember(host) { mutableStateOf<String?>(null) }

    LaunchedEffect(host) {
        withContext(Dispatchers.IO) { hostVersion = HostInfo.versionText(context, host) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontal),
    ) {
        Spacer(Modifier.height(CardGap))

        if (!loaded) {
            Text(
                text = stringResource(R.string.state_loading),
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 14.sp,
            )
            return@Column
        }

        if (state == null) {
            // 另一个宿主的数据目录不可读：只给提示，不给可编辑控件。
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Text(
                    text = stringResource(R.string.host_no_access_hint),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(CardGap))
            Spacer(Modifier.navigationBarsPadding())
            return@Column
        }

        HostIdentityCard(host = host, hostVersion = hostVersion, readOnly = readOnly)
        Spacer(Modifier.height(CardGap))

        InjectionCard(hookStatus = if (readOnly) null else HookRuntime.status())
        Spacer(Modifier.height(CardGap))

        VibrationCard(
            isDown = true,
            state = state.settings,
            readOnly = readOnly,
            waveform = state.waveform?.down,
            onStateChange = onSettingsChange,
            onOpenWaveformEditor = { onOpenWaveformEditor(true) },
        )
        Spacer(Modifier.height(CardGap))

        VibrationCard(
            isDown = false,
            state = state.settings,
            readOnly = readOnly,
            waveform = state.waveform?.up,
            onStateChange = onSettingsChange,
            onOpenWaveformEditor = { onOpenWaveformEditor(false) },
        )
        Spacer(Modifier.height(CardGap))

        OtherOptionsCard(
            readOnly = readOnly,
            onReset = onReset,
            onReload = {
                HapticEngine.killHost(context, host) { result, detail ->
                    val text = when (result) {
                        HapticEngine.ReloadResult.OK -> context.getString(R.string.reload_ok, host)
                        HapticEngine.ReloadResult.NO_ROOT -> context.getString(R.string.reload_no_root)
                        HapticEngine.ReloadResult.NO_SU -> context.getString(R.string.reload_no_su)
                        HapticEngine.ReloadResult.FAILED ->
                            context.getString(R.string.reload_failed, detail ?: "")
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            },
            onOpenModuleSettings = onOpenModuleSettings,
        )

        Spacer(Modifier.height(CardGap))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun HostIdentityCard(host: String, hostVersion: String?, readOnly: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(HostCatalog.labelRes(host)),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = host,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(
                    R.string.host_version,
                    hostVersion ?: stringResource(R.string.state_unknown),
                ),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (readOnly) {
                Text(
                    text = stringResource(R.string.read_only_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InjectionCard(hookStatus: SettingsModel.HookStatus?) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.title_injection),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val title: String
            val detail: String
            when {
                hookStatus == null -> {
                    title = stringResource(R.string.injection_none)
                    detail = stringResource(R.string.injection_none_hint)
                }
                !hookStatus.hookInstalled -> {
                    title = stringResource(R.string.injection_hook_missing)
                    detail = stringResource(R.string.injection_hook_missing_hint)
                }
                else -> {
                    title = stringResource(R.string.injection_ok)
                    detail = stringResource(
                        R.string.injection_ok_hint,
                        HostInfo.formatTime(hookStatus.configUpdatedAt),
                    )
                }
            }
            Text(text = title, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (hookStatus != null) {
                Text(
                    text = stringResource(
                        R.string.injection_heartbeat,
                        HostInfo.formatTime(hookStatus.heartbeatAt),
                        hookStatus.moduleVersion,
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun VibrationCard(
    isDown: Boolean,
    state: SettingsModel.SettingsState,
    readOnly: Boolean,
    waveform: SettingsModel.WaveformHaptic?,
    onStateChange: (SettingsModel.SettingsState) -> Unit,
    onOpenWaveformEditor: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val modes = stringArrayResource(R.array.vibration_modes).toList()
    val systemHaptics = stringArrayResource(R.array.system_haptics).toList()
    val direction = stringResource(if (isDown) R.string.label_down else R.string.label_up)

    val mode = if (isDown) state.downIndex else state.upIndex
    val sysIndex = if (isDown) state.downSystemIndex else state.upSystemIndex

    // 拖动期间只更新本地值，松手才提交：避免每帧触发整页重组与落盘。
    var duration by remember(isDown) {
        mutableFloatStateOf(if (isDown) state.downDuration else state.upDuration)
    }
    var strength by remember(isDown) {
        mutableFloatStateOf(if (isDown) state.downStrength else state.upStrength)
    }
    LaunchedEffect(isDown, state.downDuration, state.upDuration) {
        duration = if (isDown) state.downDuration else state.upDuration
    }
    LaunchedEffect(isDown, state.downStrength, state.upStrength) {
        strength = if (isDown) state.downStrength else state.upStrength
    }

    fun commitDuration(value: Float) {
        onStateChange(if (isDown) state.copy(downDuration = value) else state.copy(upDuration = value))
    }

    fun commitStrength(value: Float) {
        onStateChange(if (isDown) state.copy(downStrength = value) else state.copy(upStrength = value))
    }

    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        OverlayDropdownPreference(
            title = stringResource(
                if (isDown) R.string.title_down_vibration else R.string.title_up_vibration,
            ),
            items = modes,
            selectedIndex = mode,
            enabled = !readOnly,
            onSelectedIndexChange = {
                onStateChange(if (isDown) state.copy(downIndex = it) else state.copy(upIndex = it))
            },
        )

        AnimatedVisibility(visible = mode == 1) {
            OverlayDropdownPreference(
                title = stringResource(R.string.title_system_haptic_style),
                items = systemHaptics,
                selectedIndex = sysIndex,
                enabled = !readOnly,
                onSelectedIndexChange = { index ->
                    onStateChange(
                        if (isDown) state.copy(downSystemIndex = index) else state.copy(upSystemIndex = index),
                    )
                    view.performHapticFeedback(hapticConstantForIndex(index))
                },
            )
        }

        AnimatedVisibility(visible = mode == 2) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.duration_label, direction, duration.toInt()),
                    fontSize = 14.sp,
                    color = Color.Unspecified,
                )
                Slider(
                    value = duration,
                    enabled = !readOnly,
                    onValueChange = { duration = it },
                    onValueChangeFinished = { commitDuration(duration) },
                    valueRange = 10f..200f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.strength_label, direction, (strength * 100).toInt()),
                    fontSize = 14.sp,
                    color = Color.Unspecified,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = strength,
                    enabled = !readOnly,
                    onValueChange = { strength = it },
                    onValueChangeFinished = { commitStrength(strength) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AnimatedVisibility(visible = mode == 3) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val fallback = SettingsModel.defaultCustomHaptic()
                val wave = waveform ?: if (isDown) fallback.down!! else fallback.up!!
                Text(
                    text = stringResource(R.string.label_curve_preview),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                WaveformCurve(
                    a11y = stringResource(R.string.waveform_curve_a11y),
                    timings = wave.timings.map { it.toInt() },
                    amplitudes = wave.amplitudes,
                    count = wave.timings.size.coerceIn(
                        SettingsModel.MIN_SEGMENTS,
                        SettingsModel.MAX_SEGMENTS,
                    ),
                    selectedIndex = -1,
                    onSelect = { onOpenWaveformEditor() },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.curve_summary, wave.timings.sum().toInt()),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.action_setup_waveform),
                    summary = if (waveform == null) {
                        stringResource(R.string.waveform_not_set)
                    } else {
                        stringResource(R.string.waveform_set, wave.timings.size)
                    },
                    enabled = !readOnly,
                    startAction = {
                        Icon(
                            MiuixIcons.Tune,
                            null,
                            modifier = Modifier.padding(end = 12.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = onOpenWaveformEditor,
                )
            }
        }

        ArrowPreference(
            title = stringResource(R.string.action_test, direction),
            startAction = {
                Icon(
                    MiuixIcons.Play,
                    null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onClick = {
                if (mode != 0) {
                    HapticEngine.triggerHapticPreview(
                        context,
                        view,
                        mode,
                        sysIndex,
                        duration,
                        strength,
                        isDown = isDown,
                        waveform = waveform,
                    )
                }
            },
        )
    }
}

@Composable
private fun OtherOptionsCard(
    readOnly: Boolean,
    onReset: () -> Unit,
    onReload: () -> Unit,
    onOpenModuleSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        ArrowPreference(
            title = stringResource(R.string.action_reset_default),
            enabled = !readOnly,
            startAction = {
                Icon(
                    MiuixIcons.Reset,
                    null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onClick = onReset,
        )
        ArrowPreference(
            title = stringResource(R.string.action_reload_ime),
            summary = stringResource(R.string.reload_summary),
            startAction = {
                Icon(
                    MiuixIcons.Refresh,
                    null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onClick = onReload,
        )
        ArrowPreference(
            title = stringResource(R.string.title_module_settings),
            startAction = {
                Icon(
                    MiuixIcons.Settings,
                    null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onClick = onOpenModuleSettings,
        )
    }
}

/** 宿主选择：列出两个宿主，当前项打钩；切到另一个宿主只读查看。 */
@Composable
private fun HostPickerDialog(
    show: Boolean,
    current: String,
    primaryHost: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (!show) return
    OverlayDialog(
        title = stringResource(R.string.host_picker_title),
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column {
            HostCatalog.ALL.forEach { host ->
                val label = stringResource(HostCatalog.labelRes(host))
                ArrowPreference(
                    title = if (host == current) "✓ $label" else label,
                    summary = if (host == primaryHost) {
                        host
                    } else {
                        stringResource(R.string.read_only_hint)
                    },
                    onClick = { onSelect(host) },
                )
            }
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
