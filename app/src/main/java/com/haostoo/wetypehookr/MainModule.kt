package com.haostoo.wetypehookr

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import kotlin.jvm.java

class MainModule : XposedModule() {

    companion object {
        //custom wave
        data class WaveformHaptic(
            val timings: LongArray,
            val amplitudes: IntArray
        )

        data class CustomHapticConfig(
            val down: WaveformHaptic?,
            val up: WaveformHaptic?
        )

        @Volatile var cachedCustomHaptic: CustomHapticConfig? = null

        private fun readCustomHaptic(packageName: String): CustomHapticConfig? {
            val file = File(
                "/storage/emulated/0/Android/data/$packageName/files/haostoo/config/customhaptic.json"
            )
            if (!file.exists()) return null

            return try {
                val json = org.json.JSONObject(file.readText())
                fun parseWaveform(key: String): WaveformHaptic? {
                    val obj = json.optJSONObject(key) ?: return null
                    val tArr = obj.optJSONArray("timings") ?: return null
                    val aArr = obj.optJSONArray("amplitudes") ?: return null
                    if (tArr.length() != aArr.length()) return null
                    val timings = LongArray(tArr.length()) { tArr.getLong(it) }
                    val amplitudes = IntArray(aArr.length()) { aArr.getInt(it) }
                    return WaveformHaptic(timings, amplitudes)
                }
                CustomHapticConfig(
                    down = parseWaveform("down"),
                    up = parseWaveform("up")
                )
            } catch (e: Exception) {
                Log.e(TAG, "customhaptic.json parse failed", e)
                null
            }
        }

        fun killWeTypeHld(context: android.content.Context, onResult: (String) -> Unit) {
            kotlinx.coroutines.MainScope().launch {
                val message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val process = Runtime.getRuntime()
                            .exec(arrayOf("su", "-c", "ps -A | grep com.tencent.wetype:hld | awk '{print \$2}'"))
                        val pid = process.inputStream.bufferedReader().readText().trim()
                        val error = process.errorStream.bufferedReader().readText().trim()

                        when {
                            error.contains("permission denied", ignoreCase = true) -> "失败：没有 root 权限"
                            pid.isEmpty() -> "失败：未找到目标进程"
                            else -> {
                                val killProcess = Runtime.getRuntime()
                                    .exec(arrayOf("su", "-c", "kill -9 $pid"))
                                val killError = killProcess.errorStream.bufferedReader().readText().trim()
                                killProcess.waitFor()

                                when {
                                    killError.contains("permission denied", ignoreCase = true) -> "失败：没有 root 权限"
                                    killError.isNotEmpty() -> "失败：$killError"
                                    else -> {
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                        "成功"
                                    }
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        "失败：没有 root 权限"
                    } catch (e: Exception) {
                        "失败：${e.message}"
                    }
                }
                onResult(message)
            }
        }
        fun killDoubaoHld(context: android.content.Context, onResult: (String) -> Unit) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        private const val TAG = "WETYPE_HOOK"
        private const val TARGET_PKG = "com.tencent.wetype"
        private const val DOUBAO_PKG = "com.bytedance.android.doubaoime"
        private fun configPath(packageName: String) =
            "/storage/emulated/0/Android/data/$packageName/files/haostoo/config/config.xml"
// 新增
        @Volatile private var toastShown = false

        @Volatile var cachedSettings: SettingsState =
            SettingsState(0, 0, 50f, 0.5f, 50f, 0.5f, 0, 0)

        // ================= 配置读取 =================
        private fun readConfigDirect(packageName: String): SettingsState {
            val file = File(configPath(packageName))
            val default = SettingsState(1, 1, 50f, 0.5f, 50f, 0.5f, 0, 3)
            if (!file.exists()) return default

            return try {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(file.inputStream(), "UTF-8")

                var downMode = 0; var upMode = 0
                var downSystem = 0; var upSystem = 0
                var downDuration = 50f; var downStrength = 0.5f
                var upDuration = 50f; var upStrength = 0.5f
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "down_mode"     -> downMode     = parser.nextText().toInt()
                            "up_mode"       -> upMode       = parser.nextText().toInt()
                            "down_system"   -> downSystem   = parser.nextText().toInt()
                            "up_system"     -> upSystem     = parser.nextText().toInt()
                            "down_duration" -> downDuration = parser.nextText().toFloat()
                            "down_strength" -> downStrength = parser.nextText().toFloat()
                            "up_duration"   -> upDuration   = parser.nextText().toFloat()
                            "up_strength"   -> upStrength   = parser.nextText().toFloat()
                        }
                    }
                    event = parser.next()
                }
                SettingsState(downMode, upMode, downDuration, downStrength, upDuration, upStrength, downSystem, upSystem)
            } catch (e: Exception) {
                Log.e(TAG, "parse FAILED", e)
                default
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {}

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PKG && param.packageName != DOUBAO_PKG) return

