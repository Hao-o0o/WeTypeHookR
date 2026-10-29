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

    /** 宿主设置主页面：既作为 FAB 注入白名单，也作为寄生 Activity 的 proxy。 */
    private val SETTINGS_ACTIVITY = mapOf(
        WETYPE to "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
        DOUBAO to "com.bytedance.android.doubaoime.activity.SettingsActivityNext",
    )

    /** 触摸事件拦截面：微信拦根 View 的 dispatchTouchEvent，豆包拦键盘 View 的 onTouchEvent。 */
    private val TOUCH_TARGET = mapOf(
        WETYPE to ("com.tencent.wetype.plugin.hld.view.ImeRootView" to "dispatchTouchEvent"),
        DOUBAO to ("com.bytedance.android.input.keyboard.KeyboardView" to "onTouchEvent"),
    )

    fun isSupported(host: String): Boolean = ALL.contains(host)

    fun settingsActivity(host: String): String? = SETTINGS_ACTIVITY[host]

    fun touchTarget(host: String): Pair<String, String>? = TOUCH_TARGET[host]

    fun labelRes(host: String): Int = when (host) {
        WETYPE -> R.string.host_name_wetype
        DOUBAO -> R.string.host_name_doubao
        else -> R.string.state_unknown
    }
}
