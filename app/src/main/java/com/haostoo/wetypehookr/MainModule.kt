package com.haostoo.wetypehookr

import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle

class MainModule : XposedModule() {

    companion object {

        private const val TAG = "WETYPE_HOOK"

        @Volatile
        private var realHookInstalled = false

        @Volatile
        private var toastShown = false
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

        // ⭐ 缓存变量（只读取一次）
        private var downType: Int = HapticType.KEYBOARD.value
        private var upType: Int = HapticType.KEYBOARD.value

        private var initialized = false

        override fun intercept(chain: XposedInterface.Chain): Any? {

            val view = chain.thisObject as? View
                ?: return chain.proceed()

            val event = chain.args.getOrNull(0) as? MotionEvent
                ?: return chain.proceed()

            val context = view.context

            // ⭐ 只在第一次调用时初始化
            if (!initialized) {

                val (down, up) = HapticProviderClient.get(context)

                downType = down
                upType = up

                initialized = true

                view.post {

                    Toast.makeText(
                        context,
                        "UP:${typeName(upType)}\nDOWN:${typeName(downType)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fun perform(type: Int) {

                if (type == HapticType.NONE.value) return

                val realType = when (type) {

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

                val ok = view.performHapticFeedback(realType)

                if (!ok) {
                    view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP
                    )
                }
            }

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    perform(downType)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    perform(upType)
                }
            }

            return chain.proceed()
        }

        // ⭐ 用于显示名字（可选）
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