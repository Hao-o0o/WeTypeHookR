package com.haostoo.wetypehookr

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val MODE_NAMES = listOf("无震动", "系统预设风格", "简单自定义", "自定义波形")
private val SYSTEM_HAPTICS = listOf(
    "Virtual Key", "Virtual Key Release",
    "Keyboard Tap", "Keyboard Press", "Keyboard Release",
    "Long Press", "Context Click", "Confirm", "Reject",
    "Clock Tick",
    "Gesture Start", "Gesture End", "Gesture Threshold Activate", "Gesture Threshold Deactivate",
    "Drag Start",
    "Text Handle Move",
    "Segment Tick", "Segment Frequent Tick",
    "Toggle On", "Toggle Off",
)

/**
 * 宿主设置详情：二级页面，带返回顶栏，不含根底栏。
 * 顺序：按下震动（模式 → [系统/滑块] → 自定义波形时显示曲线预览与设置波形 → 测试）→ 抬起震动（同）→ 其他选项。
 */
@Composable
fun HostSettingsScreen(hostPackage: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshots by ConfigRepository.snapshots.collectAsState()
    val snapshot = snapshots[hostPackage]

    var state by remember(hostPackage) { mutableStateOf<SettingsModel.SettingsState?>(null) }
    var waveformConfig by remember(hostPackage) { mutableStateOf<SettingsModel.CustomHapticConfig?>(null) }
    var pendingSave by remember(hostPackage) { mutableStateOf<SettingsModel.SettingsState?>(null) }
    var editingDown by remember { mutableStateOf<Boolean?>(null) }
    var activeDraft by remember { mutableStateOf<WaveDraft?>(null) }
    var waveformWarning by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var hostVersion by remember(hostPackage) { mutableStateOf<String?>(null) }
    var hookStatus by remember(hostPackage) { mutableStateOf<SettingsModel.HookStatus?>(null) }

    LaunchedEffect(hostPackage) {
        withContext(Dispatchers.IO) {
            // 页面运行在宿主进程内：配置直接读宿主目录，状态直接读进程内 HookRuntime。
            ConfigRepository.refresh(context, hostPackage)
            hostVersion = HostInfo.versionText(context, hostPackage)
            hookStatus = ConfigRepository.hookStatus(hostPackage)
        }
    }

    // 配置 revision 变化意味着 Hook 可能重新上报过状态，顺带刷新一次注入卡。
    LaunchedEffect(snapshot?.revision) {
        withContext(Dispatchers.IO) { hookStatus = ConfigRepository.hookStatus(hostPackage) }
    }

    // 快照变化回流到 UI；拖动期间有未落盘的本地状态时不覆盖，避免滑块跳动。
    LaunchedEffect(snapshot) {
        val s = snapshot ?: return@LaunchedEffect
        if (pendingSave == null) {
            state = s.settings
            waveformConfig = s.waveform
        }
    }

    // 拖动/连续点击期间只更新本地状态，停止操作 250ms 后自动保存（避免逐帧 Binder / 文件 IO）。
    LaunchedEffect(pendingSave) {
        val s = pendingSave ?: return@LaunchedEffect
        delay(250)
        withContext(Dispatchers.IO) { ConfigRepository.saveSettings(context, hostPackage, s) }
        pendingSave = null
    }

    // 未等到防抖就离开页面时补一次落盘，避免最后一次改动丢失。
    val latestState by rememberUpdatedState(state)
    DisposableEffect(hostPackage) {
        onDispose {
            if (pendingSave != null) {
                latestState?.let { ConfigRepository.saveSettings(context, hostPackage, it) }
            }
        }
    }

    val syncNotice = when {
        snapshot == null -> null
        snapshot.conflict -> "本地有与远端冲突的修改，已保留本地版本，请确认后再编辑"
        snapshot.state == SyncState.PENDING_SYNC -> "已保存，等待框架同步"
        snapshot.state == SyncState.OFFLINE_CACHE || snapshot.state == SyncState.DEFAULT ->
            "框架未连接，当前为最后一次已知配置；修改将在连接后自动同步"
        else -> null
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun persist(newState: SettingsModel.SettingsState) {
        state = newState
        pendingSave = newState
    }

    fun saveWaveform(isDown: Boolean, wave: SettingsModel.WaveformHaptic) {
        val base = waveformConfig ?: SettingsModel.defaultCustomHaptic()
        val merged = if (isDown) base.copy(down = wave) else base.copy(up = wave)
        if (!ConfigRepository.saveWaveform(context, hostPackage, merged)) {
            toast("波形保存失败")
            return
        }
        waveformConfig = merged
        editingDown = null
        toast("${if (isDown) "按下" else "抬起"}波形已保存")
    }

    fun openWaveformEditor(isDown: Boolean) {
        val existing = if (isDown) waveformConfig?.down else waveformConfig?.up
        val editable = isEditableWaveform(existing)
        waveformWarning = if (existing != null && !editable) {
            "检测到历史波形数据长度或数组不一致，已载入默认波形草稿；保存后将按当前数量规范化。"
        } else {
            null
        }
        val fallback = SettingsModel.defaultCustomHaptic()
        val src = if (editable) existing!! else if (isDown) fallback.down!! else fallback.up!!
        activeDraft = WaveDraft(src.timings.map { it.toInt() }, src.amplitudes.toList())
        editingDown = isDown
    }

    fun saveDraft(isDown: Boolean) {
        val d = activeDraft ?: return
        val built = d.build()
        if (built == null) {
            toast(d.validate() ?: "波形数据不合法")
            return
        }
        saveWaveform(isDown, built)
    }

    fun resetToDefault() {
        ConfigRepository.resetHost(context, hostPackage)
        state = SettingsModel.defaultSettings()
        waveformConfig = SettingsModel.defaultCustomHaptic()
        toast("已恢复默认配置")
    }

    fun reloadHost() {
        HapticEngine.killHost(context, hostPackage) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val dark = SettingsStore.current.ui.colorMode.let {
        if (it == 0) false else if (it == 1) true else isSystemInDarkTheme()
    }
    val scrollBehavior = MiuixScrollBehavior()
    val draft = activeDraft
    val editDown = editingDown

    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = if (editDown != null) {
                        if (editDown) "按下波形" else "抬起波形"
                    } else {
                        stringResource(HostCatalog.labelRes(hostPackage))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (editDown != null) {
                                editingDown = null
                                activeDraft = null
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(MiuixIcons.Back, if (editDown != null) "取消" else "返回")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            val s = state
            val isEditing = editDown != null && draft != null
            AnimatedContent(
                targetState = isEditing,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 5 } + fadeOut(tween(220)))
                    } else {
                        (slideInHorizontally(tween(300)) { -it / 5 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(220)))
                    }
                },
                label = "host-detail-screen",
            ) { editing ->
                if (editing && editDown != null && draft != null) {
                    WaveformEditorContent(
                        isDown = editDown,
                        draft = draft,
                        warning = waveformWarning,
                        onCancel = { editingDown = null },
                        onSave = { saveDraft(editDown) },
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    )
                } else if (s == null) {
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "加载中…",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    HostDetailContent(
                        state = s,
                        onStateChange = ::persist,
                        waveformConfig = waveformConfig,
                        onOpenWaveformEditor = { isDown -> openWaveformEditor(isDown) },
                        onReset = { showResetDialog = true },
                        onReload = { reloadHost() },
                        hostPackage = hostPackage,
                        hostVersion = hostVersion,
                        hookStatus = hookStatus,
                        syncNotice = syncNotice,
                        scrollBehavior = scrollBehavior,
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    )
                }
            }
        }

        if (showResetDialog) {
            OverlayDialog(
                title = "恢复默认配置",
                summary = "将当前宿主的震动设置与自定义波形恢复为默认值？",
                show = true,
                onDismissRequest = { showResetDialog = false },
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(text = "取消", onClick = { showResetDialog = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确认",
                        onClick = {
                            showResetDialog = false
                            resetToDefault()
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
private fun HostDetailContent(
    state: SettingsModel.SettingsState,
    onStateChange: (SettingsModel.SettingsState) -> Unit,
    waveformConfig: SettingsModel.CustomHapticConfig?,
    onOpenWaveformEditor: (Boolean) -> Unit,
    onReset: () -> Unit,
    onReload: () -> Unit,
    hostPackage: String,
    hostVersion: String?,
    hookStatus: SettingsModel.HookStatus?,
    syncNotice: String?,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        HostIdentityCard(hostPackage = hostPackage, hostVersion = hostVersion)
        Spacer(Modifier.height(12.dp))
        InjectionCard(hookStatus = hookStatus)
        Spacer(Modifier.height(12.dp))
        if (syncNotice != null) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                Text(
                    text = syncNotice,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        VibrationCard(
            isDown = true,
            state = state,
            onStateChange = onStateChange,
            waveform = waveformConfig?.down,
            onOpenWaveformEditor = { onOpenWaveformEditor(true) },
        )
        Spacer(Modifier.height(12.dp))
        VibrationCard(
            isDown = false,
            state = state,
            onStateChange = onStateChange,
            waveform = waveformConfig?.up,
            onOpenWaveformEditor = { onOpenWaveformEditor(false) },
        )
        Spacer(Modifier.height(12.dp))
        OtherOptionsCard(onReset = onReset, onReload = onReload)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HostIdentityCard(hostPackage: String, hostVersion: String?) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(HostCatalog.labelRes(hostPackage)),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = hostPackage,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "版本 ${hostVersion ?: "未知"}",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun InjectionCard(hookStatus: SettingsModel.HookStatus?) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "注入状态", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val (title, detail) = when {
                hookStatus == null -> "未观测到注入" to "打开输入法并切换一次键盘后再回来查看"
                !hookStatus.hookInstalled -> "模块已加载，Hook 未安装" to "输入法版本可能已变更，可通过日志反馈"
                else -> "Hook 已注入" to "当前生效配置 revision ${hookStatus.configRevision}"
            }
            Text(
                text = title,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (hookStatus != null) {
                Text(
                    text = "最近心跳 ${HostInfo.formatTime(hookStatus.heartbeatAt)} · 模块 ${hookStatus.moduleVersion}",
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
    onStateChange: (SettingsModel.SettingsState) -> Unit,
    waveform: SettingsModel.WaveformHaptic?,
    onOpenWaveformEditor: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val title = if (isDown) "按下震动" else "抬起震动"
    val mode = if (isDown) state.downIndex else state.upIndex
    val sysIndex = if (isDown) state.downSystemIndex else state.upSystemIndex
    val duration = if (isDown) state.downDuration else state.upDuration
    val strength = if (isDown) state.downStrength else state.upStrength

    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            OverlayDropdownPreference(
                title = title,
                items = MODE_NAMES,
                selectedIndex = mode,
                onSelectedIndexChange = {
                    onStateChange(if (isDown) state.copy(downIndex = it) else state.copy(upIndex = it))
                },
            )

            AnimatedVisibility(visible = mode == 1) {
                OverlayDropdownPreference(
                    title = "系统震动风格",
                    items = SYSTEM_HAPTICS,
                    selectedIndex = sysIndex,
                    onSelectedIndexChange = { index ->
                        onStateChange(if (isDown) state.copy(downSystemIndex = index) else state.copy(upSystemIndex = index))
                        view.performHapticFeedback(hapticConstantForIndex(index))
                    },
                )
            }
            AnimatedVisibility(visible = mode == 2) {
                Column {
                    Text(
                        "${if (isDown) "按下" else "抬起"}震动时长：${duration.toInt()} ms",
                        fontSize = 14.sp,
                        color = Color.Unspecified,
                        modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    )
                    Slider(
                        value = duration,
                        onValueChange = {
                            onStateChange(if (isDown) state.copy(downDuration = it) else state.copy(upDuration = it))
                        },
                        valueRange = 10f..200f,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        "${if (isDown) "按下" else "抬起"}震动强度：${(strength * 100).toInt()} %",
                        fontSize = 14.sp,
                        color = Color.Unspecified,
                        modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    )
                    Slider(
                        value = strength,
                        onValueChange = {
                            onStateChange(if (isDown) state.copy(downStrength = it) else state.copy(upStrength = it))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // 仅自定义波形模式显示曲线预览与设置波形入口
            AnimatedVisibility(visible = mode == 3) {
                Column {
                    val w = waveform ?: if (isDown) {
                        SettingsModel.defaultCustomHaptic().down!!
                    } else {
                        SettingsModel.defaultCustomHaptic().up!!
                    }
                    Text(
                        "曲线预览",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
                    )
                    WaveformCurve(
                        timings = w.timings.map { it.toInt() },
                        amplitudes = w.amplitudes.toList(),
                        count = w.timings.size.coerceIn(2, 8),
                        selectedIndex = -1,
                        onSelect = { onOpenWaveformEditor() },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        "强度 0~255 · 时间累计 ${w.timings.sum()} ms（曲线为预览，实际按离散数值执行）",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
                    )
                    ArrowPreference(
                        title = "设置波形",
                        summary = if (waveform == null) "未配置 · 将载入默认四段波形" else "已配置 · ${w.timings.size} 段",
                        startAction = {
                            Icon(MiuixIcons.Tune, null, modifier = Modifier.padding(end = 12.dp), tint = MiuixTheme.colorScheme.primary)
                        },
                        onClick = onOpenWaveformEditor,
                    )
                }
            }

            ArrowPreference(
                title = "测试$title",
                startAction = {
                    Icon(MiuixIcons.Play, null, modifier = Modifier.padding(end = 12.dp), tint = MiuixTheme.colorScheme.primary)
                },
                onClick = {
                    if (mode != 0) {
                        HapticEngine.triggerHapticPreview(
                            context, view,
                            mode, sysIndex, duration, strength,
                            isDown = isDown,
                            waveform = waveform,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun OtherOptionsCard(onReset: () -> Unit, onReload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        ArrowPreference(
            title = "恢复默认配置",
            summary = "恢复当前宿主的默认震动与波形",
            startAction = {
                Icon(MiuixIcons.Reset, null, modifier = Modifier.padding(end = 12.dp), tint = MiuixTheme.colorScheme.primary)
            },
            onClick = onReset,
        )
        ArrowPreference(
            title = "重载输入法",
            summary = "通过 Root 快速重启目标输入法进程",
            startAction = {
                Icon(MiuixIcons.Refresh, null, modifier = Modifier.padding(end = 12.dp), tint = MiuixTheme.colorScheme.primary)
            },
            onClick = onReload,
        )
    }
}
