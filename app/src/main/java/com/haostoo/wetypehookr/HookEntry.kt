package com.haostoo.wetypehookr

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.registerModuleActivities
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule

/**
 * 模块唯一入口。
 *
 * 只做装配，不含业务逻辑：
 * 装触摸 Hook → 挂配置监听 → 注册寄生 Activity → 注入设置页入口 FAB。
 */
@YukiHookLibXposedEntry(
    targetApiVersion = 102,
    scope = [HostCatalog.WETYPE, HostCatalog.DOUBAO],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.NONE,
)
object HookEntry : YukiHookXposedModule {

    private const val TAG = "WETYPE_HOOK"

    override fun onHook() = encase {
        HostCatalog.ALL.forEach { host ->
            loadApp(host) {
                HookRuntime.markLoaded(host)

                installTouchHooks()
                appContext?.let { SettingsStore.attach(it, host) }

                withApplicationReady { app ->
                    HostCatalog.settingsActivity(host)?.let { proxy ->
                        runCatching { app.registerModuleActivities(proxy) }
                            .onFailure { Log.w(TAG, "registerModuleActivities failed: $host", it) }
                    }
                }

                installFabHook()
            }
        }
    }

    /** 触摸 → 震动。只读内存缓存、只做副作用，不改参数也不吞结果。 */
    private fun PackageParam.installTouchHooks() {
        val (className, methodName) = HostCatalog.touchTarget(packageName) ?: return
        val target = "$className#$methodName"

        val installed = loaders().any { loader ->
            runCatching {
                val method = Class.forName(className, false, loader)
                    .getDeclaredMethod(methodName, MotionEvent::class.java)
                method.hook {
                    before {
                        val view = instanceOrNull as? View ?: return@before
                        val event = args.getOrNull(0) as? MotionEvent ?: return@before
                        HapticEngine.dispatch(view, event)
                    }
                }
                true
            }.onFailure { Log.w(TAG, "hook install failed: $target", it) }.getOrDefault(false)
        }

        HookRuntime.markHook(installed)
        // 宿主版本升级导致类名变更时只记日志，绝不影响输入法本身。
        if (installed) Log.i(TAG, "hook installed: $target@$packageName")
        else Log.w(TAG, "hook target not found: $target@$packageName")
    }

    /**
     * 在宿主设置主页面注入入口 FAB。
     *
     * 白名单判断放在回调内，避免 Hook 所有 Activity 的 onResume 后做无谓工作。
     */
    private fun PackageParam.installFabHook() {
        val host = packageName
        runCatching {
            Activity::class.java.getDeclaredMethod("onResume").hook {
                after {
                    val activity = instanceOrNull as? Activity ?: return@after
                    if (!HostFabInjector.isTargetSettingsActivity(host, activity.javaClass.name)) return@after
                    HostFabInjector.attach(activity, host, SettingsStore.state.value.ui)
                }
            }
            Log.i(TAG, "fab hook installed: $host")
        }.onFailure { Log.w(TAG, "fab hook install failed: $host", it) }
    }

    /** 宿主包名可能位于子 ClassLoader（插件化），逐个尝试，命中即止。 */
    private fun PackageParam.loaders(): List<ClassLoader> =
        generateSequence(hostClassLoader) { it.parent }.toList()
}
