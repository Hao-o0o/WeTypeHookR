package com.haostoo.wetypehookr

/**
 * 宿主目录。
 *
 * 三件事集中在这里，避免散落在代码里：
 * 1. 受支持宿主的包名与显示名；
 * 2. 寄生 Activity 的 proxy（必须是宿主 Manifest 中真实存在、`launchMode=standard` 的页面）；
 * 3. 触摸 Hook 的目标类与方法。
 */
object HostCatalog {

    const val WETYPE = "com.tencent.wetype"

    const val DOUBAO = "com.bytedance.android.doubaoime"

    val ALL: List<String> = listOf(WETYPE, DOUBAO)

    /** 宿主设置主页面：既作为 FAB 注入白名单，也作为寄生 Activity 的 proxy 首选。 */
    private val SETTINGS_ACTIVITY = mapOf(
        WETYPE to "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
        DOUBAO to "com.bytedance.android.doubaoime.activity.SettingsActivityNext",
    )

    /**
     * 备选 proxy。
     *
     * 宿主换版本可能改名或把设置页挪进子进程，命中不到时按顺序退到下一个，
     * 避免直接拿一个不存在的类去 startActivity 导致 ActivityNotFound。
     */
    private val SETTINGS_ACTIVITY_FALLBACK = mapOf(
        WETYPE to listOf(
            "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
            "com.tencent.wetype.plugin.hld.activity.ImeSettingActivity",
        ),
        DOUBAO to listOf(
            "com.bytedance.android.doubaoime.activity.SettingsActivityNext",
            "com.bytedance.android.doubaoime.activity.SettingsActivity",
            "com.bytedance.android.doubaoime.setting.SettingsActivity",
            "com.bytedance.android.doubaoime.activity.MainActivity",
        ),
    )

    /** 触摸事件拦截面：微信拦根 View 的 dispatchTouchEvent，豆包拦键盘 View 的 onTouchEvent。 */
    private val TOUCH_TARGET = mapOf(
        WETYPE to ("com.tencent.wetype.plugin.hld.view.ImeRootView" to "dispatchTouchEvent"),
        DOUBAO to ("com.bytedance.android.input.keyboard.KeyboardView" to "onTouchEvent"),
    )

    fun isSupported(host: String): Boolean = ALL.contains(host)

    fun settingsActivity(host: String): String? = SETTINGS_ACTIVITY[host]

    /**
     * 是否是宿主的设置页。
     *
     * 先精确匹配候选；候选不再命中（宿主改版）时，退化为「宿主包内、类名包含 Setting」的页面，
     * 保证 FAB 仍能注入，避免整个功能因为没有入口而失效。
     */
    fun isSettingsActivity(host: String, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val candidates = SETTINGS_ACTIVITY_FALLBACK[host] ?: return false
        if (className in candidates) return true
        return className.startsWith(host) && className.contains("Setting", ignoreCase = true)
    }

    /**
     * 在宿主 ClassLoader 里挑一个真实存在的 proxy。
     *
     * 都找不到时退回首选值：让框架照常改写 Intent，失败日志里也能看到真实类名。
     */
    fun resolveSettingsActivity(host: String, loader: ClassLoader?): String? {
        val candidates = SETTINGS_ACTIVITY_FALLBACK[host] ?: return SETTINGS_ACTIVITY[host]
        if (loader == null) return candidates.firstOrNull()
        return candidates.firstOrNull { name ->
            runCatching { Class.forName(name, false, loader) }.isSuccess
        } ?: candidates.firstOrNull()
    }

    fun touchTarget(host: String): Pair<String, String>? = TOUCH_TARGET[host]

    fun labelRes(host: String): Int = when (host) {
        WETYPE -> R.string.host_name_wetype
        DOUBAO -> R.string.host_name_doubao
        else -> R.string.state_unknown
    }
}
