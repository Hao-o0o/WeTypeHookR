package com.haostoo.wetypehookr

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal const val MIN_SEGMENTS = 2
internal const val MAX_SEGMENTS = 8
internal const val MAX_TIMING_MS = 500
internal const val MAX_AMPLITUDE = 255

/**
 * 单个方向的波形编辑草稿。
 *
 * 采用「完整缓冲 + 可见数量」模型：缩小可见数量时保留被隐藏的波段值，
 * 再次增大时恢复；超出缓冲长度时按当前曲线插值生成非零初值。
 */
class WaveDraft(timings0: List<Int>, amps0: List<Int>) {
    val timings = mutableStateListOf<Int>().apply { addAll(timings0) }
    val amps = mutableStateListOf<Int>().apply { addAll(amps0) }
    var count by mutableStateOf(timings0.size)
        private set

    fun changeCount(newCount: Int) {
        if (newCount < MIN_SEGMENTS || newCount > MAX_SEGMENTS) return
        if (newCount > timings.size) extendBuffer(newCount)
        count = newCount
    }

    fun setTiming(index: Int, value: Int) {
        if (index <= 0) return // 首段时长固定为 0，不可编辑
        if (index in timings.indices) timings[index] = value.coerceIn(0, MAX_TIMING_MS)
    }

    fun setAmplitude(index: Int, value: Int) {
        if (index in amps.indices) amps[index] = value.coerceIn(0, MAX_AMPLITUDE)
    }

    private fun extendBuffer(target: Int) {
        val n = timings.size
        if (n < 2) {
            while (timings.size < target) {
                timings.add(0)
                amps.add(0)
            }
            return
        }
        val xs = FloatArray(n) { it.toFloat() / (n - 1) }
        val yT = FloatArray(n) { timings[it].toFloat() }
        val yA = FloatArray(n) { amps[it].toFloat() }
        for (i in n until target) {
            val t = i.toFloat() / (target - 1)
            timings.add(linearSample(xs, yT, t).roundToInt().coerceIn(0, MAX_TIMING_MS))
            amps.add(linearSample(xs, yA, t).roundToInt().coerceIn(0, MAX_AMPLITUDE))
        }
        timings[0] = 0
    }

    /** 合规性校验（依据 VibrationEffect.createWaveform）：返回错误描述，合法返回 null。 */
    fun validate(): String? {
        if (count !in MIN_SEGMENTS..MAX_SEGMENTS) return "有效波段数需在 $MIN_SEGMENTS~$MAX_SEGMENTS 之间"
        if (timings.size < count || amps.size < count) return "波段数据不完整"
        if (timings[0] != 0) return "首段时长必须为 0"
        for (i in 0 until count) {
            if (timings[i] < 0) return "波段 ${i + 1} 时长不能为负"
            if (timings[i] > MAX_TIMING_MS) return "波段 ${i + 1} 时长不能超过 $MAX_TIMING_MS ms"
            if (amps[i] !in 0..MAX_AMPLITUDE) return "波段 ${i + 1} 强度需在 0~$MAX_AMPLITUDE 之间"
        }
        if (timings.take(count).drop(1).sum() <= 0) return "至少需要一段大于 0 ms 的波段"
        return null
    }

    fun build(): SettingsModel.WaveformHaptic? {
        if (validate() != null) return null
        val t = List(count) { index -> if (index == 0) 0L else timings[index].toLong() }
        val a = List(count) { amps[it] }
        return SettingsModel.WaveformHaptic(t, a)
    }

    fun timingAt(index: Int): Int = timings.getOrNull(index) ?: 0
    fun amplitudeAt(index: Int): Int = amps.getOrNull(index) ?: 0
}

private fun linearSample(xs: FloatArray, ys: FloatArray, x: Float): Float {
    if (xs.size == 1) return ys[0]
    if (x <= xs[0]) return ys[0]
    if (x >= xs.last()) return ys.last()
    for (k in 0 until xs.size - 1) {
        if (x <= xs[k + 1]) {
            val span = xs[k + 1] - xs[k]
            val f = if (span <= 0f) 0f else (x - xs[k]) / span
            return ys[k] + f * (ys[k + 1] - ys[k])
        }
    }
    return ys.last()
}

fun isEditableWaveform(w: SettingsModel.WaveformHaptic?): Boolean {
    if (w == null) return false
    val n = w.timings.size
    return SettingsModel.isValidWaveform(w)
}

/**
 * 单方向（按下或抬起）波形编辑内容，直接嵌入宿主设置的 Miuix Scaffold，
 * 确保二级 OverlayBottomSheet 使用同一 popup host。
 */
