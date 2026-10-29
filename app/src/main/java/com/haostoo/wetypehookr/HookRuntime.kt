package com.haostoo.wetypehookr

/**
 * 进程内注入状态。
 *
 * 与配置完全分离：只描述「Hook 是否装好、最近一次生效的 revision、心跳时间」，
 * 供宿主设置页的注入状态卡直接读取（同进程，无需 IPC）。
 */
object HookRuntime {

    /** 触摸 Hook 是否安装成功。 */
    @Volatile
    var hookInstalled: Boolean = false
        private set

    /** 最近一次生效的配置 revision。 */
    @Volatile
    var configRevision: Long = 0L
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

    fun onConfigApplied(revision: Long) {
        configRevision = revision
        heartbeatAt = System.currentTimeMillis()
    }
}
