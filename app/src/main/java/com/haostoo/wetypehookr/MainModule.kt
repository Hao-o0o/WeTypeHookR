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
        //custom wave
        data class WaveformHaptic(
            val timings: LongArray,
            val amplitudes: IntArray
        )
        fun triggerHapticPreview(
            context: android.content.Context,
            view: View,
            modeIndex: Int,
            systemIndex: Int,
            duration: Float,
            strength: Float,
            isDown: Boolean
        ) {
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            // 调试日志：记录每次触发的震动风格与参数（受 debugLog 开关控制）
            if (DebugLog.isEnabled()) {
                DebugLog.log(
                    context, "vibrate",
                    "${if (isDown) "down" else "up"} mode=$modeIndex sys=$systemIndex dur=${duration}ms str=$strength"
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
                                (strength * 255).toInt().coerceIn(1, 255)
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(duration.toLong())
                    }
                }
                3 -> {
                    if (!vibrator.hasVibrator()) return
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        val waveform = if (isDown) cachedCustomHaptic?.down else cachedCustomHaptic?.up
                        waveform?.let {
                            vibrator.vibrate(
                                android.os.VibrationEffect.createWaveform(it.timings, it.amplitudes, -1)
                            )
                        }
                    }
                }
            }
        }

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
        @Volatile private var toastShown = false

        @Volatile var cachedSettings: SettingsState =
            SettingsState(0, 0, 50f, 0.5f, 50f, 0.5f, 0, 0)

        private val DEFAULT_SETTINGS = SettingsState(1, 1, 50f, 0.5f, 50f, 0.5f, 0, 3)

        // ================= 日志导出（文件选择器结果回调） =================
        const val LOG_EXPORT_REQUEST_CODE = 0x5A17
        @Volatile var pendingLogExport: ((android.net.Uri) -> Unit)? = null
        fun requestLogExport(cb: (android.net.Uri) -> Unit) {
            pendingLogExport = cb
        }

        // ================= 配置热重载（文件监听） =================
        // 设置页与键盘 hook 分属不同进程（主进程 / hld 进程），但共享同一配置文件。
        // FileObserver 监听配置目录，任一进程写入（保存设置 / 更新 customhaptic.json）
        // 都会触发各进程重新读取缓存，保存后立即生效，无需再杀进程。
        @Volatile private var configObserver: android.os.FileObserver? = null

        fun startConfigWatcher(packageName: String) {
            try {
                val configDir = File(
                    "/storage/emulated/0/Android/data/$packageName/files/haostoo/config"
                )
                configDir.mkdirs()
                configObserver?.stopWatching()
                configObserver = object : android.os.FileObserver(
                    configDir.absolutePath,
                    android.os.FileObserver.CLOSE_WRITE or
                        android.os.FileObserver.CREATE or
                        android.os.FileObserver.MOVED_TO
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        try {
                            val fresh = readConfigDirect(packageName)
                            if (fresh != null) {
                                cachedSettings = fresh
                                Log.i(TAG, "Config reloaded: $fresh")
                            } else {
                                // 文件缺失/为空/解析失败时保留旧缓存，避免被默认值覆盖
                                Log.e(TAG, "Config reload skipped (missing/empty/parse fail), keep cached=$cachedSettings")
                            }
                            cachedCustomHaptic = readCustomHaptic(packageName)
                        } catch (e: Exception) {
                            Log.e(TAG, "Config reload failed", e)
                        }
                    }
                }.also { it.startWatching() }
                Log.i(TAG, "✅ Config watcher started: ${configDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Config watcher start failed", e)
            }
        }

        // ================= 配置读取 =================
        // 返回 null 表示文件缺失/为空/解析失败；调用方据此保留旧缓存或使用默认值
        private fun readConfigDirect(packageName: String): SettingsState? {
            val file = File(configPath(packageName))
            if (!file.exists() || file.length() == 0L) return null

            return try {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(file.inputStream(), "UTF-8")

                var downMode = 0; var upMode = 0
                var downSystem = 0; var upSystem = 0
                var downDuration = 50f; var downStrength = 0.5f
                var upDuration = 50f; var upStrength = 0.5f
                var themeMode = 2
                var debugLog = false
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
                            "theme_mode"    -> themeMode    = parser.nextText().toInt()
                            "debug_log"     -> debugLog     = parser.nextText().toBoolean()
                        }
                    }
                    event = parser.next()
                }
                SettingsState(downMode, upMode, downDuration, downStrength, upDuration, upStrength, downSystem, upSystem, themeMode, debugLog)
            } catch (e: Exception) {
                Log.e(TAG, "parse FAILED", e)
                null
            }
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PKG && param.packageName != DOUBAO_PKG) return

        cachedSettings = readConfigDirect(param.packageName) ?: DEFAULT_SETTINGS
        cachedCustomHaptic = readCustomHaptic(param.packageName)
        startConfigWatcher(param.packageName)

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
                    break
                } catch (_: Exception) {
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
                                } catch (_: Exception) {
                                    return chain.proceed()
                                }
                            }
                        })
                    }
                    break
                } catch (_: Exception) {
                }
            }
        }

        // ================= Hook 日志导出结果（框架方法，版本免疫） =================
        // 设置页「导出日志」通过 ACTION_CREATE_DOCUMENT 选位置，结果经 Activity.onActivityResult 回传
        try {
            val m = android.app.Activity::class.java.getMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                android.content.Intent::class.java
            )
            hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val cb = pendingLogExport
                    if (cb != null) {
                        val requestCode = chain.args.getOrNull(0) as? Int
                        val resultCode = chain.args.getOrNull(1) as? Int
                        val data = chain.args.getOrNull(2) as? android.content.Intent
                        if (requestCode == LOG_EXPORT_REQUEST_CODE && resultCode == android.app.Activity.RESULT_OK) {
                            val uri = data?.data
                            if (uri != null) {
                                pendingLogExport = null
                                cb(uri)
                            }
                        }
                    }
                    return result
                }
            })
            Log.i(TAG, "✅ Hooked Activity.onActivityResult")
        } catch (e: Exception) {
            Log.e(TAG, "onActivityResult hook failed", e)
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

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    triggerHapticPreview(view.context, view, config.downIndex, config.downSystemIndex, config.downDuration, config.downStrength, isDown = true)
                    return chain.proceed()
                }
                MotionEvent.ACTION_UP -> {
                    triggerHapticPreview(view.context, view, config.upIndex, config.upSystemIndex, config.upDuration, config.upStrength, isDown = false)
                    return chain.proceed()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    triggerHapticPreview(view.context, view, config.downIndex, config.downSystemIndex, config.downDuration, config.downStrength, isDown = true)
                    return chain.proceed()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    triggerHapticPreview(view.context, view, config.upIndex, config.upSystemIndex, config.upDuration, config.upStrength, isDown = false)
                    return chain.proceed()
                }
            }
            return chain.proceed()
        }

    }

}