@Composable
fun WaveformEditorContent(
    isDown: Boolean,
    draft: WaveDraft,
    warning: String?,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    var sheetVisible by remember { mutableStateOf(false) }

    val error = draft.validate()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        warning?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = it,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        WaveformCard(
            isDown = isDown,
            draft = draft,
            selectedIndex = selectedIndex,
            onSelect = {
                selectedIndex = it
                sheetVisible = true
            },
        )

        if (error != null) {
            Text(
                text = error,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "保存",
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = error == null,
            )
        }
    }

    val safeIndex = selectedIndex.coerceIn(0, (draft.count - 1).coerceAtLeast(0))
    NodeEditSheet(
        show = sheetVisible,
        directionTitle = if (isDown) "按下波形" else "抬起波形",
        index = safeIndex,
        timing = draft.timingAt(safeIndex),
        amplitude = draft.amplitudeAt(safeIndex),
        timingEnabled = safeIndex != 0,
        onTimingChange = { draft.setTiming(safeIndex, it) },
        onAmplitudeChange = { draft.setAmplitude(safeIndex, it) },
        onDismiss = { sheetVisible = false },
    )
}

@Composable
private fun WaveformCard(
    isDown: Boolean,
    draft: WaveDraft,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val title = if (isDown) "按下波形" else "抬起波形"
    val description = if (isDown) {
        "手指按下时的自定义波形；曲线为预览，实际按离散数值执行。"
    } else {
        "手指抬起时的自定义波形；曲线为预览，实际按离散数值执行。"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(text = description, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
            Spacer(Modifier.width(8.dp))

            ControlledNumberField(
                value = draft.count,
                onValueChange = { draft.changeCount(it) },
                label = "有效波段数（$MIN_SEGMENTS~$MAX_SEGMENTS）",
                range = MIN_SEGMENTS..MAX_SEGMENTS,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            Spacer(Modifier.width(8.dp))
            WaveformCurve(
                timings = draft.timings,
                amplitudes = draft.amps,
                count = draft.count,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "强度 0~255 · 时间累计 ${sumTimings(draft)} ms · 选中：波段 ${selectedIndex + 1}（时长 ${draft.timingAt(selectedIndex)}ms，强度 ${draft.amplitudeAt(selectedIndex)}）",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            for (i in 0 until draft.count) {
                key(i) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "波段 ${i + 1}",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        if (i == 0) {
                            // timings[0] 依 Android 文档必须为 0：灰色只读，不可编辑
                            Text(
                                text = "0（固定）",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        } else {
                            ControlledNumberField(
                                value = draft.timingAt(i),
                                onValueChange = { draft.setTiming(i, it) },
                                label = "时长(ms)",
                                range = 0..MAX_TIMING_MS,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ControlledNumberField(
                            value = draft.amplitudeAt(i),
                            onValueChange = { draft.setAmplitude(i, it) },
                            label = "强度(0-255)",
                            range = 0..MAX_AMPLITUDE,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeEditSheet(
    show: Boolean,
    directionTitle: String,
    index: Int,
    timing: Int,
    amplitude: Int,
    timingEnabled: Boolean,
    onTimingChange: (Int) -> Unit,
    onAmplitudeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "$directionTitle · 波段 ${index + 1}",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (timingEnabled) {
                ControlledNumberField(
                    value = timing,
                    onValueChange = onTimingChange,
                    label = "时长(ms，0~$MAX_TIMING_MS)",
                    range = 0..MAX_TIMING_MS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            } else {
                Text(
                    text = "时长：0 ms（首段固定，不可编辑）",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            ControlledNumberField(
                value = amplitude,
                onValueChange = onAmplitudeChange,
                label = "强度(0~$MAX_AMPLITUDE)",
                range = 0..MAX_AMPLITUDE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            )
            Text(
                text = "调整会立即同步到曲线与下方输入行。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 受控数值输入：允许空/中间态，合法整数即回写，失焦非法则回退。 */
@Composable
private fun ControlledNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    range: IntRange,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state = remember { TextFieldState(value.toString()) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var lastEmitted by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    var wasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != lastEmitted) {
            state.edit { replace(0, length, value.toString()) }
            lastEmitted = value
            error = null
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            val parsed = text.toIntOrNull()
            when {
                parsed == null -> error = if (text.isEmpty()) null else "请输入整数"
                parsed !in range -> error = "范围 ${range.first}~${range.last}"
                else -> {
                    error = null
                    if (parsed != lastEmitted) {
                        lastEmitted = parsed
                        onValueChange(parsed)
                    }
                }
            }
        }
    }

    LaunchedEffect(focused) {
        if (focused) {
            wasFocused = true
            return@LaunchedEffect
        }
        if (wasFocused) {
            wasFocused = false
            val parsed = state.text.toString().toIntOrNull()
            if (parsed == null || parsed !in range) {
                state.edit { replace(0, length, value.toString()) }
                error = null
            }
        }
    }

    Column(modifier = modifier) {
        TextField(
            state = state,
            label = label,
            enabled = enabled,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        error?.let {
            Text(text = it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        }
    }
}

private fun sumTimings(draft: WaveDraft): Int {
    var sum = 0
    for (i in 0 until draft.count) sum += draft.timingAt(i)
    return sum
}
