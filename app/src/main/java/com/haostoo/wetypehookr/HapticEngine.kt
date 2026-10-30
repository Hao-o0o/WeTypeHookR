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
            1 -> view.performHapticFeedback(hapticConstantForIndex(systemIndex))
            2 -> {
                if (!vibrator.hasVibrator()) return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            duration.toLong(),
                            (strength * 255).toInt().coerceIn(1, 255),
                        ),
                        hapticAttributes(),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration.toLong())
                }
            }
            3 -> {
                if (!vibrator.hasVibrator()) return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val w = waveform
                        ?: if (isDown) cachedCustomHaptic?.down else cachedCustomHaptic?.up
                    w?.let {
                        vibrator.vibrate(
                            android.os.VibrationEffect.createWaveform(
                                it.timings.toLongArray(),
                                it.amplitudes.toIntArray(),
                                -1,
                            ),
                            hapticAttributes(),
                        )
                    }
                }
            }
        }
    }

    /**
     * 自定义震动的用途声明。
     *
     * 必须显式给一个非 0 的 usage：系统 `VibratorManagerService.fixupVibrationAttributes()`
     * 会把 `usage == 0` 且「可作为触摸反馈」的效果改写成 `USAGE_TOUCH`，随后走触摸反馈的
     * 立体渲染通道，长效果会被渲染成空内容（dumpsys 显示 `played: Stereo{...: null}`），
     * 马达不驱动 —— 表现为「自定义震动被压成一下弱震」。`USAGE_PHYSICAL_EMULATION`
     * 语义即自定义触感，强度档位为 HIGH，且不参与触摸反馈的改写。
     */
    private fun hapticAttributes(): android.os.VibrationAttributes =
        android.os.VibrationAttributes.createForUsage(
            android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION,
        )

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
