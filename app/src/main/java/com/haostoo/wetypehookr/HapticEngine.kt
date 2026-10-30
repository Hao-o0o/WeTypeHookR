package com.haostoo.wetypehookr

import android.content.Context
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 不依赖任何 Hook 框架类型的共享触觉引擎，供 Hook 侧与设置页共用。
 *
 * Hook 回调只读 [cachedSettings] / [cachedCustomHaptic] 这份内存快照，
 * 缓存由 [SettingsStore] 的文件监听刷新，回调本身不做任何 IO。
 */
object HapticEngine {

    /** 宿主侧当前生效配置（由 [SettingsStore] 读取并热更新）。 */
    @Volatile
    var cachedSettings: SettingsModel.SettingsState =
        SettingsModel.SettingsState()

    /** 宿主侧当前生效波形（由 [SettingsStore] 读取并热更新）。 */
    @Volatile
    var cachedCustomHaptic: SettingsModel.CustomHapticConfig? = null

    /**
     * 触摸事件分发（Hook 侧唯一入口）。
     *
     * 按下 / 多指按下触发按下震动，抬起 / 多指抬起触发抬起震动；
     * 其它事件直接忽略，不介入宿主原有点击逻辑。
     */
    fun dispatch(view: View, event: android.view.MotionEvent) {
        val settings = cachedSettings
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            -> triggerHapticPreview(
                context = view.context,
                view = view,
                modeIndex = settings.downIndex,
                systemIndex = settings.downSystemIndex,
                duration = settings.downDuration,
                strength = settings.downStrength,
                isDown = true,
            )

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP,
            -> triggerHapticPreview(
                context = view.context,
                view = view,
                modeIndex = settings.upIndex,
                systemIndex = settings.upSystemIndex,
                duration = settings.upDuration,
                strength = settings.upStrength,
                isDown = false,
            )
        }
    }

    /**
     * 震动预览（设置页测试按钮 / 宿主按键触发共用）。
     *
     * @param modeIndex 0=无震动 1=系统预设 2=简单自定义 3=自定义波形
     * @param waveform  自定义波形（App 侧预览传入；宿主侧缺省回退到 [cachedCustomHaptic]）
     */
    fun triggerHapticPreview(
        context: Context,
        view: View,
        modeIndex: Int,
        systemIndex: Int,
        duration: Float,
        strength: Float,
        isDown: Boolean,
        waveform: SettingsModel.WaveformHaptic? = null,
    ) {
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        if (DebugLog.isEnabled()) {
            DebugLog.log(
                context, "vibrate",
                "${if (isDown) "down" else "up"} mode=$modeIndex sys=$systemIndex dur=${duration}ms str=$strength",
            )
        }
        when (modeIndex) {
            1 -> VibratorProbe.trace {
                view.performHapticFeedback(hapticConstantForIndex(systemIndex))
            }
            2 -> {
                if (vibrator?.hasVibrator() != true) return
                val clicks = ((duration + PRIMITIVE_MS - 1) / PRIMITIVE_MS).toInt().coerceIn(1, MAX_PRIMITIVES)
                emit(
                    context = context,
                    vibrator = vibrator,
                    primitive = clickEffect(vibrator, clicks, strength),
                    fallback = {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            android.os.VibrationEffect.createOneShot(
                                duration.toLong(),
                                (strength * 255).toInt().coerceIn(1, 255),
                            )
                        } else {
                            null
                        }
                    },
                    label = "custom dur=${duration.toInt()} str$strength clicks=$clicks",
                )
            }
            3 -> {
                if (vibrator?.hasVibrator() != true) return
                val w = waveform
                    ?: if (isDown) cachedCustomHaptic?.down else cachedCustomHaptic?.up
                if (w == null) return
                emit(
                    context = context,
                    vibrator = vibrator,
                    primitive = waveformClickEffect(vibrator, w),
                    fallback = {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            android.os.VibrationEffect.createWaveform(
                                w.timings.toLongArray(),
                                w.amplitudes.toIntArray(),
                                -1,
                            )
                        } else {
                            null
                        }
                    },
                    label = "wave timings=${w.timings} amps=${w.amplitudes}",
                )
            }
        }
    }

    /**
     * 发出一次震动。
     *
     * 为什么优先用**原始触感（primitive）组合**而不是 `createOneShot/createWaveform`：
     * 这两条老 API 在部分机型（尤其窄频 LRA）上会被 HAL 以降幅很低的「Step」渲染，
     * 而系统自带的按键反馈用的是 prebaked/primitive，响度和手感都由厂商调过。
     * 把自定义时长/强度映射成一串 CLICK，等于借用系统自己那条「手感正常」的通道。
     *
     * - 设备不支持 primitive 时退回老 API（[fallback]，行为与旧版一致）；
     * - 一律带 `USAGE_TOUCH`，与系统按键反馈同一用途，避免落到会被降权的 UNKNOWN 档；
     * - 每次只写前 [DIAG_LIMIT] 条诊断，避免刷屏。
     */
    private fun emit(
        context: Context,
        vibrator: android.os.Vibrator,
        primitive: android.os.VibrationEffect?,
        fallback: () -> android.os.VibrationEffect?,
        label: String,
    ) {
        val effect = primitive ?: fallback()
        if (effect == null) return
        var failure: Throwable? = null
        try {
            VibratorProbe.trace {
                vibrator.vibrate(
                    effect,
                    android.os.VibrationAttributes.createForUsage(
                        android.os.VibrationAttributes.USAGE_TOUCH,
                    ),
                )
            }
        } catch (t: Throwable) {
            failure = t
        }
        val index = emitCount.incrementAndGet()
        if (index <= DIAG_LIMIT) {
            val kind = if (primitive != null) "primitive" else "legacy"
            HostDiag.log(
                context,
                "VIB",
                "emit#$index $kind $label ${if (failure == null) "ok" else "ERR=${failure.javaClass.name}: ${failure.message}"}",
            )
        }
    }

    /** 把一段时长映射成 n 个 CLICK，连续播放即得到一段稳定的持续震动。 */
    private fun clickEffect(
        vibrator: android.os.Vibrator,
        count: Int,
        scale: Float,
    ): android.os.VibrationEffect? = runCatching {
        if (!supportsPrimitives(vibrator)) return null
        val composition = android.os.VibrationEffect.startComposition()
        repeat(count.coerceIn(1, MAX_PRIMITIVES)) {
            composition.addPrimitive(
                android.os.VibrationEffect.Composition.PRIMITIVE_CLICK,
                scale.coerceIn(MIN_SCALE, 1f),
            )
        }
        composition.compose()
    }.getOrNull()

    /** 自定义波形：每段的时长决定 CLICK 个数，强度决定 CLICK 的 scale。 */
    private fun waveformClickEffect(
        vibrator: android.os.Vibrator,
        wave: SettingsModel.WaveformHaptic,
    ): android.os.VibrationEffect? = runCatching {
        if (!supportsPrimitives(vibrator)) return null
        val composition = android.os.VibrationEffect.startComposition()
        var added = 0
        wave.timings.forEachIndexed { index, timing ->
            val amplitude = wave.amplitudes.getOrElse(index) { 0 }
            // 首段固定为 0，强度 0 表示静音，两者都跳过（CLICK 无法表达「静音」）。
            if (index > 0 && timing > 0 && amplitude > 0 && added < MAX_PRIMITIVES) {
                val clicks = ((timing + PRIMITIVE_MS - 1) / PRIMITIVE_MS).toInt().coerceAtLeast(1)
                repeat(clicks.coerceAtMost(MAX_PRIMITIVES - added)) {
                    composition.addPrimitive(
                        android.os.VibrationEffect.Composition.PRIMITIVE_CLICK,
                        (amplitude / 255f).coerceIn(MIN_SCALE, 1f),
                    )
                    added++
                }
            }
        }
        if (added == 0) return null
        composition.compose()
    }.getOrNull()

    /** 设备是否支持 primitive 触感；只探测一次。 */
    @Volatile
    private var primitiveSupport: Boolean? = null

    private fun supportsPrimitives(vibrator: android.os.Vibrator): Boolean {
        primitiveSupport?.let { return it }
        val supported: Boolean = try {
            vibrator.areAllPrimitivesSupported(
                android.os.VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
        } catch (t: Throwable) {
            false
        }
        primitiveSupport = supported
        return supported
    }

    private const val PRIMITIVE_MS = 20L
    private const val MAX_PRIMITIVES = 120
    private const val MIN_SCALE = 0.05f
    private const val DIAG_LIMIT = 24

    private val emitCount = java.util.concurrent.atomic.AtomicInteger()

    /** 重载输入法的结果；文案在 UI 层由资源解析。 */
    enum class ReloadResult { OK, NO_ROOT, NO_SU, FAILED }

    /** 通过 su 快速重启目标输入法进程（设置页「重载输入法」）。 */
    fun killHost(
        context: Context,
        packageName: String,
        onResult: (ReloadResult, String?) -> Unit,
    ) {
        MainScope().launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
                    val error = process.errorStream.bufferedReader().readText().trim()
                    process.waitFor()
                    when {
                        error.contains("permission denied", ignoreCase = true) -> ReloadResult.NO_ROOT to null
                        error.contains("not found", ignoreCase = true) -> ReloadResult.NO_SU to null
                        error.isNotEmpty() -> ReloadResult.FAILED to error
                        else -> ReloadResult.OK to null
                    }
                }.getOrElse { e -> ReloadResult.FAILED to e.message }
            }
            onResult(outcome.first, outcome.second)
        }
    }
}
