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
    scope = [HostCatalog.WETYPE, HostCatalog.DOUBAO, HostCatalog.IFLYTEK, HostCatalog.GBOARD],
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
        // 不再枚举精确包名：讯飞等宿主存在 `com.iflytek.inputmethod.*` 定制版，
        // 运行期按「家族前缀」判断，命中即装配，未命中的进程直接退出。
        val host = packageName
        if (!HostCatalog.isSupported(host)) {
            Log.i(TAG, "skip unsupported host: $host")
            return@encase
        }
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

            installTouchHooks()

            // 需要 Context 的一切都必须放进 withApplicationReady：
            // `onHook` 在 Application 创建之前跑，此时 `hostApplication` 是 null，
            // 直接 `hostApplication?.let { … }` 会在冷启动时被静默跳过 —— 配置就读不到了。
            withApplicationReady { app ->
                // 崩溃留证：宿主进程里模块导致的未捕获异常写进 diag.log（logcat 会轮转丢失）。
                HostDiag.installCrashHandler(app)
                SettingsStore.attach(app, host)
                registerActivities(app, host)
            }

            installFabHook()
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
        val deferHaptic = HostCatalog.defersHaptic(packageName)

        val installed = loaders().any { loader ->
            runCatching {
                val method = Class.forName(className, false, loader)
                    .getDeclaredMethod(methodName, MotionEvent::class.java)
                method.hook {
                    // 必须在 super 之后再震动：微信输入法与 Gboard 在自己的按键处理里也会调
                    // vibrator.vibrate(createOneShot(~25ms))（微信默认还走 performHapticFeedback，
                    // 经 WindowManager 异步下发），系统侧「后来的震动替换前一个」，
                    // 放在 before 里我们那 200ms/自定义波形会被顶掉，听起来就是「变弱/不完整」。
                    after {
                        val view = instanceOrNull as? View ?: return@after
                        val action = (args.getOrNull(0) as? MotionEvent)?.actionMasked ?: return@after
                        // MotionEvent 在 dispatch 返回后会被宿主回收，这里只传动作码。
                        if (deferHaptic) {
                            // 宿主自己会震动且排在我们之后，再让 24ms 给它，保证我们是最后一次震动。
                            HapticEngine.dispatchAfterHost(view, action)
                        } else {
                            HapticEngine.dispatchAction(view, action)
                        }
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
