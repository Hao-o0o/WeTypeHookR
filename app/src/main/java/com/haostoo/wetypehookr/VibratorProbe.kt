package com.haostoo.wetypehookr

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.lang.reflect.Method
import java.util.Arrays

/**
 * 临时诊断：查清「自定义震动变弱」到底是**被别的震动顶掉**还是**强度被系统降权**。
 *
 * - [installVibratorProbe]：在宿主进程里挂 `Vibrator.vibrate*` / `cancel` 的只读 Hook，
 *   把每一次调用的用途、波形、调用方都写进 diag.log；只记录「我们自己发出的」以及
 *   「紧跟在我们之后 200ms 内的其它调用」，避免刷屏。
 * - [play]：设置页里三种写法连测，同一段 200ms 波形分别以
 *   裸调用 / USAGE_TOUCH / USAGE_HARDWARE_FEEDBACK 发出，并采样 `isVibrating`。
 *
 * 这些都是实验代码，结论确定后应当整体删除。
 */
object VibratorProbe {

    /** 我们自己发出的调用窗口；只用于给日志打 own 标记，避免每次都回溯栈。 */
    private val own = ThreadLocal<Boolean>()

    @Volatile
    private var ownUntil = 0L

    private const val TRAIL_MS = 200L

    fun <T> trace(block: () -> T): T {
        val previous = own.get()
        own.set(true)
        ownUntil = System.currentTimeMillis() + TRAIL_MS
        return try {
            block()
        } finally {
            own.set(previous)
        }
    }