        try {
            cachedSettings = readConfigDirect(param.packageName)
            cachedCustomHaptic = readCustomHaptic(param.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Config load failed", e)
        }

        val isDoubao = param.packageName == DOUBAO_PKG
        val loaders = collectClassLoaders(param.classLoader)

        // ================= Hook 震动 =================
        for (loader in loaders) {
            try {
                val clazz = loader.loadClass("com.tencent.wetype.plugin.hld.view.ImeRootView")
                val method = clazz.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
                hook(method).intercept(ImeRootViewHooker())
                Log.i(TAG, "✅ Hooked ImeRootView")
                break
            } catch (_: Exception) {}
        }
        if (isDoubao) {
            for (loader in loaders) {
                try {
                    val clazz = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
                    val method = clazz.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
                    hook(method).intercept(ImeRootViewHooker())
                    //File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                        //.appendText("KeyboardView hooked\n")
                    break
                } catch (e: Exception) {
                    //File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                        //.appendText("KeyboardView hook failed: $e\n")
                }
            }
        }
        // ================= Hook 设置页 =================
        for (loader in loaders) {
            try {
                val clazz = loader.loadClass("com.tencent.wetype.plugin.hld.ui.feedback.HldFeedbackUI")
                val method = clazz.getDeclaredMethod("onCreate", android.os.Bundle::class.java)

                hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity ?: return result
                        Handler(Looper.getMainLooper()).post {
                            try {
                                injectSettingsPage(activity)
                            } catch (e: Exception) {
                                Log.e(TAG, "inject fail", e)
                            }
                        }
                        return result
                    }
                })

                Log.i(TAG, "✅ Hooked HldFeedbackUI")
                break
            } catch (_: Exception) {}
        }
        //doubao 设置页
        if (isDoubao) {
            for (loader in loaders) {
                try {
                    var searchClass: Class<*>? = loader.loadClass("com.bytedance.android.doubaoime.activity.FeedbackActivity")
                    var onCreateMethod: java.lang.reflect.Method? = null
                    while (searchClass != null && onCreateMethod == null) {
                        try {
                            onCreateMethod = searchClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
                        } catch (_: Exception) {
                            searchClass = searchClass.superclass
                        }
                    }

                    if (onCreateMethod != null) {
                        hook(onCreateMethod).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                try {
                                    val result = chain.proceed()
                                    val activity = chain.thisObject as? Activity ?: return result
                                    Handler(Looper.getMainLooper()).post {
                                        injectSettingsPage(activity)
                                    }
                                    return result
                                } catch (e: Exception) {
                                   // File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                                       // .appendText("hook intercept fail: $e\n${e.stackTraceToString()}\n")
                                    return chain.proceed()
                                }
                            }
                        })
                        //File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                            //.appendText("FeedbackActivity hooked via ${onCreateMethod.declaringClass.name}\n")
                    }
                    break
                } catch (e: Exception) {
                   // File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
                       // .appendText("hook FeedbackActivity failed: $e\n")
                }
            }
        }
    }

    private fun collectClassLoaders(root: ClassLoader): List<ClassLoader> {
        val result = mutableListOf<ClassLoader>()
        var cl: ClassLoader? = root
        while (cl != null) {
            result.add(cl)
            cl = cl.parent
        }
        return result
    }

    // ================= Hook =================

    class ImeRootViewHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            val event = chain.args.getOrNull(0) as? MotionEvent ?: return chain.proceed()

            if (!toastShown) {
                toastShown = true
                val pkgDir = if (view.context.packageName == DOUBAO_PKG) DOUBAO_PKG else TARGET_PKG
                view.post {
                    try {
                        val dir = File("/storage/emulated/0/Android/data/$pkgDir/files/haostoo/")
                        dir.mkdirs()
                        val time = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        File(dir, "hookstatus.txt").writeText("Hook Success!\nHook Time: $time")
                    } catch (_: Exception) {}
                }
            }

            val config = cachedSettings

            val vibrator = view.context.getSystemService(android.os.Vibrator::class.java) as android.os.Vibrator
            fun vibrateCompat(duration: Long, strength: Float) {
                if (!vibrator.hasVibrator()) return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            duration,
                            (strength * 255).toInt().coerceIn(1, 255)
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
            fun vibrateWaveform(timings: LongArray, amplitudes: IntArray) {
                if (!vibrator.hasVibrator()) return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createWaveform(timings, amplitudes, -1)
                    )
                }
            }
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    when (config.downIndex) {
                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.downSystemIndex))
                        2 -> vibrateCompat(config.downDuration.toLong(), config.downStrength)
                        3 -> cachedCustomHaptic?.down?.let { vibrateWaveform(it.timings, it.amplitudes) }
                    }

                    return chain.proceed()
                }

                MotionEvent.ACTION_UP -> {
                    when (config.upIndex) {
                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.upSystemIndex))
                        2 -> vibrateCompat(config.upDuration.toLong(), config.upStrength)
                        3 -> cachedCustomHaptic?.up?.let { vibrateWaveform(it.timings, it.amplitudes) }
                    }

                    return chain.proceed()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    when (config.downIndex) {
                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.downSystemIndex))
                        2 -> vibrateCompat(config.downDuration.toLong(), config.downStrength)
                        3 -> cachedCustomHaptic?.down?.let { vibrateWaveform(it.timings, it.amplitudes) }
                    }
                    return chain.proceed()
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    when (config.upIndex) {
                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.upSystemIndex))
                        2 -> vibrateCompat(config.upDuration.toLong(), config.upStrength)
                        3 -> cachedCustomHaptic?.up?.let { vibrateWaveform(it.timings, it.amplitudes) }
                    }
                    return chain.proceed()
                }
            }
            return chain.proceed()
        }

    }

}