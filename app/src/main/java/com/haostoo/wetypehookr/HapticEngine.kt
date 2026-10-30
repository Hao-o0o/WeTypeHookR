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
        dispatchAction(view, event.actionMasked)
    }

    /**
     * 按「动作码」触发，供需要延迟调用时使用（宿主回收 MotionEvent 后不能再持有它）。
     */
    fun dispatchAction(view: View, action: Int) {
        val settings = cachedSettings
        when (action) {
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
     * 宿主按键处理结束之后再震动（带一点点延迟）。
     *
     * 微信输入法与 Gboard 都在自己的按键处理里调 `vibrator.vibrate(createOneShot(~25ms))`，
     * 微信默认走的是 `View.performHapticFeedback`（经 WindowManager 异步下发），
     * 两者都会把先发出的长波形顶掉 —— 表现就是自定义时长/波形被压成一下短震。
     * 所以这里让我们的震动排到它们之后。
     */
    fun dispatchAfterHost(view: View, action: Int, delayMs: Long = 24L) {
        mainHandler.postDelayed({ dispatchAction(view, action) }, delayMs)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                        )
                    }
                }
            }
        }
    }

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
