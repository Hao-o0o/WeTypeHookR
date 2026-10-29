package com.haostoo.wetypehookr

import android.content.Context

/**
 * 宿主目录中的配置读写与热更新。
 *
 * 设计要点：
 * - 文件是唯一权威源：UI 只写文件，Hook 只读；写完后由 [android.os.FileObserver] 感知变化。
 * - 写入原子化：临时文件 → `fd.sync()` → 覆盖重命名，避免读到半截 JSON。
 * - 解析失败保留上一份有效缓存，绝不让输入法崩溃。
 */
object SettingsStore {

    private const val TAG = "WETYPE_CFG"

    /** 当前 settings.json 结构版本。 */
    private const val SCHEMA = 2

    /** 热更新去抖窗口（毫秒）。 */
    private const val DEBOUNCE_MS = 200L

    /** FAB 位置与配色（宿主设置页自己的 UI 状态）。 */
    data class UiState(
        val fabX: Float = 0f,
        val fabY: Float = DEFAULT_FAB_Y,
        val colorMode: Int = 2,
    ) {
        companion object {
            const val DEFAULT_FAB_Y = 0.62f
        }
    }

    /** 一次完整读取的结果。 */
    data class Snapshot(
        val settings: SettingsModel.SettingsState,
        val haptic: SettingsModel.CustomHapticConfig?,
        val ui: UiState,
        val revision: Long,
    )

    /** 当前生效缓存，Hook 侧只读这份内存快照。 */
    @Volatile
    var current: Snapshot = Snapshot(
        settings = SettingsModel.defaultSettings(),
        haptic = null,
        ui = UiState(),
        revision = 0L,
    )
        private set

    private var observer: Any? = null

    private var watchedHost: String? = null

    private var appContext: Context? = null

    private val debounceHandler = Debouncer()

    /**
     * 宿主进程启动时调用一次：读取当前宿主配置并挂上文件监听。
     *
     * 之后 [HapticEngine.cachedSettings] / [HapticEngine.cachedCustomHaptic] 会随文件变化自动刷新。
     */
    @Synchronized
    fun attach(context: Context, host: String) {
        if (watchedHost == host) return
        stopWatching()

        appContext = context.applicationContext
        watchedHost = host

        val snapshot = load(context, host)
        current = snapshot
        publish(snapshot)
        startWatching(context, host)
    }

    /**
     * 读取某个宿主的全部配置；`settings.json` 不存在时按迁移顺序初始化并落盘。
     *
     * 读取失败（目录不可访问、JSON 非法）时返回默认值，不抛异常。
     */
    fun load(context: Context, host: String): Snapshot {
        val settingsFile = HostPaths.settings(context)
        val settings = if (settingsFile.isFile) {
            readSettings(settingsFile) ?: migrate(context).also { writeSettings(context, it) }
        } else {
            migrate(context).also { writeSettings(context, it) }
        }

        val haptic = runCatching {
            val file = HostPaths.haptic(context)
            if (file.isFile) SettingsModel.parseCustomHaptic(file.readText()) else null
        }.getOrNull()

        val ui = runCatching {
            val file = HostPaths.ui(context)
            if (file.isFile) parseUi(file.readText()) else UiState()
        }.getOrDefault(UiState())

        return Snapshot(
            settings = settings,
            haptic = haptic,
            ui = ui,
            revision = settingsFile.takeIf { it.isFile }?.lastModified() ?: 0L,
        )
    }

    /** 保存设置；先写 haptic 位置的调用由调用方保证顺序（见 [save]）。 */
    fun saveSettings(context: Context, host: String, settings: SettingsModel.SettingsState): Boolean {
        val ok = writeSettings(context, settings)
        if (ok) {
            // 同进程内立即回填，避免 UI 在 200ms 去抖窗口内读到旧值；Hook 侧仍走文件监听。
            val merged = current.copy(settings = settings, revision = System.currentTimeMillis())
            current = merged
            publish(merged)
        }
        return ok
    }

    fun saveHaptic(context: Context, host: String, haptic: SettingsModel.CustomHapticConfig): Boolean {
        val ok = atomicWrite(HostPaths.haptic(context), SettingsModel.serializeCustomHaptic(haptic))
        if (ok) {
            val merged = current.copy(haptic = haptic)
            current = merged
            publish(merged)
        }
        return ok
    }

    fun saveUi(context: Context, host: String, ui: UiState): Boolean {
        val ok = atomicWrite(HostPaths.ui(context), serializeUi(ui))
        if (ok) {
            val merged = current.copy(ui = ui)
            current = merged
        }
        return ok
    }

    private fun migrate(context: Context): SettingsModel.SettingsState {
        val legacy = HostPaths.legacyConfig(context)
        if (legacy.isFile) {
            runCatching { legacy.readText() }.getOrNull()
                ?.let { LegacyConfigImporter.parseLegacyXml(it) }
                ?.let {
                    logInfo("migrated legacy config.xml")
                    return it
                }
        }
        return SettingsModel.defaultSettings()
    }

