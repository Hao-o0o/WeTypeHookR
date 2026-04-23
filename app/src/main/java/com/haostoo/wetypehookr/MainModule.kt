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

    class ImeRootViewHooker :
        XposedInterface.Hooker {

        override fun intercept(
            chain: XposedInterface.Chain
        ): Any? {

            val view =
                chain.thisObject as? View
                    ?: return chain.proceed()

            if (chain.args.isEmpty()) {
                return chain.proceed()
            }

            val event =
                chain.args[0] as? MotionEvent
                    ?: return chain.proceed()

            if (!toastShown) {

                toastShown = true

                view.post {
                    Toast.makeText(
                        view.context,
                        "Hook attached",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fun doHaptic() {

                val ok =
                    view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP
                    )

                if (!ok) {
                    view.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY
                    )
                }
            }

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    doHaptic()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    doHaptic()
                }
            }

            return chain.proceed()
        }
    }
}