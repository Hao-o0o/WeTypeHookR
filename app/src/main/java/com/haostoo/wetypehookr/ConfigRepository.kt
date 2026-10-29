package com.haostoo.wetypehookr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 某个宿主当前生效配置的状态（文件为唯一来源，恒为 SYNCED）。 */
enum class SyncState {
    /** 已与宿主目录中的文件一致。 */
    SYNCED,

    /** 保留给历史代码；文件写入是同步的，不会出现该状态。 */
    PENDING_SYNC,

    /** 保留给历史代码；文件不可读时用默认值兜底。 */
    OFFLINE_CACHE,

    /** 首次运行，尚未写入任何配置。 */
    DEFAULT,
}

/**
 * 一个宿主的有效配置快照。
 *
 * 逻辑上每个宿主只有这一份配置：`hostPackage + revision + settings + waveform`。
 */
data class ConfigSnapshot(
    val hostPackage: String,
    val settings: SettingsModel.SettingsState,
    val waveform: SettingsModel.CustomHapticConfig?,
    val revision: Long,
    val state: SyncState,
    /** 历史字段，文件模型下恒为 false。 */
    val conflict: Boolean = false,
) {
    val writable: Boolean get() = state == SyncState.SYNCED
    val pending: Boolean get() = state == SyncState.PENDING_SYNC
}

/**
 * 配置仓库：宿主目录中的 JSON 文件是唯一权威来源。
 *
 * UI 只通过本对象读写配置，不直接碰文件；Hook 侧由 [SettingsStore] 的文件监听自行热更新，
 * 因此「保存」= 写文件成功，不存在跨进程同步队列。
 */
object ConfigRepository {

    private val _snapshots = MutableStateFlow<Map<String, ConfigSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ConfigSnapshot>> = _snapshots.asStateFlow()

    fun snapshot(hostPackage: String): ConfigSnapshot? = _snapshots.value[hostPackage]

    /**
     * 当前宿主进程内的注入状态。
     *
     * 设置页运行在宿主进程里，直接读 [HookRuntime]，没有心跳文件也没有延迟。
     * 查询非当前进程的宿主时返回 null（页面据此显示「未观测到注入」）。
     */
    fun hookStatus(hostPackage: String): SettingsModel.HookStatus? {
        if (currentProcessHost() != hostPackage) return null
        return SettingsModel.HookStatus(
            hookInstalled = HookRuntime.hookInstalled,
            moduleVersion = BuildConfig.VERSION_NAME,
            frameworkName = null,
            configRevision = HookRuntime.configRevision,
            heartbeatAt = HookRuntime.heartbeatAt,
        )
    }

    fun refresh(context: Context) {
        HostCatalog.ALL.forEach { refresh(context, it) }
    }

    @Synchronized
    fun refresh(context: Context, hostPackage: String) {
        if (!HostCatalog.isSupported(hostPackage)) return
        val loaded = SettingsStore.load(context.applicationContext, hostPackage)
        _snapshots.value = _snapshots.value + (
            hostPackage to ConfigSnapshot(
                hostPackage = hostPackage,
                settings = loaded.settings,
                waveform = loaded.haptic,
                revision = loaded.revision,
                state = SyncState.SYNCED,
            )
            )
    }

    fun saveSettings(context: Context, host: String, state: SettingsModel.SettingsState): Boolean {
        val ok = SettingsStore.saveSettings(context.applicationContext, host, state)
        if (ok) refresh(context, host)
        return ok
    }

    fun saveWaveform(context: Context, host: String, config: SettingsModel.CustomHapticConfig): Boolean {
        val ok = SettingsStore.saveHaptic(context.applicationContext, host, config)
        if (ok) refresh(context, host)
        return ok
    }

    fun resetHost(context: Context, host: String): Boolean {
        val okSettings = saveSettings(context, host, SettingsModel.defaultSettings())
        val okWave = saveWaveform(context, host, SettingsModel.defaultCustomHaptic())
        return okSettings && okWave
    }

    /** 设置页所在的宿主包名（= 进程名归属），用于判断状态是否属于当前进程。 */
    private fun currentProcessHost(): String = HookRuntime.hostPackage
}
