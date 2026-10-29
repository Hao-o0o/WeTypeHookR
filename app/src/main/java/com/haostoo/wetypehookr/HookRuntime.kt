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

    /** 框架名称（LSPosed / EdXposed…），未取到时为 "unknown"。 */
    @Volatile
    var frameworkName: String = "unknown"
        private set

    /** 框架版本号。 */
    @Volatile
    var frameworkVersion: String = "unknown"
        private set

    /** 框架 API 版本。 */
    @Volatile
    var frameworkApiVersion: Int = -1
        private set

    /** 已加载的宿主包名。 */
    @Volatile
    var hostPackage: String = ""
        private set

    fun markLoaded(host: String) {
        hostPackage = host
        heartbeatAt = System.currentTimeMillis()
    }

    /** 记录当前框架信息；读取失败时保留上一次的值。 */
    fun markFramework(name: String?, version: String?, apiVersion: Int) {
        name?.takeIf { it.isNotBlank() }?.let { frameworkName = it }
        version?.takeIf { it.isNotBlank() }?.let { frameworkVersion = it }
        if (apiVersion > 0) frameworkApiVersion = apiVersion
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
            frameworkName = frameworkName,
            configUpdatedAt = configUpdatedAt,
            heartbeatAt = heartbeatAt,
        )
    }

    fun onConfigApplied(updatedAt: Long) {
        configUpdatedAt = updatedAt
        heartbeatAt = System.currentTimeMillis()
    }
}
