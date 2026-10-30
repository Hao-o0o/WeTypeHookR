package com.haostoo.wetypehookr

import android.content.Context
import android.content.pm.PackageManager

/**
 * 宿主目录。
 *
 * 四件事集中在这里，避免散落在代码里：
 * 1. 受支持宿主的包名与显示名（含讯飞这类「同族多包名」的变体）；
 * 2. 寄生 Activity 的 proxy（必须是宿主 Manifest 中真实存在、`launchMode=standard` 的页面）；
 * 3. 触摸 Hook 的目标类与方法；
 * 4. 由包名归并出的「家族」，所有按宿主编排的表都以家族为键。
 */
object HostCatalog {

    const val WETYPE = "com.tencent.wetype"

    const val DOUBAO = "com.bytedance.android.doubaoime"

    const val IFLYTEK = "com.iflytek.inputmethod"

    const val GBOARD = "com.google.android.inputmethod.latin"

    /** 精确包名：用于 `scope`（LSPosed 只认精确包名）与设置页的宿主列表。 */
    val ALL: List<String> = listOf(WETYPE, DOUBAO, IFLYTEK, GBOARD)

    /**
     * 以「前缀」承认的家族。
     *
     * 讯飞有 `com.iflytek.inputmethod.oem` / `.miui` 等定制版，包名不可枚举，
     * 只能用 `pkg == base || pkg.startsWith("$base.")` 判定。
     * 注意不要写成纯前缀比较，否则会误伤 `com.iflytek.inputmethodxxx`；
     * 也不会误伤 `com.iflytek.elpmobile.student`（第二段不同）。
     */
    private val PREFIX_FAMILIES = listOf(IFLYTEK)

    /** 宿主设置主页面：FAB 注入白名单的首选（不一定是可用的 proxy）。 */
    private val SETTINGS_ACTIVITY = mapOf(
        WETYPE to "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
        DOUBAO to "com.bytedance.android.doubaoime.activity.SettingsActivityNext",
        IFLYTEK to "com.iflytek.inputmethod.FlyImeSettingsActivity",
        GBOARD to "com.google.android.apps.inputmethod.latin.preference.SettingsActivity",
    )

    /**
     * 设置页候选表（FAB 白名单 + proxy 兜底都用它）。
     *
     * 宿主换版本可能改名或把设置页挪进子进程，命中不到时按顺序退到下一个。
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
        IFLYTEK to listOf(
            "com.iflytek.inputmethod.FlyImeSettingsActivity",
            "com.iflytek.inputmethod.LauncherSettingsActivity",
        ),
        GBOARD to listOf(
            "com.google.android.apps.inputmethod.latin.preference.SettingsActivity",
            "com.google.android.apps.inputmethod.latin.spelling.LatinSpellCheckerSettingsActivity",
            "com.google.android.apps.inputmethod.latin.stylus.StylusSettingsActivity",
        ),
    )

    /**
     * 触摸事件拦截面：一律拦「键盘根 View 的 dispatchTouchEvent」。
     *
     * - 讯飞：`keyboard/normal/InputView`，IME 的 `onCreateInputView` 层层委托后返回它；
     * - Gboard：`inputview/InputView`，`GoogleInputMethodService.onCreateInputView` 里
     *   `View.inflate(R.layout…)` 出来的就是它（方法是 `final`，但 Hook 不受影响）。
     */
    private val TOUCH_TARGET = mapOf(
        WETYPE to ("com.tencent.wetype.plugin.hld.view.ImeRootView" to "dispatchTouchEvent"),
        DOUBAO to ("com.bytedance.android.input.keyboard.KeyboardView" to "onTouchEvent"),
        IFLYTEK to ("com.iflytek.inputmethod.keyboard.normal.InputView" to "dispatchTouchEvent"),
        GBOARD to ("com.google.android.libraries.inputmethod.inputview.InputView" to "dispatchTouchEvent"),
    )

    /**
     * 承载模块 Activity 的 proxy 候选，**与 FAB 注入白名单解耦**。
     *
     * 选 proxy 的三条硬要求：导出、无权限限制、当前不在 Activity 栈里且 `launchMode=standard`。
     * - 豆包曾用 `SettingsActivityNext`（带 signature 权限 + singleInstance 语义），
     *   一次失败的启动会留下永远画不出来的记录，之后每次 start 只走 `onNewIntent`，
     *   模块页面再也出不来；换成 `LauncherActivity` 后正常。
     * - 讯飞的 `FlyImeSettingsActivity` 是 `launchMode=singleInstance`，同样不能当 proxy，
     *   改用同进程的 `LauncherSettingsActivity`（导出、standard）。
     * - Gboard 全在主进程，`libraries.inputmethod.launcher.LauncherActivity` 导出且 standard。
     */
    private val PROXY_ACTIVITY = mapOf(
        WETYPE to listOf(
            "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
            "com.tencent.wetype.plugin.hld.activity.ImeSettingActivity",
        ),
        DOUBAO to listOf(
            "com.bytedance.android.doubaoime.activity.LauncherActivity",
            "com.bytedance.android.doubaoime.activity.SettingsActivityNext",
            "com.bytedance.android.doubaoime.activity.SettingsActivity",
        ),
        IFLYTEK to listOf(
            "com.iflytek.inputmethod.LauncherSettingsActivity",
            "com.iflytek.inputmethod.LauncherActivity",
        ),
        GBOARD to listOf(
            "com.google.android.libraries.inputmethod.launcher.LauncherActivity",
            "com.google.android.apps.inputmethod.latin.preference.SettingsActivity",
        ),
    )

