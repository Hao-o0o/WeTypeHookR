package com.haostoo.wetypehookr

/**
 * 进程内注入状态。
 *
 * 与配置完全分离：只描述「Hook 是否装好、配置更新时间、心跳时间」，
 * 供宿主设置页的注入状态卡直接读取（同进程，无需 IPC）。
 */
object HookRuntime {

    /** 触摸 Hook 是否安装成功。 */
    @Volatile
    var hookInstalled: Boolean = false
        private set

    /** 最近一次生效的配置更新时间（= state.json 的 updated_at）。 */
    @Volatile
    var configUpdatedAt: Long = 0L
        private set

    /** 最近心跳时间。 */
    @Volatile
    var heartbeatAt: Long = 0L
        private set

    /** 已加载的宿主包名。 */
    @Volatile
    var hostPackage: String = ""
        private set

    fun markLoaded(host: String) {
        hostPackage = host
        heartbeatAt = System.currentTimeMillis()
    }

    fun markHook(installed: Boolean) {
        hookInstalled = installed
        heartbeatAt = System.currentTimeMillis()
    }

    /** 当前进程的注入状态；未加载时返回 null（页面显示「未观测到注入」）。 */
    fun status(): SettingsModel.HookStatus? {
        if (hostPackage.isEmpty()) return null
        return SettingsModel.HookStatus(
            hookInstalled = hookInstalled,
            moduleVersion = BuildConfig.VERSION_NAME,
            frameworkName = null,
            configUpdatedAt = configUpdatedAt,
            heartbeatAt = heartbeatAt,
        )
    }

    fun onConfigApplied(updatedAt: Long) {
        configUpdatedAt = updatedAt
        heartbeatAt = System.currentTimeMillis()
    }
}
