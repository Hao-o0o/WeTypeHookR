package com.haostoo.wetypehookr

import android.content.Context
import android.os.FileObserver
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 单快照配置仓库：`<host>/files/haostoo/config/state.json` 是唯一权威来源。
 *
 * - 写：`update` → `AtomicFile`（临时文件 + fsync + rename，自带备份）+ 单锁串行化；
 * - 读：向 UI 暴露 [state]（`StateFlow`），Compose 直接订阅；
 * - 热更新：`FileObserver` 只盯 `state.json`，200ms 去抖，`updated_at` 相同则跳过（自写回环）；
 * - 损坏：非法内容改名为 `state.bad.<时间戳>.json` 留证，退回默认值继续跑；
 * - 迁移：旧三件套 / 旧 `config.xml` 一次性合并，旧文件移入 `config/legacy/`。
 */
object SettingsStore {

    private const val TAG = "WETYPE_CFG"

    private const val DEBOUNCE_MS = 200L

    private const val STATE_NAME = "state.json"

    private val _state = MutableStateFlow(SettingsModel.HostState())

    /** 当前宿主的完整状态；UI 订阅它，Hook 只读 [HapticEngine] 的缓存。 */
    val state: StateFlow<SettingsModel.HostState> = _state.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "haostoo-cfg").apply { isDaemon = true }
    }

    private var observer: FileObserver? = null

    private var watchedHost: String? = null

    private var appContext: Context? = null

    private val debouncer = Debouncer()

    // ================= 生命周期 =================

    /** 宿主进程启动时调用一次：读快照并挂文件监听。 */
    @Synchronized
    fun attach(context: Context, host: String) {
        if (watchedHost == host) return
        stopWatching()

        appContext = context.applicationContext
        watchedHost = host

        apply(readOrMigrate(context, host))
        startWatching(context, host)
    }

    /** 只读地取当前内存快照。 */
    fun current(): SettingsModel.HostState = _state.value

    // ================= 写 =================

    /**
     * 以 transform 生成下一份状态并落盘，成功后回填 `StateFlow` 与 Hook 缓存。
     *
     * 所有写操作都必须走这里，外部不得直接写文件。
     */
    @Synchronized
    fun update(
        context: Context,
        host: String,
        transform: (SettingsModel.HostState) -> SettingsModel.HostState,
    ): Boolean {
        val next = transform(_state.value).copy(
            schema = SettingsModel.SCHEMA_VERSION,
            updatedAt = System.currentTimeMillis(),
        )
        val ok = writeState(context.applicationContext ?: context, next)
        if (ok) apply(next)
        return ok
    }

    /** 主线程调用点（FAB 落位、导入）走这个，避免在主线程写盘。 */
    fun updateAsync(
        context: Context,
        host: String,
        transform: (SettingsModel.HostState) -> SettingsModel.HostState,
    ) {
        val app = context.applicationContext
        ioExecutor.execute { update(app, host, transform) }
    }

    // ================= 读 =================

    /** 另一个宿主的一次性只读读取；读不到（目录不可互访 / 不存在）返回 null。 */
    fun readExternal(host: String): SettingsModel.HostState? = runCatching {
        val file = HostPaths.externalState(host)
        if (!file.isFile) return null
        SettingsModel.json.decodeFromString(SettingsModel.HostState.serializer(), file.readText())
    }.onFailure { Log.w(TAG, "readExternal failed: $host", it) }.getOrNull()

    /** 重新从磁盘加载当前宿主快照（导出、切回本宿主时用）。 */
    fun reload(context: Context, host: String): SettingsModel.HostState {
        val loaded = readOrMigrate(context.applicationContext ?: context, host)
        apply(loaded)
        return loaded
    }

    // ================= 内部实现 =================

    private fun apply(next: SettingsModel.HostState) {
        _state.value = next
        HapticEngine.cachedSettings = next.settings
        HapticEngine.cachedCustomHaptic = next.waveform
        HookRuntime.onConfigApplied(next.updatedAt)
    }

    /** 读快照；不存在则迁移；内容非法则留证后退回默认值。 */
    private fun readOrMigrate(context: Context, host: String): SettingsModel.HostState {
        val file = HostPaths.state(context)
        if (file.isFile) {
            parse(file)?.let { return it }
            quarantine(file)
            return SettingsModel.HostState(updatedAt = System.currentTimeMillis())
        }

        val migrated = buildMigratedState(context)
        writeState(context, migrated)
        moveLegacyFiles(context)
        Log.i(TAG, "migrated to $STATE_NAME for $host")
        return migrated
    }

    private fun parse(file: File): SettingsModel.HostState? = runCatching {
        SettingsModel.json.decodeFromString(SettingsModel.HostState.serializer(), file.readText())
    }.onFailure { Log.w(TAG, "$STATE_NAME parse failed, keep last valid", it) }.getOrNull()

    /** 非法内容改名为 `state.bad.<ts>.json`，保留现场。 */
    private fun quarantine(file: File) {
        runCatching {
            val bad = File(file.parentFile, "state.bad.${System.currentTimeMillis()}.json")
            file.renameTo(bad)
            Log.w(TAG, "$STATE_NAME is invalid, moved to ${bad.name}")
        }.onFailure { Log.w(TAG, "quarantine failed", it) }
    }

    /** 旧三件套 → 旧 config.xml → 默认值。 */
    private fun buildMigratedState(context: Context): SettingsModel.HostState {
        val settingsFile = HostPaths.legacySettings(context)
        val hapticFile = HostPaths.legacyHaptic(context)
        val uiFile = HostPaths.legacyUi(context)
        val xmlFile = HostPaths.legacyXml(context)

        val legacySettings = settingsFile.takeIf { it.isFile }
            ?.let { runCatching { SettingsModel.parseLegacySettings(it.readText()) }.getOrNull() }
        val legacyHaptic = hapticFile.takeIf { it.isFile }
            ?.let { runCatching { SettingsModel.parseLegacyHaptic(it.readText()) }.getOrNull() }
        val legacyUi = uiFile.takeIf { it.isFile }
            ?.let { runCatching { SettingsModel.parseLegacyUi(it.readText()) }.getOrNull() }
        val fromXml = xmlFile.takeIf { it.isFile }
            ?.let { runCatching { LegacyConfigImporter.parseLegacyXml(it.readText()) }.getOrNull() }

        return SettingsModel.HostState(
            updatedAt = System.currentTimeMillis(),
            settings = legacySettings ?: fromXml ?: SettingsModel.defaultSettings(),
            waveform = legacyHaptic,
            ui = legacyUi ?: SettingsModel.UiState(),
        )
    }

    /** 迁移后把旧文件移入 `config/legacy/`，不删除，便于回滚。 */
    private fun moveLegacyFiles(context: Context) {
        val legacyDir = HostPaths.legacyDir(context)
        listOf(
            HostPaths.legacySettings(context),
            HostPaths.legacyHaptic(context),
            HostPaths.legacyUi(context),
            HostPaths.legacyXml(context),
        ).forEach { file ->
            if (!file.isFile) return@forEach
            runCatching {
                val target = File(legacyDir, file.name)
                if (target.exists()) target.delete()
                file.renameTo(target)
            }.onFailure { Log.w(TAG, "move legacy failed: ${file.name}", it) }
        }
    }

    private fun writeState(context: Context, state: SettingsModel.HostState): Boolean = runCatching {
        val atomic = AtomicFile(HostPaths.state(context))
        val bytes = SettingsModel.json
            .encodeToString(SettingsModel.HostState.serializer(), state)
            .toByteArray(Charsets.UTF_8)
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            out.fd.sync()
            atomic.finishWrite(out)
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            throw e
        }
        true
    }.onFailure { Log.w(TAG, "write $STATE_NAME failed", it) }.getOrDefault(false)

    private fun startWatching(context: Context, host: String) {
        val listener = object : FileObserver(HostPaths.configDir(context).absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != STATE_NAME) return
                debouncer.postDelayed(DEBOUNCE_MS) { reloadFromWatch(host) }
            }
        }
        observer = listener
        runCatching { listener.startWatching() }
            .onFailure { Log.w(TAG, "FileObserver start failed", it) }
    }

    private fun stopWatching() {
        observer?.let { runCatching { it.stopWatching() } }
        observer = null
        debouncer.cancel()
    }

    /** 监听线程触发的重读：`updated_at` 没变说明是自己写的回环，直接跳过。 */
    private fun reloadFromWatch(host: String) {
        val context = appContext ?: return
        val file = HostPaths.state(context)
        if (!file.isFile) return
        val next = parse(file) ?: return
        if (next.updatedAt == _state.value.updatedAt) return
        apply(next)
        Log.i(TAG, "config reloaded for $host at ${next.updatedAt}")
    }

    /** 极简去抖器：单线程 Handler，避免引入协程作用域依赖。 */
    private class Debouncer {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pending: Runnable? = null

        fun postDelayed(delayMs: Long, block: () -> Unit) {
            pending?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                pending = null
                block()
            }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        }

        fun cancel() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }
    }
}
