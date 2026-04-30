package com.haostoo.wetypehookr

import android.app.Activity
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

class MainModule : XposedModule() {

    companion object {

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
        private const val TAG = "WETYPE_HOOK"
        private const val TARGET_PKG = "com.tencent.wetype"

        private const val CONFIG_PATH =
            "/storage/emulated/0/Android/data/com.tencent.wetype/files/haostoo/config/config.xml"

        @Volatile
        private var toastShown = false

        @Volatile
        var cachedSettings: SettingsState =
            SettingsState(0, 0, 50f, 0.5f, 50f, 0.5f, 0, 0)

        // ================= 配置读取 =================
        private fun readConfigDirect(): SettingsState {
            val file = File(CONFIG_PATH)

            Log.i(TAG, "=== readConfigDirect ===")
            Log.i(TAG, "path=${file.absolutePath}")
            Log.i(TAG, "exists=${file.exists()}")
            Log.i(TAG, "length=${file.length()}")

            val default = SettingsState(1, 1, 50f, 0.5f, 50f, 0.5f, 0, 3)

            if (!file.exists()) {
                Log.w(TAG, "config NOT found")
                return default
            }

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
                            "down_mode"     -> downMode = parser.nextText().toInt()
                            "up_mode"       -> upMode = parser.nextText().toInt()
                            "down_system"   -> downSystem = parser.nextText().toInt()
                            "up_system"     -> upSystem = parser.nextText().toInt()
                            "down_duration" -> downDuration = parser.nextText().toFloat()
                            "down_strength" -> downStrength = parser.nextText().toFloat()
                            "up_duration"   -> upDuration = parser.nextText().toFloat()
                            "up_strength"   -> upStrength = parser.nextText().toFloat()
                        }
                    }
                    event = parser.next()
                }

                val result = SettingsState(
                    downMode, upMode,
                    downDuration, downStrength,
                    upDuration, upStrength,
                    downSystem, upSystem
                )

                Log.i(TAG, "parsed config=$result")

                result

            } catch (e: Exception) {
                Log.e(TAG, "parse FAILED", e)
                default
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {}

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PKG) return

        Log.i(TAG, "🚀 onPackageReady: ${param.packageName}")

        // ⭐ 只读取一次配置
        try {
            cachedSettings = readConfigDirect()
            Log.i(TAG, "Initial config=$cachedSettings")
        } catch (e: Exception) {
            Log.e(TAG, "Config load failed", e)
        }

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
                                Log.i(TAG, "injectSettingsPage")
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

                view.post {
                    try {
                        val dir = File("/storage/emulated/0/Android/data/com.tencent.wetype/files/haostoo/")
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }

                        val file = File(dir, "hookstatus.txt")

                        // 当前时间
                        val time = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())

                        // 👇 这里填你实际 hook 的类和方法

                        val content = """
                            Hook Success!
                Hook Time: $time
            """.trimIndent()

                        // 👇 直接覆盖写入（文件存在会自动替换）
                        file.writeText(content)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            val config = cachedSettings

            val vibrator = view.context.getSystemService(android.os.Vibrator::class.java)
                    as android.os.Vibrator
            fun vibrateCompat(duration: Long, strength: Float) {
                if (vibrator == null || !vibrator.hasVibrator()) return

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val effect = android.os.VibrationEffect.createOneShot(
                        duration,
                        (strength * 255).toInt().coerceIn(1, 255)
                    )
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {

                    when (config.downIndex) {

                        0 -> {}

                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.downSystemIndex))

                        2 -> {
                            vibrateCompat(config.downDuration.toLong(), config.downStrength)
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {

                    when (config.upIndex) {

                        0 -> {}

                        1 -> view.performHapticFeedback(hapticConstantForIndex(config.upSystemIndex))

                        2 -> {
                            vibrateCompat(config.upDuration.toLong(), config.upStrength)
                        }
                    }
                }
            }

            return chain.proceed()
        }
    }
}