    /**
     * 各家族的类名前缀，用于「候选全部失效」时按类名兜底。
     *
     * Gboard 的设置页在 `com.google.android.apps…` 下、键盘在 `com.google.android.libraries…` 下，
     * 两个前缀都收，否则包名开头匹配不到。
     */
    private val CLASS_PREFIX = mapOf(
        WETYPE to listOf("com.tencent.wetype"),
        DOUBAO to listOf("com.bytedance.android.doubaoime"),
        IFLYTEK to listOf("com.iflytek.inputmethod"),
        GBOARD to listOf(
            "com.google.android.inputmethod",
            "com.google.android.apps.inputmethod",
            "com.google.android.libraries.inputmethod",
        ),
    )

    /** 是否受支持（含讯飞定制版这类不可枚举的前缀家族）。 */
    fun isSupported(pkg: String): Boolean =
        pkg in ALL || PREFIX_FAMILIES.any { pkg.startsWith("$it.") }

    /** 包名 → 家族；未收录的包名原样返回。 */
    fun familyOf(pkg: String): String = when {
        pkg in ALL -> pkg
        else -> PREFIX_FAMILIES.firstOrNull { pkg.startsWith("$it.") } ?: pkg
    }

    /**
     * 是否是宿主的设置页。
     *
     * 先精确匹配候选；候选不再命中（宿主改版）时，退化为「同族类名 + 含 Setting」的页面，
     * 保证 FAB 仍能注入，避免整个功能因为没有入口而失效。
     */
    fun isSettingsActivity(host: String, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val family = familyOf(host)
        val candidates = SETTINGS_ACTIVITY_FALLBACK[family] ?: return false
        if (className in candidates) return true
        val prefixes = CLASS_PREFIX[family].orEmpty()
        return className.contains("Setting", ignoreCase = true) && prefixes.any { className.startsWith(it) }
    }

    /**
     * 挑一个可用的 proxy。
     *
     * 类存在 ≠ 在宿主清单里声明，而 proxy 必须是宿主已声明的 Activity，
     * 所以先用 `getActivityInfo` 逐个验证候选；都不中时，从宿主声明的 Activity 里
     * 找一个 exported 且无权限限制的同族「Setting 页面」兜底；再不行才退回首选值（失败会留日志）。
     */
    fun resolveSettingsActivity(context: Context, host: String): String? {
        val pm = context.packageManager
        val family = familyOf(host)
        val candidates = PROXY_ACTIVITY[family] ?: listOfNotNull(SETTINGS_ACTIVITY[family])

        val declared = runCatching {
            pm.getPackageInfo(host, PackageManager.GET_ACTIVITIES).activities.orEmpty()
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

        val prefixes = CLASS_PREFIX[family].orEmpty()
        declared.firstOrNull {
            it.exported && it.permission.isNullOrBlank() &&
                it.name.contains("Setting", ignoreCase = true) &&
                prefixes.any { prefix -> it.name.startsWith(prefix) }
        }?.let { return it.name }

        return candidates.firstOrNull()
    }

    fun touchTarget(host: String): Pair<String, String>? = TOUCH_TARGET[familyOf(host)]

    /** 宿主显示名的资源（家族级）。 */
    fun labelRes(host: String): Int = when (familyOf(host)) {
        WETYPE -> R.string.host_name_wetype
        DOUBAO -> R.string.host_name_doubao
        IFLYTEK -> R.string.host_name_iflytek
        GBOARD -> R.string.host_name_gboard
        else -> R.string.state_unknown
    }

    /**
     * 宿主显示名（字符串）。
     *
     * 定制版走前缀匹配时家族名相同，这里补上包名后缀，避免两个变体在列表里长得一模一样：
     * `com.iflytek.inputmethod.oem` → 「讯飞输入法 (oem)」。
     */
    fun displayName(context: Context, host: String): String {
        val family = familyOf(host)
        val base = context.getString(labelRes(family))
        return if (host == family) base else "$base (${host.removePrefix("$family.")})"
    }
}
