package com.haostoo.wetypehookr

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.highcapable.yukihookapi.YukiHook
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.registerModuleActivities
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.highcapable.yukihookapi.hook.xposed.reload.lifecycle.registerModuleLifecycle

/**
 * 模块唯一入口。
 *
 * 只做装配，不含业务逻辑：
 * 装触摸 Hook → 挂配置监听 → 注册寄生 Activity → 注入设置页入口 FAB。
 *
 * 热重载：`HotReload.AUTO`（需要 API 102）。设置页存活时拒绝重载，
 * 退出时释放文件监听与日志线程，避免旧实例残留。
 */
@YukiHookLibXposedEntry(
    targetApiVersion = 102,
    scope = [HostCatalog.WETYPE, HostCatalog.DOUBAO],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.AUTO,
)
object HookEntry : YukiHookXposedModule {

    private const val TAG = "WETYPE_HOOK"

    override fun onInit() {
        registerModuleLifecycle {
            // 模块 Activity 还在前台时不接受重载：框架要求被代理的页面先销毁。
            canReload { !SettingsActivity.isAlive }

            onDispose {
                SettingsStore.detach()
                DebugLog.shutdown()
                HookRuntime.reset()
                Log.i(TAG, "module disposed for reload")
            }

            onReloaded {
                Log.i(TAG, "module reloaded, host=${HookRuntime.hostPackage}")
            }
        }
    }

    override fun onHook() = encase {
        HostCatalog.ALL.forEach { host ->
            loadApp(host) {
                HookRuntime.markLoaded(host)
                // 框架名 / 版本 / API 版本由 YukiHook 的运行时桥接读取，取不到时保持 unknown。
                runCatching {
                    HookRuntime.markFramework(
                        name = YukiHook.Status.Executor.name,
                        version = YukiHook.Status.Executor.version,
                        apiVersion = YukiHook.Status.Executor.apiVersion,
                    )
                }

                // 崩溃留证：宿主进程里模块导致的未捕获异常写进 diag.log（logcat 会轮转丢失）。
                hostApplication?.let { HostDiag.installCrashHandler(it) }

                installTouchHooks()
                hostApplication?.let { SettingsStore.attach(it, host) }

                withApplicationReady { app ->
                    registerActivities(app, host)
                }

                installFabHook()
            }
        }
    }

    /** 注册模块 Activity：proxy 必须是宿主清单里真实声明、可启动的 Activity。 */
    private fun registerActivities(context: android.content.Context, host: String) {
        val proxy = HostCatalog.resolveSettingsActivity(context, host) ?: return
        val proc = currentProcessName()
        runCatching { context.registerModuleActivities(proxy) }
            .onSuccess {
                Log.i(TAG, "registerModuleActivities ok: host=$host proxy=$proxy proc=$proc")
                HostDiag.log(context, "REG", "ok host=$host proxy=$proxy proc=$proc")
            }
            .onFailure {
                Log.w(TAG, "registerModuleActivities failed: host=$host proxy=$proxy proc=$proc", it)
                HostDiag.log(context, "REG", "fail host=$host proxy=$proxy proc=$proc err=$it")
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

    /** 诊断用：把宿主声明的 Activity 打出来（只在启动设置页失败时调用）。 */
    fun logHostActivities(activity: Activity) {
        runCatching {
            val info = activity.packageManager
                .getPackageInfo(activity.packageName, PackageManager.GET_ACTIVITIES)
            val names = info.activities?.map { it.name }.orEmpty()
            Log.w(TAG, "host=${activity.packageName} proc=${currentProcessName()} activities=${names.size}")
            names.forEach { Log.w(TAG, "  activity: $it") }
            HostDiag.log(activity, "ACT", "host=${activity.packageName} n=${names.size}")
            names.take(40).forEach { HostDiag.log(activity, "ACT", "  $it") }
        }.onFailure { Log.w(TAG, "dump activities failed", it) }
    }

    fun currentProcessName(): String = runCatching { Application.getProcessName() }.getOrDefault("?")
}
