package com.haostoo.wetypehookr

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class MainModule : XposedModule() {

    companion object {

        private const val TAG = "WETYPE_HOOK"

        @Volatile
        private var realHookInstalled = false

    }

    override fun onPackageLoaded(
        param: XposedModuleInterface.PackageLoadedParam
    ) {

        if (param.getPackageName() != "com.tencent.wetype") {
            return
        }

        try {

            val method =
                ClassLoader::class.java.getDeclaredMethod(
                    "loadClass",
                    String::class.java
                )

            var handle: HookHandle? = null

            handle = hook(method).intercept(
                ClassLoaderHooker {
                    handle?.unhook()
                }
            )

        } catch (t: Throwable) {

            Log.e(TAG, "Stage1 install failed=$t")
        }
    }

    inner class ClassLoaderHooker(
        private val onSuccess: () -> Unit
    ) : XposedInterface.Hooker {

        override fun intercept(
            chain: XposedInterface.Chain
        ): Any? {

            val name = chain.args[0] as? String

            val result = chain.proceed()

            if (realHookInstalled) {
                return result
            }

            if (name == "com.tencent.wetype.plugin.hld.view.ImeRootView") {

                try {

                    val clazz = result as Class<*>

                    val method =
                        clazz.getDeclaredMethod(
                            "dispatchTouchEvent",
                            MotionEvent::class.java
                        )

                    hook(method)
                        .intercept(
                            ImeRootViewHooker()
                        )

                    realHookInstalled = true

                    Log.e(TAG, "Hook success")

                    onSuccess()

                } catch (t: Throwable) {

                    Log.e(TAG, "Hook target failed=$t")
                }
            }

            return result
        }
    }

    class ImeRootViewHooker : XposedInterface.Hooker {

        private var config: HapticFullConfig? = null
        private var initialized = false

        override fun intercept(chain: XposedInterface.Chain): Any? {

            val view = chain.thisObject as? View
                ?: return chain.proceed()

            val event = chain.args.getOrNull(0) as? MotionEvent
                ?: return chain.proceed()

            val context = view.context

            // ⭐ 只初始化一次
            if (!initialized) {

                config = HapticProviderClient.getFull(context)
                initialized = true

                val cfg = config!!

                view.post {

                    val msg = buildString {

                        append("DOWN:")
                        append(
                            if (cfg.downMode == 0)
                                typeName(cfg.downType)
                            else
                                "${cfg.downDuration}ms ${cfg.downStrength}"
                        )

                        append("\nUP:")
                        append(
                            if (cfg.upMode == 0)
                                typeName(cfg.upType)
                            else
                                "${cfg.upDuration}ms ${cfg.upStrength}"
                        )
                    }

                    Toast.makeText(
                        context,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val cfg = config ?: return chain.proceed()

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {

                    if (cfg.downMode == 0) {
                        performSystem(view, cfg.downType)
                    } else {
                        vibrate(context, cfg.downDuration, cfg.downStrength)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {

                    if (cfg.upMode == 0) {
                        performSystem(view, cfg.upType)
                    } else {
                        vibrate(context, cfg.upDuration, cfg.upStrength)
                    }
                }
            }

            return chain.proceed()
        }

        // ================= 系统震动 =================
        private fun performSystem(view: View, type: Int) {

            if (type == HapticType.NONE.value) return

            val real = when (type) {

                HapticType.KEYBOARD.value ->
                    HapticFeedbackConstants.KEYBOARD_TAP

                HapticType.VIRTUAL_KEY.value ->
                    HapticFeedbackConstants.VIRTUAL_KEY

                HapticType.VIRTUAL_KEY_RELEASE.value ->
                    HapticFeedbackConstants.VIRTUAL_KEY_RELEASE

                HapticType.LONG_PRESS.value ->
                    HapticFeedbackConstants.LONG_PRESS

                HapticType.CLOCK.value ->
                    HapticFeedbackConstants.CLOCK_TICK

                HapticType.CONTEXT.value ->
                    HapticFeedbackConstants.CONTEXT_CLICK

                else ->
                    HapticFeedbackConstants.KEYBOARD_TAP
            }

            val ok = view.performHapticFeedback(real)

            if (!ok) {
                view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP
                )
            }
        }

        // ================= 自定义震动 =================
        private fun vibrate(
            context: Context,
            duration: Long,
            strength: Int
        ) {

            try {

                val vibrator =
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

                if (!vibrator.hasVibrator()) return

                val amplitude = strength.coerceIn(1, 255)

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(duration, amplitude)
                    )
                } else {
                    vibrator.vibrate(duration)
                }

            } catch (_: Throwable) {
            }
        }

        // ================= 名称映射 =================
        private fun typeName(type: Int): String {
            return when (type) {
                HapticType.KEYBOARD.value -> "KEYBOARD"
                HapticType.VIRTUAL_KEY.value -> "VIRTUAL_KEY"
                HapticType.VIRTUAL_KEY_RELEASE.value -> "VIRTUAL_KEY_RELEASE"
                HapticType.LONG_PRESS.value -> "LONG_PRESS"
                HapticType.CLOCK.value -> "CLOCK"
                HapticType.CONTEXT.value -> "CONTEXT"
                HapticType.NONE.value -> "NONE"
                else -> "UNKNOWN($type)"
            }
        }
    }
}