    private fun publish(snapshot: Snapshot) {
        HapticEngine.cachedSettings = snapshot.settings
        HapticEngine.cachedCustomHaptic = snapshot.haptic
        HookRuntime.onConfigApplied(snapshot.revision)
    }

    private fun startWatching(context: Context, host: String) {
        val file = HostPaths.configDir(context)
        val listener = ConfigFileObserver(file, onChanged = {
            debounceHandler.postDelayed(DEBOUNCE_MS) {
                val ctx = appContext ?: return@postDelayed
                val snapshot = load(ctx, host)
                current = snapshot
                publish(snapshot)
                logInfo("config reloaded revision=${snapshot.revision}")
            }
        })
        observer = listener
        runCatching { listener.startWatching() }
            .onFailure { android.util.Log.w(TAG, "FileObserver start failed", it) }
    }

    private fun stopWatching() {
        (observer as? ConfigFileObserver)?.let { runCatching { it.stopWatching() } }
        observer = null
        debounceHandler.cancel()
    }

    private fun readSettings(file: java.io.File): SettingsModel.SettingsState? = runCatching {
        val json = org.json.JSONObject(file.readText())
        SettingsModel.SettingsState(
            downIndex = json.optInt("down_mode", 1),
            upIndex = json.optInt("up_mode", 1),
            downDuration = json.optDouble("down_duration", 50.0).toFloat(),
            downStrength = json.optDouble("down_strength", 0.5).toFloat(),
            upDuration = json.optDouble("up_duration", 50.0).toFloat(),
            upStrength = json.optDouble("up_strength", 0.5).toFloat(),
            downSystemIndex = json.optInt("down_system", 0),
            upSystemIndex = json.optInt("up_system", 3),
            themeMode = json.optInt("theme_mode", 2),
            debugLog = json.optBoolean("debug_log", false),
        )
    }.onFailure { android.util.Log.w(TAG, "settings.json parse failed, keep last valid", it) }.getOrNull()

    private fun writeSettings(context: Context, settings: SettingsModel.SettingsState): Boolean {
        val now = System.currentTimeMillis()
        val json = org.json.JSONObject().apply {
            put("schema", SCHEMA)
            put("revision", now)
            put("updated_at", now)
            put("down_mode", settings.downIndex)
            put("up_mode", settings.upIndex)
            put("down_system", settings.downSystemIndex)
            put("up_system", settings.upSystemIndex)
            put("down_duration", settings.downDuration.toDouble())
            put("down_strength", settings.downStrength.toDouble())
            put("up_duration", settings.upDuration.toDouble())
            put("up_strength", settings.upStrength.toDouble())
            put("theme_mode", settings.themeMode)
            put("debug_log", settings.debugLog)
        }
        return atomicWrite(HostPaths.settings(context), json.toString())
    }

    /**
     * 原子写：临时文件 → `fd.sync()` → 删除旧文件并重命名；重命名失败时退化为流拷贝。
     */
    private fun atomicWrite(target: java.io.File, text: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parentFile, target.name + ".tmp")
        java.io.FileOutputStream(tmp, false).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            return@runCatching true
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.onFailure { android.util.Log.w(TAG, "write failed: ${target.name}", it) }.getOrDefault(false)

    private fun parseUi(text: String): UiState = runCatching {
        val json = org.json.JSONObject(text)
        UiState(
            fabX = json.optDouble("fab_x", 0.0).toFloat(),
            fabY = json.optDouble("fab_y", UiState.DEFAULT_FAB_Y.toDouble()).toFloat(),
            colorMode = json.optInt("color_mode", 2),
        )
    }.getOrDefault(UiState())

    private fun serializeUi(ui: UiState): String = org.json.JSONObject().apply {
        put("fab_x", ui.fabX.toDouble())
        put("fab_y", ui.fabY.toDouble())
        put("color_mode", ui.colorMode)
    }.toString()

    private fun logInfo(message: String) = android.util.Log.i(TAG, message)

    /** 监听 config 目录的写入与重命名事件（临时文件 → 正式文件的落地方式）。 */
    private class ConfigFileObserver(
        dir: java.io.File,
        private val onChanged: () -> Unit,
    ) : android.os.FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {

        override fun onEvent(event: Int, path: String?) {
            if (path == null) return
            if (path.endsWith(".tmp")) return
            onChanged()
        }
    }

    /** 极简去抖器：单线程 Handler，避免引入协程作用域依赖。 */
    private class Debouncer {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pending: Runnable? = null

        fun postDelayed(delayMs: Long, block: () -> Unit) {
            pending?.let { handler.removeCallbacks(it) }
            val runnable = Runnable { pending = null; block() }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }

        fun cancel() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }
    }
}