    fun PackageParam.installVibratorProbe(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: error("no vibrator")
            var type: Class<*>? = vibrator.javaClass
            var hooks = 0
            while (type != null && type != Any::class.java) {
                type.declaredMethods
                    .filter { it.name == "cancel" || it.name.startsWith("vibrate") }
                    .filter { method ->
                        method.parameterTypes.any {
                            it == VibrationEffect::class.java || it == LongArray::class.java ||
                                it == Long::class.javaPrimitiveType
                        }
                    }
                    .forEach { if (hook(context, it)) hooks++ }
                type = type.superclass
            }
            HostDiag.log(
                context,
                "VIB",
                "probe installed hooks=$hooks ${describeVibrator(context, vibrator)}",
            )
            installServiceProxyProbe(context)
        }.onFailure { HostDiag.log(context, "VIB", "probe install failed: $it") }
    }

    /** 把 Vibrator 实例的内部字段摊开：uid / 包名 / 是否真的有服务端 binder。 */
    private fun describeVibrator(context: Context, vibrator: Vibrator): String = buildString {
        append("impl=${vibrator.javaClass.name}")
        append(" id=")
        runCatching { append(vibrator.id) }.onFailure { append("?") }
        append(" has=")
        runCatching { append(vibrator.hasVibrator()) }.onFailure { append("?") }
        append(" amp=")
        runCatching { append(vibrator.hasAmplitudeControl()) }.onFailure { append("?") }
        append(" myUid=").append(android.os.Process.myUid())
        append(" pkg=").append(context.packageName)
        var type: Class<*>? = vibrator.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val value = field.get(vibrator)
                    append(" ").append(field.name).append("=")
                    append(
                        when (value) {
                            null -> "null"
                            is String, is Number, is Boolean -> value.toString()
                            else -> value.javaClass.name
                        },
                    )
                }
            }
            type = type.superclass
        }
    }

    /**
     * 服务端 binder 代理的探针。
     *
     * `SystemVibrator` 内部把 `RemoteException` / `RuntimeException` 吞掉只写一条日志，
     * 这里在代理上取 throwable，能直接看到服务端是否拒绝（SecurityException 等）。
     */
    private fun PackageParam.installServiceProxyProbe(context: Context) {
        runCatching {
            val proxy = Class.forName("android.os.IVibratorService\$Stub\$Proxy")
            var count = 0
            proxy.declaredMethods
                .filter { it.name.startsWith("vibrate") || it.name == "cancelVibrate" }
                .forEach { method ->
                    runCatching {
                        method.hook {
                            after {
                                if (!own.get() && System.currentTimeMillis() > ownUntil) return@after
                                val failure = throwable
                                HostDiag.log(
                                    context,
                                    "VIB",
                                    "  proxy ${method.name} throwable=${failure ?: "none"}",
                                )
                            }
                        }
                        count++
                    }
                }
            HostDiag.log(context, "VIB", "proxy probe hooks=$count")
        }.onFailure { HostDiag.log(context, "VIB", "proxy probe failed: $it") }
    }

    private fun PackageParam.hook(context: Context, method: Method): Boolean = runCatching {
        method.hook {
            after {
                val isOwn = own.get()
                val now = System.currentTimeMillis()
                val trailing = now <= ownUntil
                if (!isOwn && !trailing) return@after

                val argList = args
                val effect = argList.filterIsInstance<VibrationEffect>().firstOrNull()
                val attributes = argList.filterIsInstance<VibrationAttributes>().firstOrNull()
                val longArgs = argList.filterIsInstance<LongArray>().firstOrNull()
                    ?: argList.filterIsInstance<Long>().firstOrNull()
                HostDiag.log(
                    context,
                    "VIB",
                    buildString {
                        append(if (isOwn) "own " else "other ")
                        append("${method.name}(${argList.joinToString(",") { it?.javaClass?.simpleName ?: "null" }})")
                        // 关键：直接 vibrate 的 overload 会带上 uid / packageName，
                        // 服务端若认为「uid 与包名不匹配」会抛 SecurityException 并静默丢弃。
                        argList.filterIsInstance<Int>().firstOrNull()?.let { append(" uid=$it") }
                        argList.filterIsInstance<String>().let { strings ->
                            if (strings.isNotEmpty()) append(" pkg=${strings.joinToString("/")}")
                        }
                        if (effect != null) append(" effect=${describe(effect)}")
                        if (longArgs != null) append(" raw=$longArgs")
                        append(" attr=${attributes?.toString() ?: "none"}")
                        append(" caller=${caller()}")
                    },
                )
            }
        }
        true
    }.getOrDefault(false)

    /** 设置页三种写法连测。 */
    enum class Variant { RAW, TOUCH, HARDWARE, HAPTIC, WAVEFORM, APPCTX, VM }

    fun play(view: View, variant: Variant, durationMs: Long = 800L, amplitude: Int = 255) {
        val context = view.context
        val vibrator = context.getSystemService(Vibrator::class.java)
        // epoch 毫秒用于和 dumpsys vibrator_manager 的 start 时间对齐
        HostDiag.log(
            context,
            "VIB",
            "play ${variant.name} t=${System.currentTimeMillis()} duration=$durationMs amp=$amplitude",
        )
        trace {
            runCatching {
                when (variant) {
                    Variant.RAW -> vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                    Variant.TOUCH -> vibrator?.vibrate(
                        VibrationEffect.createOneShot(durationMs, amplitude),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                    )
                    Variant.HARDWARE -> vibrator?.vibrate(
                        VibrationEffect.createOneShot(durationMs, amplitude),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK),
                    )
                    Variant.HAPTIC -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    Variant.APPCTX -> context.applicationContext
                        .getSystemService(Vibrator::class.java)
                        ?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                    Variant.VM -> context.getSystemService(android.os.VibratorManager::class.java)
                        ?.defaultVibrator
                        ?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                    Variant.WAVEFORM -> vibrator?.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, durationMs),
                            intArrayOf(0, amplitude),
                            -1,
                        ),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                    )
                }
            }.onFailure { HostDiag.log(context, "VIB", "play ${variant.name} failed: $it") }
        }
        // 观察是否有别的震动在 200ms 内插进来（由 installVibratorProbe 记录），故只需要一个尾标记。
        view.postDelayed({
            HostDiag.log(context, "VIB", "  end ${variant.name} (after ${durationMs}ms)")
        }, durationMs + 20)
    }

    private fun describe(effect: VibrationEffect): String = runCatching {
        val timings = readField(effect, "mTimings") as? LongArray
        val amplitudes = readField(effect, "mAmplitudes") as? IntArray
        val duration = readField(effect, "mDuration")
        val amplitude = readField(effect, "mAmplitude")
        when {
            timings != null -> "waveform(timings=${Arrays.toString(timings)} amps=${Arrays.toString(amplitudes)})"
            duration != null -> "oneShot(duration=$duration amp=$amplitude)"
            else -> {
                val segments = readField(effect, "mSegments")?.let { (it as? Array<*>)?.size }
                "${effect.javaClass.simpleName}(segments=$segments)"
            }
        }
    }.getOrElse { effect.javaClass.simpleName }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it.get(target)
            }
            type = type.superclass
        }
        return null
    }

    /** 调用方：跳过框架与模块自身，取最内两层业务栈。 */
    private fun caller(): String = runCatching {
        Throwable().stackTrace
            .filterNot {
                it.className.startsWith("android.") || it.className.startsWith("java.") ||
                    it.className.startsWith("kotlin.") || it.className.startsWith("com.haostoo.wetypehookr")
            }
            .take(2)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
    }.getOrDefault("?")
}
