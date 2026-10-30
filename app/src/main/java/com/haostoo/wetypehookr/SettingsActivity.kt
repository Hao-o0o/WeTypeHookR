package com.haostoo.wetypehookr

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.util.concurrent.atomic.AtomicInteger

/**
 * 设置页：模块的类，跑在宿主进程里。
 *
 * 因此 `packageName` / `pid` / `filesDir` 全部是宿主的，配置直接落宿主目录，
 * 与 Hook 共享同一批文件与同一个内存缓存。
 */
class SettingsActivity : BaseHostActivity() {

    @SuppressLint("LogNotTimber")
    override fun onCreate(savedInstanceState: Bundle?) {
        aliveCount.incrementAndGet()
        try {
            super.onCreate(savedInstanceState)
        } catch (t: Throwable) {
            // 主题 / 资源注入失败只会在这里暴露，写盘比 logcat 可靠。
            HostDiag.logNow(this, "UI", "onCreate failed: ${Log.getStackTraceString(t)}")
            throw t
        }
        enableEdgeToEdge()

        Log.i(TAG, "SettingsActivity onCreate host=$packageName pid=${Process.myPid()}")
        HostDiag.log(this, "UI", "onCreate host=$packageName pid=${Process.myPid()}")

        // 兜底：正常路径下 HookEntry 已在 withApplicationReady 里 attach 过，
        // 这里幂等（同一 host 直接返回），只防「页面起来了但配置没加载」。
        runCatching { SettingsStore.attach(this, packageName) }

        setContent {
            SettingsApp()
        }
    }

    override fun onDestroy() {
        aliveCount.decrementAndGet()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WETYPE_UI"

        private val aliveCount = AtomicInteger()

        /** 有模块设置页存活时，框架会拒绝热重载。 */
        val isAlive: Boolean get() = aliveCount.get() > 0
    }
}
