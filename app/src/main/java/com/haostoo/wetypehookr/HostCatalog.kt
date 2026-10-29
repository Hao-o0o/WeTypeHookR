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
            "com.bytedance.android.doubaoime.activity.LauncherActivity",
            "com.bytedance.android.doubaoime.activity.SettingsActivity",
            "com.bytedance.android.doubaoime.setting.SettingsActivity",
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
     * 挑一个可用的 proxy。
     *
     * 类存在 ≠ 在宿主清单里声明，而 proxy 必须是宿主已声明的 Activity，
     * 所以先用 `getActivityInfo` 逐个验证候选；都不中时，从宿主声明的 Activity 里
     * 找一个 exported 且无权限限制的「Setting 页面」兜底；再不行才退回首选值（失败会留日志）。
     */
    fun resolveSettingsActivity(context: android.content.Context, host: String): String? {
        val pm = context.packageManager
        val candidates = SETTINGS_ACTIVITY_FALLBACK[host] ?: listOfNotNull(SETTINGS_ACTIVITY[host])

        val declared = runCatching {
            pm.getPackageInfo(host, android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
        }.getOrDefault(emptyArray())

        fun isDeclared(name: String): Boolean = declared.any { it.name == name }

        fun isStartable(name: String): Boolean = declared.any {
            it.name == name && it.exported && it.permission.isNullOrBlank()
        }

        // 优先级：已声明且导出无权限 > 已声明且导出（可能是 signature 权限，同 UID 可过） > 已声明
        candidates.firstOrNull { isStartable(it) }?.let { return it }
        candidates.firstOrNull { name ->
            declared.any { it.name == name && it.exported }
        }?.let { return it }
        candidates.firstOrNull { isDeclared(it) }?.let { return it }

        declared.firstOrNull {
            it.exported && it.permission.isNullOrBlank() &&
                it.name.startsWith(host) && it.name.contains("Setting", ignoreCase = true)
        }?.let { return it.name }

        return candidates.firstOrNull()
    }

    fun touchTarget(host: String): Pair<String, String>? = TOUCH_TARGET[host]

    fun labelRes(host: String): Int = when (host) {
        WETYPE -> R.string.host_name_wetype
        DOUBAO -> R.string.host_name_doubao
        else -> R.string.state_unknown
    }
}
